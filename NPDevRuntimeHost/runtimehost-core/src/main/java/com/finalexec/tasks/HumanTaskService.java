package com.finalexec.tasks;

import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledHumanTask;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.MailTemplateRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * PROC-2: the human-task inbox as a READ-SIDE PROJECTION over parked flow instances. No table of
 * its own: a task "exists" exactly while a flow instance is WAITING_EVENT on an awaitEvent step that
 * declares a {@code task} block (PROC-1). The kernel's own wait descriptor ({@code _npdev.await},
 * FlowStateCodec) names the parked step, so the task metadata is looked up in the compiled model by
 * (flowName, stepName). Completing a task = publishing that step's awaited event with the
 * correlation/payload-match values the step needs, after checking the caller is an assignee.
 *
 * <p>The event is published straight through the kernel (never the REST {@code /api/events/publish}
 * path): that path demands PUBLISH_EVENTS, which a regular USER lacks, and this service has already
 * done the only authorization that matters here -- "is the caller an assignee of THIS task".
 */
public final class HumanTaskService {

    /** Mirrors FlowStateCodec's package-private constants (com.npdev.kernel) -- keep in sync. */
    static final String AWAIT_STATE_KEY = "_npdev.await";
    static final String AWAIT_STEP_NAME = "stepName";
    static final String AWAIT_DEADLINE = "timeoutDeadlineEpochMs";
    static final String AWAIT_PAYLOAD_MATCH_REFS = "payloadMatchRefs";

    /** Narrow seam over KernelRunner.publishExternalEvent(name, payload, corr, causation, ctx) -- keeps this class unit-testable. */
    @FunctionalInterface
    public interface EventPublisher {
        EventEnvelope publish(String eventName, Map<String, Object> payload, String correlationId,
                              String causationId, ExecutionContext ctx);
    }

    /** Thrown for every refusal; the controller maps code -> HTTP status. */
    public static final class TaskException extends RuntimeException {
        private final String code;

        public TaskException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    record Declared(String flowName, CompiledFlowStep step, CompiledHumanTask task) {
    }

    record Located(FlowInstance instance, Declared decl) {
    }

    private final Supplier<CompiledModel> model;
    private final FlowInstanceStore instances;
    private final EventPublisher publisher;
    private final Map<String, Object> completionLocks = new ConcurrentHashMap<>();

    public HumanTaskService(Supplier<CompiledModel> model, FlowInstanceStore instances, EventPublisher publisher) {
        this.model = model;
        this.instances = instances;
        this.publisher = publisher;
    }

    /** GET /api/tasks -- open tasks the caller may complete, oldest first. */
    public List<Map<String, Object>> myTasks(ExecutionContext ctx) {
        Map<String, Declared> declared = declaredTasks();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String eventName : awaitedEventNames(declared)) {
            for (FlowInstance instance : instances.findWaitingByEvent(eventName)) {
                Optional<Declared> decl = declaredFor(instance, declared);
                if (decl.isPresent() && sameTenant(ctx, instance) && isAssignee(ctx, instance, decl.get().task())) {
                    out.add(summary(instance, decl.get()));
                }
            }
        }
        out.sort(Comparator.comparingLong(m -> (Long) m.get("waitingSinceEpochMs")));
        return out;
    }

