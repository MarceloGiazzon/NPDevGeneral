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
 *
 * <p>B15(A) (Move 16, docs/BOUNDARY_LIFT_ROADMAP.md): the {@link
 * FlowStateCodec#FOR_EACH_AWAIT_SATISFIED_KEY_PREFIX} check at the top of {@link #execute} makes a
 * forEach-nested await's re-entry safe after a crash between "event consumed" and "outer loop
 * iteration progress advanced" (see {@link ForEachStep}'s own javadoc for the full race). The
 * marker is set here the instant an event is consumed but deliberately NOT cleared here -- only
 * {@link ForEachStep} clears it, alongside its own progress advance, so repeated crashes in that
 * exact window keep re-finding the marker rather than re-querying an event the idempotency store
 * has already marked processed.
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

        String satisfiedKey = FlowStateCodec.FOR_EACH_AWAIT_SATISFIED_KEY_PREFIX + step.getName();
        if (Boolean.TRUE.equals(state.get(satisfiedKey))) {
            // B15(A): this exact await already resolved in a prior attempt that crashed before
            // ForEachStep's own progress advance could persist -- state.awaitRef/last/lastEvent
            // etc are already durably set from that attempt (see this class's own javadoc), so
            // skip straight past a re-query that would find the satisfying event already marked
            // processed by the idempotency store and park the flow WAITING forever.
            stepInfo.put("awaitedEventName", step.getAwaitEventName());
            stepInfo.put("awaitedEventStatus", "ALREADY_SATISFIED_ON_REENTRY");
            return null;
        }

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
        state.put(satisfiedKey, Boolean.TRUE);
        return null;
    }
}
