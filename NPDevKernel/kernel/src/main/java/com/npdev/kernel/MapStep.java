package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T2.B.5 (pure mechanical extraction, see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.5): the
 * {@code MAP} step-kind case body, split out of {@link KernelRunner#executeSteps}'s switch
 * verbatim -- no behavior change. See {@link InvariantCheckStep} for the shared {@code execute}
 * return convention. Stays a flat sibling of {@link KernelRunner} in {@code com.npdev.kernel}, not
 * a subpackage, for the same reason the rest of this split's files do: {@code traceFailedStep} is
 * package-private.
 */
final class MapStep {

    private MapStep() {
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
        int traceStepIndex = req.traceStepIndex();
        long stepStartedAt = req.stepStartedAt();
        Map<String, Object> stateBefore = req.stateBefore();
        Map<String, Object> stepInfo = req.stepInfo();

        Object mappedValue = KernelRunner.resolveReference(step.getMapFromRef(), state, input);
        String mapToRef = KernelRunner.normalizeRef(step.getMapToRef());
        if (mapToRef.isBlank()) {
            runner.traceFailedStep(
                    traceMeta,
                    step,
                    traceStepIndex,
                    stepStartedAt,
                    stateBefore,
                    state,
                    stepInfo,
                    List.of(),
                    null,
                    stepTraces
            );
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.failed(
                    flow.getName(),
                    List.of(),
                    emittedEvents,
                    "Map step missing output target: " + step.getName(),
                    executionId,
                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                    executionId
            ));
        }
        state.put(mapToRef, mappedValue);
        state.put("last", mappedValue);
        stepInfo.put("mapFromRef", step.getMapFromRef());
        stepInfo.put("mapToRef", step.getMapToRef());
        return null;
    }
}