    /** GET /api/tasks/{executionId} -- summary plus the form (fields, outcomes) for one task. */
    public Map<String, Object> detail(String executionId, ExecutionContext ctx) {
        Located located = locate(executionId, ctx);
        CompiledHumanTask task = located.decl().task();
        Map<String, Object> view = summary(located.instance(), located.decl());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (CompiledHumanTask.Field f : task.getFields()) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", f.name());
            field.put("type", f.type());
            field.put("label", f.label() == null ? f.name() : f.label());
            field.put("required", f.required());
            field.put("maxLength", f.maxLength());
            fields.add(field);
        }
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (CompiledHumanTask.Outcome o : task.getOutcomes()) {
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("value", o.value());
            outcome.put("label", o.label() == null ? o.value() : o.label());
            outcomes.add(outcome);
        }
        view.put("fields", fields);
        view.put("outcomes", outcomes);
        view.put("outcomeField", task.getOutcomeField());
        return view;
    }

    /** POST /api/tasks/{executionId}/complete {stepName, outcome, values} */
    public Map<String, Object> complete(String executionId, String stepName, String outcome,
                                        Map<String, Object> values, ExecutionContext ctx) {
        Object lock = completionLocks.computeIfAbsent(executionId, k -> new Object());
        synchronized (lock) {
            try {
                Located located = locate(executionId, ctx);
                if (stepName == null || !stepName.equals(located.decl().step().getName())) {
                    throw new TaskException("TASK_NOT_OPEN", "This task was already answered or moved on.");
                }
                CompiledHumanTask task = located.decl().task();
                if (outcome == null || task.getOutcomes().stream().noneMatch(o -> o.value().equals(outcome))) {
                    throw new TaskException("TASK_OUTCOME_INVALID", "Unknown outcome: " + outcome);
                }
                Map<String, Object> payload = TaskForms.validateAndCoerce(task, values);
                payload.put(task.getOutcomeField(), outcome);
                // The resumed flow runs as its ORIGINAL starter, so record who actually answered.
                payload.put("completedBy", ctx.actorId());
                Map<String, Object> state = located.instance().state();
                // Satisfy the step's own payload match (e.g. tradeId = $saved.id) so the event resumes THIS instance.
                if (state.get(AWAIT_STATE_KEY) instanceof Map<?, ?> await
                        && await.get(AWAIT_PAYLOAD_MATCH_REFS) instanceof Map<?, ?> refs) {
                    refs.forEach((field, ref) ->
                            payload.put(String.valueOf(field), StateRefs.resolve(state, String.valueOf(ref))));
                }
                // The live state value wins over the row field (a sequential forEach overwrites it per iteration).
                Object liveCorrelation = state.get("correlationId");
                String correlationId = liveCorrelation == null || String.valueOf(liveCorrelation).isBlank()
                        ? located.instance().correlationId()
                        : String.valueOf(liveCorrelation);
                EventEnvelope envelope = publisher.publish(located.decl().step().getAwaitEventName(), payload,
                        correlationId, executionId, ctx);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("outcome", "COMPLETED");
                result.put("eventId", envelope == null ? null : envelope.eventId());
                result.put("executionStatus", instances.findByExecutionId(executionId)
                        .map(i -> i.status().name()).orElse("UNKNOWN"));
                return result;
            } finally {
                completionLocks.remove(executionId, lock);
            }
        }
    }

    // ------------------------------------------------------------------------------ internals

    private Located locate(String executionId, ExecutionContext ctx) {
        FlowInstance instance = instances.findByExecutionId(executionId)
                .orElseThrow(() -> new TaskException("TASK_NOT_FOUND", "No such task."));
        if (!sameTenant(ctx, instance)) {
            throw new TaskException("TASK_NOT_FOUND", "No such task.");
        }
        if (instance.status() != FlowInstanceStatus.WAITING_EVENT) {
            throw new TaskException("TASK_NOT_OPEN", "This task was already answered or moved on.");
        }
        Declared decl = declaredFor(instance, declaredTasks())
                .orElseThrow(() -> new TaskException("TASK_NOT_OPEN", "This execution is not waiting on a human task."));
        if (!isAssignee(ctx, instance, decl.task())) {
            throw new TaskException("TASK_FORBIDDEN", "You are not an assignee of this task.");
        }
        return new Located(instance, decl);
    }

    /** Walks every flow's step tree collecting awaits that declare a task, keyed by flow|step. */
    Map<String, Declared> declaredTasks() {
        Map<String, Declared> out = new LinkedHashMap<>();
        CompiledModel compiled = model.get();
        if (compiled == null) {
            return out;
        }
        for (CompiledFlow flow : compiled.getFlows()) {
            collect(flow.getName(), flow.getSteps(), out);
        }
        return out;
    }

    private static void collect(String flowName, List<CompiledFlowStep> steps, Map<String, Declared> out) {
        for (CompiledFlowStep step : steps) {
            if (step.getTask() != null) {
                out.put(key(flowName, step.getName()), new Declared(flowName, step, step.getTask()));
            }
            collect(flowName, step.getThenSteps(), out);
            collect(flowName, step.getElseSteps(), out);
            collect(flowName, step.getLoopSteps(), out);
            collect(flowName, step.getOnFailureSteps(), out);
            collect(flowName, step.getOnTimeoutSteps(), out);
        }
    }

    private static List<String> awaitedEventNames(Map<String, Declared> declared) {
        return declared.values().stream().map(d -> d.step().getAwaitEventName())
                .filter(Objects::nonNull).distinct().toList();
    }

    private static Optional<Declared> declaredFor(FlowInstance instance, Map<String, Declared> declared) {
        if (!(instance.state().get(AWAIT_STATE_KEY) instanceof Map<?, ?> await)) {
            // parallelAwait forEach parks under _npdev.parallelAwait.*, not here -- never a task (PROC-1 forbids it)
            return Optional.empty();
        }
        return Optional.ofNullable(declared.get(key(instance.flowName(), Objects.toString(await.get(AWAIT_STEP_NAME), ""))));
    }

    static boolean isAssignee(ExecutionContext ctx, FlowInstance instance, CompiledHumanTask task) {
        for (String role : task.getAssigneeRoles()) {
            if (ctx.hasRole(role)) {
                return true;
            }
        }
        if (task.getAssigneeUser() != null) {
            Object user = StateRefs.resolve(instance.state(), task.getAssigneeUser());
            return user != null && String.valueOf(user).equalsIgnoreCase(ctx.actorId());
        }
        return false;
    }

    /** Same semantics as KernelRunner.sameTenant: a blank side is a wildcard. */
    static boolean sameTenant(ExecutionContext ctx, FlowInstance instance) {
        String a = ctx.tenantId();
        String b = instance.tenantId();
        return a == null || a.isBlank() || b == null || b.isBlank() || a.equals(b);
    }

    private Map<String, Object> summary(FlowInstance instance, Declared decl) {
        Map<String, Object> state = instance.state();
        Map<String, Object> vars = StateRefs.flatten(state);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("executionId", instance.executionId());
        m.put("flowName", instance.flowName());
        m.put("stepName", decl.step().getName());
        m.put("title", MailTemplateRenderer.render(decl.task().getTitle(), vars));
        m.put("description", decl.task().getDescription() == null ? ""
                : MailTemplateRenderer.render(decl.task().getDescription(), vars));
        m.put("startedBy", instance.actorId() == null ? "" : instance.actorId());
        Long since = instance.lastProgressAtEpochMs() != null ? instance.lastProgressAtEpochMs() : instance.updatedAtEpochMs();
        m.put("waitingSinceEpochMs", since);
        Object deadline = state.get(AWAIT_STATE_KEY) instanceof Map<?, ?> await ? await.get(AWAIT_DEADLINE) : null;
        Long dueAt = deadline == null ? null : Long.valueOf(String.valueOf(deadline));
        m.put("dueAtEpochMs", dueAt);
        m.put("overdue", dueAt != null && System.currentTimeMillis() > dueAt);
        if (decl.task().getRecord() != null && StateRefs.resolve(state, decl.task().getRecord()) instanceof Map<?, ?> rec) {
            m.put("recordId", rec.get("id"));
        }
        return m;
    }

    private static String key(String flowName, String stepName) {
        return (flowName + "|" + stepName).toLowerCase(Locale.ROOT);
    }
}
