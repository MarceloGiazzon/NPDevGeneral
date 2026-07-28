package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T2.B.5 (pure mechanical extraction, see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.5): the
 * {@code AWAIT_EVENT} step-kind case body, split out of {@link KernelRunner#executeSteps}'s switch
 * verbatim -- no behavior change. This is the step kind that parks a flow instance durably (via
 * {@link FlowStateCodec#AWAIT_STATE_KEY}) for the durable-resume rehearsal: a process restart
 * between the {@code WAITING} branch below and a later {@link ResumeCoordinator} match must
 * rehydrate this exact checkpoint. See {@link InvariantCheckStep} for the shared {@code execute}
 * return convention. Stays a flat sibling of {@link KernelRunner} in {@code com.npdev.kernel}, not
 * a subpackage, for the same reason the rest of this split's files do: the collaborators
 * (awaitEvent, traceFailedStep) are package-private.
 */
final class AwaitEventStep {

    private AwaitEventStep() {
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
        ExecutionContext effectiveContext = req.effectiveContext();
        int traceStepIndex = req.traceStepIndex();
        long stepStartedAt = req.stepStartedAt();
        Map<String, Object> stateBefore = req.stateBefore();
        Map<String, Object> stepInfo = req.stepInfo();

        EventEnvelope awaited = runner.awaitEvent(
                step,
                state,
                defaultCorrelationId,
                input,
                effectiveContext.tenantId(),
                executionId
        );
        stepInfo.put("awaitedEventName", step.getAwaitEventName());
        if (awaited == null) {
            String waitingCorrelationId = Objects.toString(state.get("correlationId"), defaultCorrelationId);
            String awaitRef = KernelRunner.normalizeRef(step.getAwaitRef());
            if (awaitRef.isBlank()) {
                awaitRef = "awaitedEvent";
            }
            state.put(
                    FlowStateCodec.AWAIT_STATE_KEY,
                    FlowStateCodec.buildAwaitState(
                            step,
                            traceStepIndex,
                            awaitRef
                    )
            );
            stepInfo.put("awaitedEventStatus", "WAITING");
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
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.waitingEvent(
                    flow.getName(),
                    emittedEvents,
                    step.getName(),
                    traceStepIndex,
                    step.getAwaitEventName(),
                    waitingCorrelationId,
                    "Awaited event not found for step: " + step.getName()
                            + " eventName=" + step.getAwaitEventName()
                            + " correlationId=" + waitingCorrelationId
                            + " matchCorrelation=" + step.isAwaitMatchCorrelation()
                            + " payloadMatchRefs=" + step.getAwaitPayloadMatchRefs(),
                    executionId,
                    waitingCorrelationId,
                    executionId
            ));
        }
        stepInfo.put("awaitedEventFoundEventId", awaited.eventId());

        String awaitRef = KernelRunner.normalizeRef(step.getAwaitRef());
        if (awaitRef.isBlank()) {
            awaitRef = "awaitedEvent";
        }
        state.remove(FlowStateCodec.AWAIT_STATE_KEY);
        state.put(awaitRef, awaited.payload());
        state.put(awaitRef + "Envelope", awaited);
        state.put("last", awaited.payload());
        state.put("lastEvent", awaited);
        state.put("causationId", awaited.eventId());
        return null;
    }
}
