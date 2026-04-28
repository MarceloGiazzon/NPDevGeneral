package com.npdev.kernel.mvp;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.FlowDefinition;
import com.npdev.kernel.FlowStepDefinition;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.CapabilityInvoker;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.FlowDefinitionProvider;
import com.npdev.kernel.ports.InvariantEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Phase-2 deterministic MVP runner:
 * sequential flow execution with deterministic trace and explicit failure typing.
 */
public final class MvpKernelRunner {

    private final EventBus eventBus;
    private final InvariantEngine invariantEngine;
    private final CapabilityInvoker capabilityInvoker;

    public MvpKernelRunner(EventBus eventBus, InvariantEngine invariantEngine, CapabilityInvoker capabilityInvoker) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.invariantEngine = Objects.requireNonNull(invariantEngine, "invariantEngine");
        this.capabilityInvoker = Objects.requireNonNull(capabilityInvoker, "capabilityInvoker");
    }

    public ExecutionTrace run(
            FlowDefinitionProvider flowDefinitionProvider,
            String flowName,
            Object input,
            ExecutionContext executionContext
    ) {
        Objects.requireNonNull(flowDefinitionProvider, "flowDefinitionProvider");
        String normalizedFlowName = requireNonBlank(flowName, "flowName");
        Optional<FlowDefinition> flowDefinition = flowDefinitionProvider.findFlow(normalizedFlowName);
        if (flowDefinition.isEmpty()) {
            return new ExecutionTrace(
                    normalizedFlowName,
                    List.of(),
                    List.of(),
                    List.of(),
                    ExecutionTraceStatus.FAILURE,
                    new ExecutionTraceFailure("FLOW_NOT_FOUND", "Flow not found: " + normalizedFlowName, null)
            );
        }
        return run(flowDefinition.get(), input, executionContext);
    }

    public ExecutionTrace run(
            FlowDefinition flowDefinition,
            Object input,
            ExecutionContext executionContext
    ) {
        Objects.requireNonNull(flowDefinition, "flowDefinition");
        ExecutionContext effectiveContext = executionContext == null ? ExecutionContext.anonymous() : executionContext;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("input", canonicalizeValue(input));

        String correlationId = resolveCorrelationId(flowDefinition.getName(), state.get("input"), effectiveContext);
        state.put("correlationId", correlationId);
        String executionId = buildExecutionId(flowDefinition.getName(), state.get("input"), effectiveContext);
        state.put("executionId", executionId);

        List<StepExecutionTrace> steps = new ArrayList<>();
        List<EventEnvelope> emittedEvents = new ArrayList<>();
        List<InvariantTrace> invariantChecks = new ArrayList<>();

        StepRunResult result = executeSteps(
                flowDefinition,
                flowDefinition.getSteps(),
                "",
                state,
                effectiveContext,
                correlationId,
                executionId,
                steps,
                emittedEvents,
                invariantChecks
        );

        ExecutionTraceStatus status = result.failure() == null
                ? ExecutionTraceStatus.SUCCESS
                : ExecutionTraceStatus.FAILURE;
        return new ExecutionTrace(
                flowDefinition.getName(),
                steps,
                emittedEvents,
                invariantChecks,
                status,
                result.failure()
        );
    }

    private StepRunResult executeSteps(
            FlowDefinition flowDefinition,
            List<FlowStepDefinition> steps,
            String stepPathPrefix,
            Map<String, Object> state,
            ExecutionContext executionContext,
            String correlationId,
            String executionId,
            List<StepExecutionTrace> traces,
            List<EventEnvelope> emittedEvents,
            List<InvariantTrace> invariantChecks
    ) {
        for (int index = 0; index < steps.size(); index++) {
            FlowStepDefinition step = steps.get(index);
            String path = stepPathPrefix.isBlank()
                    ? String.valueOf(index + 1)
                    : stepPathPrefix + "." + (index + 1);
            String stepId = stableStepId(path, step.getName());
            String stepType = step.getType().name().toLowerCase(Locale.ROOT);

            switch (step.getType()) {
                case INVARIANT_CHECK -> {
                    Object payload = state.get("input");
                    String inputSummary = summarize(payload);
                    InvariantEngine.InvariantEvaluationRequest request = new InvariantEngine.InvariantEvaluationRequest(
                            normalizeOrFallback(step.getInvariantScope(), flowDefinition.getEntityName()),
                            payload,
                            step.getInvariants(),
                            new InvariantEngine.EvaluationMetadata(
                                    flowDefinition.getName(),
                                    step.getName(),
                                    traces.size(),
                                    step.getCheckpoint() == null
                                            ? FlowStepDefinition.InvariantCheckpoint.PRE
                                            : step.getCheckpoint(),
                                    correlationId
                            ),
                            state
                    );
                    InvariantEngine.InvariantEvaluationResult evaluationResult = invariantEngine.evaluate(request);
                    Map<String, InvariantEngine.Violation> violationsByRef = new LinkedHashMap<>();
                    for (InvariantEngine.Violation violation : evaluationResult.violations()) {
                        if (violation == null || violation.invariantRef() == null || violation.invariantRef().isBlank()) {
                            continue;
                        }
                        violationsByRef.put(violation.invariantRef(), violation);
                    }

                    boolean invariantFailed = false;
                    for (String invariantRef : step.getInvariants()) {
                        InvariantEngine.Violation violation = violationsByRef.get(invariantRef);
                        if (violation == null) {
                            invariantChecks.add(new InvariantTrace(invariantRef, stepId, true, "OK"));
                            continue;
                        }
                        invariantFailed = true;
                        invariantChecks.add(new InvariantTrace(
                                invariantRef,
                                stepId,
                                false,
                                violation.message()
                        ));
                    }

                    if (invariantFailed) {
                        String message = firstViolationMessage(evaluationResult.violations(), "Invariant check failed");
                        traces.add(new StepExecutionTrace(
                                stepId,
                                stepType,
                                inputSummary,
                                summarize(message),
                                StepExecutionStatus.FAILURE
                        ));
                        return new StepRunResult(new ExecutionTraceFailure("INVARIANT_FAILED", message, stepId), false);
                    }

                    traces.add(new StepExecutionTrace(
                            stepId,
                            stepType,
                            inputSummary,
                            "\"OK\"",
                            StepExecutionStatus.SUCCESS
                    ));
                }
                case CAPABILITY_CALL -> {
                    if (step.getCapabilityAdapterId() == null || step.getCapabilityAdapterId().isBlank()) {
                        traces.add(new StepExecutionTrace(
                                stepId,
                                stepType,
                                summarize(resolveCapabilityInput(step, state)),
                                "\"missing adapter binding\"",
                                StepExecutionStatus.FAILURE
                        ));
                        return new StepRunResult(
                                new ExecutionTraceFailure(
                                        "CAPABILITY_BINDING_MISSING",
                                        "Capability binding missing for " + step.getCapability(),
                                        stepId
                                ),
                                false
                        );
                    }
                    Object capabilityInput = resolveCapabilityInput(step, state);
                    try {
                        Object output = capabilityInvoker.invoke(
                                step.getCapability(),
                                step.getCapabilityAdapterId(),
                                step.getOperation(),
                                capabilityInput,
                                executionContext,
                                state
                        );
                        if (step.getOutputRef() != null && !step.getOutputRef().isBlank()) {
                            writeRef(state, step.getOutputRef(), output);
                        }
                        traces.add(new StepExecutionTrace(
                                stepId,
                                stepType,
                                summarize(capabilityInput),
                                summarize(output),
                                StepExecutionStatus.SUCCESS
                        ));
                    } catch (RuntimeException runtimeException) {
                        String message = runtimeException.getMessage() == null
                                ? "Capability invocation failed"
                                : runtimeException.getMessage();
                        traces.add(new StepExecutionTrace(
                                stepId,
                                stepType,
                                summarize(capabilityInput),
                                summarize(message),
                                StepExecutionStatus.FAILURE
                        ));
                        return new StepRunResult(
                                new ExecutionTraceFailure("CAPABILITY_INVOCATION_FAILED", message, stepId),
                                false
                        );
                    }
                }
                case EMIT_EVENT -> {
                    Object payload = resolveEventPayload(step, state);
                    EventEnvelope envelope = new EventEnvelope(
                            stableEventId(flowDefinition.getName(), stepId, emittedEvents.size()),
                            step.getEventName(),
                            1_000_000L + emittedEvents.size(),
                            toEventPayloadMap(payload),
                            correlationId,
                            executionId,
                            flowDefinition.getName(),
                            emittedEvents.size(),
                            executionContext.tenantId(),
                            executionContext.actorId()
                    );
                    eventBus.publish(envelope);
                    emittedEvents.add(envelope);
                    traces.add(new StepExecutionTrace(
                            stepId,
                            stepType,
                            summarize(payload),
                            summarize(Map.of("eventId", envelope.eventId(), "eventName", envelope.eventName())),
                            StepExecutionStatus.SUCCESS
                    ));
                }
                case BRANCH -> {
                    boolean branchResult = evaluateCondition(step.getCondition(), state);
                    traces.add(new StepExecutionTrace(
                            stepId,
                            stepType,
                            summarize(step.getCondition()),
                            summarize(branchResult ? "then" : "else"),
                            StepExecutionStatus.SUCCESS
                    ));
                    List<FlowStepDefinition> branchSteps = branchResult ? step.getThenSteps() : step.getElseSteps();
                    StepRunResult nestedResult = executeSteps(
                            flowDefinition,
                            branchSteps,
                            stepPathPrefix.isBlank() ? (index + 1) + "b" : stepPathPrefix + "." + (index + 1) + "b",
                            state,
                            executionContext,
                            correlationId,
                            executionId,
                            traces,
                            emittedEvents,
                            invariantChecks
                    );
                    if (nestedResult.failure() != null || nestedResult.shouldStop()) {
                        return nestedResult;
                    }
                }
                case AWAIT_EVENT -> {
                    traces.add(new StepExecutionTrace(
                            stepId,
                            stepType,
                            summarize(step.getAwaitEventName()),
                            summarize("await-not-supported-in-mvp"),
                            StepExecutionStatus.FAILURE
                    ));
                    return new StepRunResult(
                            new ExecutionTraceFailure(
                                    "STEP_UNSUPPORTED",
                                    "Await step is not supported in phase-2 MVP runner",
                                    stepId
                            ),
                            false
                    );
                }
                case MAP -> {
                    Object mappedValue = resolveRef(step.getMapFromRef(), state);
                    writeRef(state, step.getMapToRef(), mappedValue);
                    traces.add(new StepExecutionTrace(
                            stepId,
                            stepType,
                            summarize(Map.of("from", step.getMapFromRef(), "value", mappedValue)),
                            summarize(Map.of("to", step.getMapToRef(), "value", mappedValue)),
                            StepExecutionStatus.SUCCESS
                    ));
                }
                case RETURN -> {
                    Object value = resolveRef(step.getReturnRef(), state);
                    state.put("return", value);
                    traces.add(new StepExecutionTrace(
                            stepId,
                            stepType,
                            summarize(step.getReturnRef()),
                            summarize(value),
                            StepExecutionStatus.SUCCESS
                    ));
                    return new StepRunResult(null, true);
                }
                default -> {
                    traces.add(new StepExecutionTrace(
                            stepId,
                            stepType,
                            "null",
                            summarize("unsupported step type"),
                            StepExecutionStatus.FAILURE
                    ));
                    return new StepRunResult(
                            new ExecutionTraceFailure(
                                    "STEP_UNSUPPORTED",
                                    "Unsupported step type: " + step.getType(),
                                    stepId
                            ),
                            false
                    );
                }
            }
        }
        return new StepRunResult(null, false);
    }

    private static String stableStepId(String path, String stepName) {
        return "step-" + path + "-" + sanitizeToken(stepName);
    }

    private static String stableEventId(String flowName, String stepId, int eventIndex) {
        return "evt-" + sanitizeToken(flowName) + "-" + sanitizeToken(stepId) + "-" + eventIndex;
    }

    private static String sanitizeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unnamed";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if ((current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9')
                    || current == '-') {
                out.append(current);
            } else {
                out.append('-');
            }
        }
        return out.toString();
    }

    private static String buildExecutionId(String flowName, Object input, ExecutionContext context) {
        String fingerprint = flowName + "|" + summarize(input) + "|" + context.tenantId() + "|" + context.actorId();
        int hash = Math.abs(fingerprint.hashCode());
        return "exec-" + hash;
    }

    private static String resolveCorrelationId(String flowName, Object input, ExecutionContext context) {
        String explicitContext = context.correlationId();
        if (explicitContext != null && !explicitContext.isBlank()) {
            return explicitContext;
        }
        if (input instanceof Map<?, ?> map) {
            Object direct = map.get("correlationId");
            if (direct instanceof String text && !text.isBlank()) {
                return text.trim();
            }
            Object dashed = map.get("correlation_id");
            if (dashed instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return "corr-" + sanitizeToken(flowName) + "-1";
    }

    private static Object resolveCapabilityInput(FlowStepDefinition step, Map<String, Object> state) {
        List<String> args = step.getArgsRefs();
        if (args == null || args.isEmpty()) {
            return state.get("input");
        }
        if (args.size() == 1) {
            return resolveRef(args.get(0), state);
        }
        List<Object> values = new ArrayList<>(args.size());
        for (String arg : args) {
            values.add(resolveRef(arg, state));
        }
        return values;
    }

    private static Object resolveEventPayload(FlowStepDefinition step, Map<String, Object> state) {
        if (step.getPayloadRef() != null && !step.getPayloadRef().isBlank()) {
            return resolveRef(step.getPayloadRef(), state);
        }
        if (!step.getEventDataRefs().isEmpty()) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : step.getEventDataRefs().entrySet()) {
                mapped.put(entry.getKey(), resolveRef(entry.getValue(), state));
            }
            return mapped;
        }
        return Map.of();
    }

    private static Map<String, Object> toEventPayloadMap(Object payload) {
        Object normalized = canonicalizeValue(payload);
        if (normalized instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return copy;
        }
        return Map.of("value", normalized);
    }

    private static boolean evaluateCondition(String condition, Map<String, Object> state) {
        if (condition == null || condition.isBlank()) {
            return false;
        }
        String trimmed = condition.trim();
        if (trimmed.contains("==")) {
            String[] parts = trimmed.split("==", 2);
            Object left = resolveToken(parts[0], state);
            Object right = resolveToken(parts[1], state);
            return Objects.equals(left, right);
        }
        if (trimmed.contains("!=")) {
            String[] parts = trimmed.split("!=", 2);
            Object left = resolveToken(parts[0], state);
            Object right = resolveToken(parts[1], state);
            return !Objects.equals(left, right);
        }

        Object resolved = resolveToken(trimmed, state);
        if (resolved instanceof Boolean bool) {
            return bool;
        }
        if (resolved instanceof String text) {
            return !text.isBlank() && !"false".equalsIgnoreCase(text);
        }
        return resolved != null;
    }

    private static Object resolveToken(String token, Map<String, Object> state) {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("$")) {
            return resolveRef(trimmed, state);
        }
        if ("true".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }
        if ("null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        try {
            if (trimmed.contains(".")) {
                return Double.parseDouble(trimmed);
            }
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            return trimmed;
        }
    }

    private static Object resolveRef(String ref, Map<String, Object> state) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String path = ref.trim();
        if (!path.startsWith("$")) {
            return path;
        }
        String normalized = path.substring(1);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return state;
        }

        String[] parts = normalized.split("\\.");
        Object current = state.get(parts[0]);
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index];
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
                continue;
            }
            if (current instanceof List<?> list) {
                int position;
                try {
                    position = Integer.parseInt(part);
                } catch (NumberFormatException ignored) {
                    return null;
                }
                if (position < 0 || position >= list.size()) {
                    return null;
                }
                current = list.get(position);
                continue;
            }
            return null;
        }
        return current;
    }

    private static void writeRef(Map<String, Object> state, String ref, Object value) {
        if (ref == null || ref.isBlank()) {
            return;
        }
        String path = ref.trim();
        if (path.startsWith("$")) {
            path = path.substring(1);
        }
        if (path.startsWith(".")) {
            path = path.substring(1);
        }
        if (path.isBlank()) {
            return;
        }
        String[] parts = path.split("\\.");
        if (parts.length == 1) {
            state.put(parts[0], canonicalizeValue(value));
            return;
        }

        Map<String, Object> current = state;
        for (int index = 0; index < parts.length - 1; index++) {
            String part = parts[index];
            Object existing = current.get(part);
            if (!(existing instanceof Map<?, ?> existingMap)) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(part, created);
                current = created;
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) existingMap;
            current = map;
        }
        current.put(parts[parts.length - 1], canonicalizeValue(value));
    }

    private static String summarize(Object value) {
        Object canonical = canonicalizeValue(value);
        if (canonical == null) {
            return "null";
        }
        if (canonical instanceof String text) {
            return "\"" + escape(text) + "\"";
        }
        if (canonical instanceof Number || canonical instanceof Boolean) {
            return String.valueOf(canonical);
        }
        if (canonical instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder();
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append("\"")
                        .append(escape(String.valueOf(entry.getKey())))
                        .append("\":")
                        .append(summarize(entry.getValue()));
            }
            out.append('}');
            return out.toString();
        }
        if (canonical instanceof List<?> list) {
            StringBuilder out = new StringBuilder();
            out.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    out.append(',');
                }
                out.append(summarize(list.get(index)));
            }
            out.append(']');
            return out.toString();
        }
        return "\"" + escape(String.valueOf(canonical)) + "\"";
    }

    private static Object canonicalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> ordered = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                ordered.put(String.valueOf(entry.getKey()), canonicalizeValue(entry.getValue()));
            }
            return new LinkedHashMap<>(ordered);
        }
        if (value instanceof List<?> list) {
            List<Object> canonical = new ArrayList<>(list.size());
            for (Object item : list) {
                canonical.add(canonicalizeValue(item));
            }
            return canonical;
        }
        if (value instanceof Set<?> set) {
            List<Object> canonical = new ArrayList<>(set.size());
            for (Object item : new LinkedHashSet<>(set)) {
                canonical.add(canonicalizeValue(item));
            }
            return canonical;
        }
        return value;
    }

    private static String escape(String value) {
        String escaped = value;
        escaped = escaped.replace("\\", "\\\\");
        escaped = escaped.replace("\"", "\\\"");
        escaped = escaped.replace("\n", "\\n");
        escaped = escaped.replace("\r", "\\r");
        escaped = escaped.replace("\t", "\\t");
        return escaped;
    }

    private static String firstViolationMessage(List<InvariantEngine.Violation> violations, String fallback) {
        if (violations == null || violations.isEmpty()) {
            return fallback;
        }
        for (InvariantEngine.Violation violation : violations) {
            if (violation != null && violation.message() != null && !violation.message().isBlank()) {
                return violation.message();
            }
        }
        return fallback;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value.trim();
    }

    private static String normalizeOrFallback(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private record StepRunResult(ExecutionTraceFailure failure, boolean shouldStop) {
    }
}
