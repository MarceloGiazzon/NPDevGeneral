package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.List;
import java.util.Map;

/**
 * T2.B.5 (pure mechanical extraction, see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.5): the
 * {@code BRANCH} step-kind case body, split out of {@link KernelRunner#executeSteps}'s switch
 * verbatim -- no behavior change. See {@link InvariantCheckStep} for the shared {@code execute}
 * return convention. Recurses back into {@link KernelRunner#executeSteps} for the taken branch's
 * nested steps, exactly as the pre-split code did. Stays a flat sibling of {@link KernelRunner} in
 * {@code com.npdev.kernel}, not a subpackage, for the same reason the rest of this split's files
 * do: the collaborators (executeSteps, traceFailedStep, traceSuccessfulStep) are package-private.
 */
final class BranchStep {

    private BranchStep() {
    }

    static KernelRunner.StepExecutionOutcome execute(KernelRunner runner, StepExecutionRequest req) {
        FlowDefinition flow = req.flow();
        FlowStepDefinition step = req.step();
        Object input = req.input();
        Map<String, Object> state = req.state();
        List<EventEnvelope> emittedEvents = req.emittedEvents();
        FlowTraceMeta traceMeta = req.traceMeta();
        List<StepTrace> stepTraces = req.stepTraces();
        String executionId = req.executionId();
        String defaultCorrelationId = req.defaultCorrelationId();
        int stepIndexOffset = req.stepIndexOffset();
        KernelRunner.StepProgressRecorder progressRecorder = req.progressRecorder();
        ExecutionContext effectiveContext = req.effectiveContext();
        int traceStepIndex = req.traceStepIndex();
        long stepStartedAt = req.stepStartedAt();
        Map<String, Object> stateBefore = req.stateBefore();
        Map<String, Object> stepInfo = req.stepInfo();

        boolean branchResult = KernelRunner.evaluateCondition(step.getCondition(), state, input);
        stepInfo.put("branchResult", branchResult ? "then" : "else");
        List<FlowStepDefinition> nestedSteps = branchResult ? step.getThenSteps() : step.getElseSteps();
        if (!nestedSteps.isEmpty()) {
            KernelRunner.StepExecutionOutcome nested = runner.executeSteps(
                    flow,
                    nestedSteps,
                    input,
                    state,
                    emittedEvents,
                    traceMeta,
                    stepTraces,
                    executionId,
                    defaultCorrelationId,
                    stepIndexOffset,
                    progressRecorder,
                    effectiveContext
            );
            if (nested.failedResult() != null) {
                ExecutionResult nestedFailure = nested.failedResult();
                runner.traceFailedStep(
                        traceMeta,
                        step,
                        traceStepIndex,
                        stepStartedAt,
                        stateBefore,
                        state,
                        stepInfo,
                        nestedFailure.getInvariantViolations(),
                        nestedFailure.getCapabilityError(),
                        stepTraces
                );
                return nested;
            }
            if (nested.returned()) {
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
                return nested;
            }
        }
        return null;
    }
}
