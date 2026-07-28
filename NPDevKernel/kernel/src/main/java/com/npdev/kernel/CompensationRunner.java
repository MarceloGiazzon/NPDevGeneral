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

    /** Whether any top-level step in this flow declares compensation -- lets every failure path
     * skip compensation entirely (zero extra durable writes, zero behavior change) for the vast
     * majority of flows that don't use this feature. */
    static boolean hasAnyOnFailure(FlowDefinition flow) {
        for (FlowStepDefinition step : flow.getSteps()) {
            if (!step.getOnFailureSteps().isEmpty()) {
                return true;
            }
        }
        return false;
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
        for (int index = startIndex; index >= 0; index--) {
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
            }
            state.put(COMPENSATION_NEXT_INDEX_KEY, index - 1);
            FlowInstance checkpoint = currentInstance[0].markRunning(currentInstance[0].currentStepIndex(), state, KernelRunner.nowEpochMillis());
            runner.flowInstanceStore.update(checkpoint);
            currentInstance[0] = checkpoint;
        }
        state.remove(COMPENSATION_ACTIVE_KEY);
        state.remove(COMPENSATION_NEXT_INDEX_KEY);
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
