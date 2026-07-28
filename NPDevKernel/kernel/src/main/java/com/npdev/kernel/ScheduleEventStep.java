package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T2.B.5 (pure mechanical extraction, see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.5): the
 * {@code SCHEDULE_EVENT} step-kind case body, split out of {@link KernelRunner#executeSteps}'s
 * switch verbatim -- no behavior change. See {@link InvariantCheckStep} for the shared
 * {@code execute} return convention. Deliberately near-identical to {@link EmitEventStep} (the
 * pre-existing duplication between the two step kinds is preserved as-is, not deduplicated, per
 * this split's pure-move discipline). Stays a flat sibling of {@link KernelRunner} in {@code
 * com.npdev.kernel}, not a subpackage, for the same reason the rest of this split's files do: the
 * collaborators (eventSchemaProvider, schemaValidator, eventStore, eventBus, nextId,
 * traceFailedStep) are package-private.
 */
final class ScheduleEventStep {

    private ScheduleEventStep() {
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
        ExecutionContext effectiveContext = req.effectiveContext();

        Object eventPayload = KernelRunner.buildEventPayload(step, state, input);
        if (step.getEventName() != null) {
            java.util.Optional<com.npdev.kernel.schema.SchemaObject> eventSchemaOpt = runner.eventSchemaProvider.findEventPayloadSchema(step.getEventName());
            if (eventSchemaOpt.isPresent()) {
                List<InputValidationError> errors = runner.schemaValidator.validate(eventSchemaOpt.get(), eventPayload);
                if (errors != null && !errors.isEmpty()) {
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
                    String currentCorrelationId = Objects.toString(state.get("correlationId"), defaultCorrelationId);
                    return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.eventPayloadInvalid(
                            flow.getName(),
                            emittedEvents,
                            step.getName(),
                            traceStepIndex,
                            step.getEventName(),
                            errors,
                            executionId,
                            currentCorrelationId,
                            executionId
                    ));
                }
            }
        }
        Map<String, Object> envelopePayload = KernelRunner.toEventPayloadMap(eventPayload);
        String currentCorrelationId = Objects.toString(state.get("correlationId"), defaultCorrelationId);
        String currentCausationId = executionId;
        long safeDelaySeconds = step.getDelaySeconds() == null ? 0L : Math.max(0L, step.getDelaySeconds());
        long scheduledForEpochMs = KernelRunner.nowEpochMillis() + (safeDelaySeconds * 1000L);
        EventEnvelope envelope = KernelRunner.newEnvelope(
                runner.nextId("event"),
                Objects.requireNonNull(step.getEventName(), "eventName is required"),
                currentCorrelationId,
                currentCausationId,
                envelopePayload,
                Map.of(
                        "flow", flow.getName(),
                        "step", step.getName(),
                        "stepIndex", traceStepIndex,
                        "deliveryMode", "scheduled",
                        "delaySeconds", safeDelaySeconds,
                        "scheduledForEpochMs", scheduledForEpochMs
                ),
                flow.getName(),
                traceStepIndex,
                effectiveContext.tenantId(),
                effectiveContext.actorId()
        );
        if (runner.eventStore == null) {
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
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.eventPersistFailed(
                    flow.getName(),
                    emittedEvents,
                    step.getName(),
                    traceStepIndex,
                    step.getEventName(),
                    "EventStore is required for scheduleEvent but was not configured",
                    executionId,
                    currentCorrelationId,
                    executionId
            ));
        }
        try {
            runner.eventStore.append(envelope);
        } catch (RuntimeException runtimeException) {
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
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.eventPersistFailed(
                    flow.getName(),
                    emittedEvents,
                    step.getName(),
                    traceStepIndex,
                    step.getEventName(),
                    runtimeException.getMessage() == null
                            ? "Scheduled event append failed"
                            : runtimeException.getMessage(),
                    executionId,
                    currentCorrelationId,
                    executionId
            ));
        }
        runner.eventBus.publish(envelope);
        ResumeCoordinator.resumeWaitingExecutionsFor(runner, envelope, executionId, envelope.correlationId(), effectiveContext);
        emittedEvents.add(envelope);
        state.put("lastEvent", envelope);
        state.put("causationId", executionId);
        stepInfo.put("emittedEventName", envelope.eventName());
        stepInfo.put("emittedEventId", envelope.eventId());
        stepInfo.put("deliveryMode", "scheduled");
        stepInfo.put("delaySeconds", safeDelaySeconds);
        stepInfo.put("scheduledForEpochMs", scheduledForEpochMs);
        return null;
    }
}
