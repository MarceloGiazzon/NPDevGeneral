package com.npdev.kernel;

import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.List;
import java.util.Map;

/**
 * T2.B.5 (pure mechanical extraction, see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.5): the
 * {@code RETURN} step-kind case body, split out of {@link KernelRunner#executeSteps}'s switch
 * verbatim -- no behavior change. Unlike every other step kind, {@code RETURN} always terminates
 * the flow (there is no "continue" path), so the caller in {@code executeSteps} returns this
 * method's result directly rather than checking for {@code null} first. Stays a flat sibling of
 * {@link KernelRunner} in {@code com.npdev.kernel}, not a subpackage, for the same reason the rest
 * of this split's files do: {@code traceSuccessfulStep} is package-private.
 */
final class ReturnStep {

    private ReturnStep() {
    }

    static KernelRunner.StepExecutionOutcome execute(KernelRunner runner, StepExecutionRequest req) {
        FlowStepDefinition step = req.step();
        Object input = req.input();
        Map<String, Object> state = req.state();
        FlowTraceMeta traceMeta = req.traceMeta();
        List<StepTrace> stepTraces = req.stepTraces();
        KernelRunner.StepProgressRecorder progressRecorder = req.progressRecorder();
        int traceStepIndex = req.traceStepIndex();
        long stepStartedAt = req.stepStartedAt();
        Map<String, Object> stateBefore = req.stateBefore();
        Map<String, Object> stepInfo = req.stepInfo();

        Object returnValue = KernelRunner.resolveReference(step.getReturnRef(), state, input);
        state.put("last", returnValue);
        stepInfo.put("returnRef", step.getReturnRef());
        runner.traceSuccessfulStep(
                traceMeta,
                step,
                traceStepIndex,
                stepStartedAt,
                stateBefore,
                state,
                stepInfo,
                stepTraces
        );
        progressRecorder.onStepCompleted(traceStepIndex + 1, state);
        return KernelRunner.StepExecutionOutcome.returned(returnValue);
    }
}
