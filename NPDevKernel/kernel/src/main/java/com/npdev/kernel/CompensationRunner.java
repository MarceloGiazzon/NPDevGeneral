package com.npdev.kernel;

import com.npdev.kernel.errors.ErrorKind;
import com.npdev.kernel.errors.FailureInfo;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T2.B.5 (pure mechanical extraction, see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.5): the LNCH-17
 * saga-compensation semantics, split out of {@link KernelRunner} verbatim -- no behavior change.
 * {@link KernelRunner#executeFlowInstance} (the dispatcher, which stays on {@code KernelRunner})
 * calls straight into this sibling class's statics, passing itself as the {@code runner} argument
 * for the collaborators (execute-steps recursion, {@code flowInstanceStore}, the logger) this logic
 * needs. This file deliberately stays a flat sibling in {@code com.npdev.kernel}, not a subpackage,
 * for the same reason {@code FlowStateCodec}/2.B.4's {@code TableRenamePass} documented: the
 * collaborators here are package-private, and Java sub-packages get no special access to them.
 *
 * <p>LNCH-17: reserved flow-state keys used to durably checkpoint a compensation run in progress,
 * so a crash mid-compensation resumes (via the same RUNNING-status recovery path LIFT-LOOP-P2
 * already uses for forEach) and finishes running the remaining compensations exactly once, rather
 * than silently leaving some rolled back and some not. Flat strings/primitives only (no
 * {@code FailureInfo} object stored directly) since flow state must survive a JDBC-backed
 * {@code FlowInstanceStore}'s JSON round trip.
 */
final class CompensationRunner {

    private CompensationRunner() {
    }

    static final String COMPENSATION_ACTIVE_KEY = "__npdev_compensating__";
    static final String COMPENSATION_NEXT_INDEX_KEY = "__npdev_compensationNextIndex__";
    static final String COMPENSATION_TERMINAL_STATUS_KEY = "__npdev_compensationTerminalStatus__";
    static final String COMPENSATION_ERROR_KIND_KEY = "__npdev_compensationErrorKind__";
    static final String COMPENSATION_ERROR_CODE_KEY = "__npdev_compensationErrorCode__";
    static final String COMPENSATION_ERROR_MESSAGE_KEY = "__npdev_compensationErrorMessage__";
    /** PROC-8: how many {@code onFailure} blocks ran (best-effort: a block that threw still counts).
     * Deliberately NOT cleared by {@link #finalizeCompensatedFailure} -- it is the durable record
     * a settled instance keeps so the execution card can say COMPENSATED. Absent when none ran. */
    static final String COMPENSATED_COUNT_KEY = "__npdev_compensatedSteps__";
    /** RUN-3: the onFailure blocks of steps nested in a failed branch/forEach body, as a list of step-name
     * paths in the order they must run; present only while a failure with nested compensation is being
     * settled. */
    static final String NESTED_PLAN_KEY = "__npdev_nestedCompPlan__";
    /** RUN-3: how many entries of {@link #NESTED_PLAN_KEY} have already run (the durable pointer). */
    static final String NESTED_NEXT_KEY = "__npdev_nestedCompNext__";

    /** RUN-3: the top-level index of the step whose nested body failed, so top-level compensation
     * starts below it (the failing step is not "completed", same as a failing top-level step). */
    static final String NESTED_TOP_INDEX_KEY = "__npdev_nestedCompTopIndex__";

    /** Whether this flow declares compensation on any step, top-level or nested in a branch/forEach
     * body -- lets every failure path skip compensation entirely (zero extra durable writes, zero
     * behavior change) for the vast majority of flows that don't use this feature. */
    static boolean hasAnyOnFailure(FlowDefinition flow) {
        return anyOnFailureRecursive(flow.getSteps());
    }

    /** True when any step in {@code steps}, or in a then/else/forEach body below them, declares onFailure. */
    static boolean anyOnFailureRecursive(List<FlowStepDefinition> steps) {
        for (FlowStepDefinition step : steps) {
            if (!step.getOnFailureSteps().isEmpty()
                    || anyOnFailureRecursive(step.getThenSteps())
                    || anyOnFailureRecursive(step.getElseSteps())
                    || anyOnFailureRecursive(step.getLoopSteps())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a nested body's result is a genuine failure (a parked await is not). */
    static boolean isFailure(ExecutionResult result) {
        if (result == null || result.getStatus() == null) {
            return false;
        }
        return switch (result.getStatus()) {
            case OK, WAITING_EVENT -> false;
            default -> true;
        };
    }

    /**
     * RUN-3: a step in a nested body (the then/else of a branch, one iteration of a forEach) failed.
     * Plans the {@code onFailure} blocks of the steps of THAT body that had already completed, newest
     * first, and appends them to the durable plan in {@code state}. The top-level failure handler then
     * runs the plan (see {@link #runCompensations}) before it compensates the top-level steps, so
     * nested work is undone first and the failure still propagates to the parent level as before.
     * Inner bodies record before outer ones because the failure surfaces innermost first. For a
     * forEach, {@code completed} holds only the failing iteration's steps, so earlier iterations stay
     * done. A no-op for a flow without any onFailure, and while a compensation is already running (a
     * failure inside an onFailure body must not plan more compensation).
     *
     * <p>Scope: a nested step's onFailure runs when ITS enclosing body fails. A body that completed
     * normally is not revisited when a later top-level step fails; only that top-level step's own
     * onFailure (if any) covers it. Parallel forEach bodies are not supported.</p>
     */
    static void recordNestedFailure(
            FlowDefinition flow,
            FlowStepDefinition owner,
            Map<String, Object> state,
            List<FlowStepDefinition> completed
    ) {
        if (Boolean.TRUE.equals(state.get(COMPENSATION_ACTIVE_KEY)) || !hasAnyOnFailure(flow)) {
            return;
        }
        List<String> ownerPath = pathOf(flow.getSteps(), owner, new ArrayList<>());
        if (ownerPath == null) {
            return;
        }
        int topIndex = indexOfTopLevel(flow, ownerPath.get(0));
        if (topIndex >= 0) {
            state.put(NESTED_TOP_INDEX_KEY, topIndex);
        }
        List<Object> plan = state.get(NESTED_PLAN_KEY) instanceof List<?> existing
                ? new ArrayList<>(existing) : new ArrayList<>();
        for (int i = completed.size() - 1; i >= 0; i--) {
            FlowStepDefinition step = completed.get(i);
            if (step.getOnFailureSteps().isEmpty()) {
                continue;
            }
            List<String> path = pathOf(flow.getSteps(), step, new ArrayList<>());
            if (path != null) {
                plan.add(path);
            }
        }
        if (!plan.isEmpty()) {
            state.put(NESTED_PLAN_KEY, plan);
        }
    }

    /** Names from the top-level step down to {@code target}: {@code [top, region, child, ...]} with
     * region one of then/else/loop. Located by identity now and resolved by name later (a resume may
     * rebuild the definition), so names only need to be unique within their own body. */
    private static List<String> pathOf(List<FlowStepDefinition> steps, FlowStepDefinition target, List<String> prefix) {
        for (FlowStepDefinition step : steps) {
            List<String> here = new ArrayList<>(prefix);
            here.add(step.getName());
            if (step == target) {
                return here;
            }
            List<String> found = pathInRegion(here, "then", step.getThenSteps(), target);
            if (found == null) {
                found = pathInRegion(here, "else", step.getElseSteps(), target);
            }
            if (found == null) {
                found = pathInRegion(here, "loop", step.getLoopSteps(), target);
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<String> pathInRegion(
            List<String> here, String region, List<FlowStepDefinition> body, FlowStepDefinition target) {
        List<String> regionPrefix = new ArrayList<>(here);
        regionPrefix.add(region);
        return pathOf(body, target, regionPrefix);
    }

    private static int indexOfTopLevel(FlowDefinition flow, String name) {
        for (int i = 0; i < flow.getSteps().size(); i++) {
            if (Objects.equals(flow.getSteps().get(i).getName(), name)) {
                return i;
            }
        }
        return -1;
    }

    private static FlowStepDefinition resolvePath(FlowDefinition flow, List<?> path) {
        if (path.isEmpty()) {
            return null;
        }
        int topIndex = indexOfTopLevel(flow, String.valueOf(path.get(0)));
        if (topIndex < 0) {
            return null;
        }
        FlowStepDefinition current = flow.getSteps().get(topIndex);
        for (int i = 1; i + 1 < path.size() && current != null; i += 2) {
            List<FlowStepDefinition> body = switch (String.valueOf(path.get(i))) {
                case "then" -> current.getThenSteps();
                case "else" -> current.getElseSteps();
                case "loop" -> current.getLoopSteps();
                default -> List.of();
            };
            FlowStepDefinition next = null;
            for (FlowStepDefinition candidate : body) {
                if (Objects.equals(candidate.getName(), String.valueOf(path.get(i + 1)))) {
                    next = candidate;
                    break;
                }
            }
            current = next;
        }
        return current;
    }

    /** Stamps the flow state with what a compensation run needs to remember to finalize
     * correctly later, whether that happens in this same call or after a crash-and-resume. */
    static void beginCompensation(Map<String, Object> state, FlowInstanceStatus terminalStatus, FailureInfo failureInfo) {
        state.put(COMPENSATION_ACTIVE_KEY, Boolean.TRUE);
        state.put(COMPENSATION_TERMINAL_STATUS_KEY, terminalStatus.name());
        state.put(COMPENSATION_ERROR_KIND_KEY, failureInfo.kind().name());
        state.put(COMPENSATION_ERROR_CODE_KEY, failureInfo.code());
        state.put(COMPENSATION_ERROR_MESSAGE_KEY, failureInfo.message());
    }

    /**
     * Runs declared {@code onFailure} steps for every top-level step from {@code startIndex} down
     * to 0 that has them, in reverse completion order (the saga-compensation pattern the spec
     * doc recommends) -- best-effort: a compensation block that itself throws is logged and
     * skipped, not allowed to abort compensating earlier steps too. Durably checkpoints after
     * each top-level step's compensation (mirroring {@code StepProgressRecorder}'s per-step
     * checkpoint), so a crash here resumes from the next uncompensated step, not from scratch.
     */
    static void runCompensations(
            KernelRunner runner,
            FlowDefinition flow,
            Object input,
            Map<String, Object> state,
            int startIndex,
            FlowTraceMeta traceMeta,
            List<StepTrace> stepTraces,
            String executionId,
            String correlationId,
            FlowInstance[] currentInstance,
            ExecutionContext executionContext
    ) {
        runNestedPlan(runner, flow, input, state, traceMeta, stepTraces, executionId, correlationId, currentInstance, executionContext);
        // A nested failure starts top-level compensation below the step that contained it; the clamp
        // also keeps a flat step index (which grows with nested bodies) from pointing past the list.
        int effectiveStart = Math.min(startIndex, flow.getSteps().size() - 1);
        if (state.get(NESTED_TOP_INDEX_KEY) instanceof Number top) {
            effectiveStart = Math.min(effectiveStart, top.intValue() - 1);
        }
        state.remove(NESTED_TOP_INDEX_KEY);
        for (int index = effectiveStart; index >= 0; index--) {
            FlowStepDefinition step = flow.getSteps().get(index);
            if (!step.getOnFailureSteps().isEmpty()) {
                try {
                    runner.executeSteps(
                            flow,
                            step.getOnFailureSteps(),
                            input,
                            state,
                            new ArrayList<>(),
                            traceMeta,
                            stepTraces,
                            executionId,
                            correlationId,
                            0,
                            KernelRunner.NOOP_STEP_PROGRESS_RECORDER,
                            executionContext
                    );
                } catch (RuntimeException compensationException) {
                    KernelRunner.LOG.warning(String.format(
                            "Compensation for step '%s' in flow '%s' (execution %s) failed: %s",
                            step.getName(), flow.getName(), executionId, compensationException.getMessage()
                    ));
                }
                // Count BEFORE the per-step checkpoint below, so a crash-resume never double counts.
                Object previous = state.get(COMPENSATED_COUNT_KEY);
                state.put(COMPENSATED_COUNT_KEY, (previous instanceof Number number ? number.intValue() : 0) + 1);
            }
            state.put(COMPENSATION_NEXT_INDEX_KEY, index - 1);
            FlowInstance checkpoint = currentInstance[0].markRunning(currentInstance[0].currentStepIndex(), state, KernelRunner.nowEpochMillis());
            runner.flowInstanceStore.update(checkpoint);
            currentInstance[0] = checkpoint;
        }
        state.remove(COMPENSATION_ACTIVE_KEY);
        state.remove(COMPENSATION_NEXT_INDEX_KEY);
    }

    /**
     * RUN-3: runs the nested-compensation plan (see {@link #recordNestedFailure}) entry by entry,
     * counting each block and durably checkpointing the pointer after every one. Same
     * count-then-checkpoint order as the top-level loop, so a crash and resume (the LNCH-17
     * compensation-active path) continues from the next entry and runs each exactly once.
     */
    private static void runNestedPlan(
            KernelRunner runner,
            FlowDefinition flow,
            Object input,
            Map<String, Object> state,
            FlowTraceMeta traceMeta,
            List<StepTrace> stepTraces,
            String executionId,
            String correlationId,
            FlowInstance[] currentInstance,
            ExecutionContext executionContext
    ) {
        if (!(state.get(NESTED_PLAN_KEY) instanceof List<?> plan)) {
            return;
        }
        int next = state.get(NESTED_NEXT_KEY) instanceof Number number ? number.intValue() : 0;
        for (; next < plan.size(); next++) {
            FlowStepDefinition step = plan.get(next) instanceof List<?> path ? resolvePath(flow, path) : null;
            if (step != null && !step.getOnFailureSteps().isEmpty()) {
                try {
                    runner.executeSteps(
                            flow,
                            step.getOnFailureSteps(),
                            input,
                            state,
                            new ArrayList<>(),
                            traceMeta,
                            stepTraces,
                            executionId,
                            correlationId,
                            0,
                            KernelRunner.NOOP_STEP_PROGRESS_RECORDER,
                            executionContext
                    );
                } catch (RuntimeException compensationException) {
                    KernelRunner.LOG.warning(String.format(
                            "Nested compensation for step '%s' in flow '%s' (execution %s) failed: %s",
                            step.getName(), flow.getName(), executionId, compensationException.getMessage()
                    ));
                }
                Object previous = state.get(COMPENSATED_COUNT_KEY);
                state.put(COMPENSATED_COUNT_KEY, (previous instanceof Number n ? n.intValue() : 0) + 1);
            }
            state.put(NESTED_NEXT_KEY, next + 1);
            FlowInstance checkpoint = currentInstance[0].markRunning(currentInstance[0].currentStepIndex(), state, KernelRunner.nowEpochMillis());
            runner.flowInstanceStore.update(checkpoint);
            currentInstance[0] = checkpoint;
        }
        state.remove(NESTED_PLAN_KEY);
        state.remove(NESTED_NEXT_KEY);
    }

    /** Reads back what {@link #beginCompensation} stamped, clears it, and applies the terminal
     * status the original failure would have gotten had compensation not run at all. */
    static FlowInstance finalizeCompensatedFailure(FlowInstance instance, Map<String, Object> state, long nowEpochMs) {
        String terminalStatusName = String.valueOf(state.remove(COMPENSATION_TERMINAL_STATUS_KEY));
        ErrorKind kind = ErrorKind.valueOf(String.valueOf(state.remove(COMPENSATION_ERROR_KIND_KEY)));
        String code = String.valueOf(state.remove(COMPENSATION_ERROR_CODE_KEY));
        String message = String.valueOf(state.remove(COMPENSATION_ERROR_MESSAGE_KEY));
        FailureInfo failureInfo = FailureInfo.of(kind, code, message);
        return switch (FlowInstanceStatus.valueOf(terminalStatusName)) {
            case FAILED_PERMANENT -> instance.markFailedPermanent(state, nowEpochMs, failureInfo);
            case STUCK -> instance.markStuck(state, nowEpochMs, failureInfo);
            default -> instance.markFailed(state, nowEpochMs, failureInfo);
        };
    }
}
