package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FORM P3-T5b: the {@code CALL_FLOW} step-kind case body -- a flow synchronously starting another flow by name, the
 * only way to start a flow declared {@code expose: internal}. The callee is a full, separate execution (its own
 * instance, trace, checkpoints and permission check) run as the caller's context; its output is bound into this
 * step's output key and {@code last}. A callee that does not finish (waiting, failed, denied) fails the calling
 * step: a synchronous call has no way to park half of itself. Nesting is bounded so a flow that calls itself fails
 * with a named error instead of a stack overflow.
 */
final class CallFlowStep {

    /** Deepest callFlow nesting allowed on one thread. */
    static final int MAX_DEPTH = 16;

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[]{0});

    private CallFlowStep() {
    }

    @SuppressWarnings("unchecked")
    static KernelRunner.StepExecutionOutcome execute(KernelRunner runner, StepExecutionRequest req) {
        FlowStepDefinition step = req.step();
        Map<String, Object> state = req.state();
        String flowName = step.getProcedureName();
        req.stepInfo().put("flow", flowName);

        Object resolved = KernelRunner.resolveReference(step.getInputRef(), state, req.input());
        Map<String, Object> calleeInput;
        if (resolved == null) {
            calleeInput = Map.of();
        } else if (resolved instanceof Map<?, ?> map) {
            calleeInput = (Map<String, Object>) map;
        } else {
            return failed(runner, req, "CALL_FLOW_INPUT_INVALID: callFlow input must resolve to a map, got "
                    + resolved.getClass().getSimpleName());
        }

        int[] depth = DEPTH.get();
        if (depth[0] >= MAX_DEPTH) {
            return failed(runner, req, "CALL_FLOW_TOO_DEEP: callFlow nested more than " + MAX_DEPTH
                    + " levels deep (a flow calling itself?) at " + flowName);
        }
        depth[0]++;
        ExecutionResult result;
        try {
            result = runner.execute(flowName, calleeInput, req.effectiveContext());
        } finally {
            depth[0]--;
        }
        if (result.getStatus() != ExecutionStatus.OK) {
            String detail = result.getError() == null ? "" : ": " + result.getError();
            return failed(runner, req, "CALL_FLOW_FAILED: flow " + flowName + " ended " + result.getStatus() + detail);
        }
        Object output = FlowStepSupport.plainTree(result.getOutput());
        String outputRef = KernelRunner.normalizeRef(step.getOutputRef());
        if (!outputRef.isBlank()) {
            state.put(outputRef, output);
        }
        state.put("last", output);
        return null;
    }

    private static KernelRunner.StepExecutionOutcome failed(KernelRunner runner, StepExecutionRequest req, String message) {
        return FlowStepSupport.fail(runner, req.flow(), req.step(), req.emittedEvents(), req.traceMeta(),
                req.stepTraces(), req.executionId(), req.defaultCorrelationId(), req.traceStepIndex(),
                req.stepStartedAt(), req.stateBefore(), req.state(), req.stepInfo(), message);
    }
}
