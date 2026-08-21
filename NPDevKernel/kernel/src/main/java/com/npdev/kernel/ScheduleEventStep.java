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
 * switch verbatim. See {@link InvariantCheckStep} for the shared {@code execute} return convention.
 * Stays a flat sibling of {@link KernelRunner} in {@code com.npdev.kernel}, not a subpackage, for
 * the same reason the rest of this split's files do: the collaborators (eventSchemaProvider,
 * schemaValidator, eventStore, eventBus, nextId, traceFailedStep) are package-private.
 *
 * <p><b>R2.4 -- the delay is now real.</b> Until R2.4 this class computed {@code scheduledForEpochMs}
 * into the envelope's metadata and then appended + published + resumed waiters immediately, exactly
 * like {@link EmitEventStep}. So {@code delayMinutes: 1440} fired NOW and merely labelled itself
 * {@code deliveryMode: scheduled} -- the canonical demo's 24-hour appointment reminder arrived the
 * instant the appointment was created. The durable substrate existed the whole time (the
 * {@code npdev_scheduled_event} table with its {@code due_at} column, and since R2.3 a timer that
 * drains it) but only the ORCHESTRATION-level {@code scheduleEvent} action ever wrote a row.
 *
 * <p>A non-zero delay now goes to {@link com.npdev.kernel.ports.DeferredEventScheduler} and is NOT
 * published here. <b>Zero delay is untouched</b> -- same append, same publish, same synchronous
 * waiter resume, same ordering. Routing zero-delay through the table "for consistency" would add a
 * scheduler tick of latency to every existing model that uses it, and every model that wants an
 * immediate event already has {@code emitEvent}; a {@code delaySeconds: 0} schedule is deliberate
 * back-compatible spelling, not an accident to be normalised away.
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
            return persistFailure(
                    runner,
                    req,
                    currentCorrelationId,
                    "EventStore is required for scheduleEvent but was not configured"
            );
        }

        if (safeDelaySeconds > 0L) {
            // R2.4: the deferred half. Nothing is appended, published, resumed or added to
            // emittedEvents here -- the event has not happened yet, and claiming otherwise is the
            // whole defect this branch removes. Appending to the event store would be just as
            // wrong as publishing: ResumeCoordinator's scheduled sweep looks for a satisfying
            // event IN THE STORE (findAwaitedEvent), so a stored-but-unpublished envelope would
            // wake an AWAIT_EVENT waiter on the very next sweep and defeat the delay anyway.
            if (runner.deferredEventScheduler == null) {
                return persistFailure(
                        runner,
                        req,
                        currentCorrelationId,
                        "DeferredEventScheduler is required for a scheduleEvent step with delaySeconds > 0"
                                + " but was not configured"
                );
            }
            boolean scheduled;
            try {
                scheduled = runner.deferredEventScheduler.scheduleForLaterDelivery(envelope, scheduledForEpochMs);
            } catch (RuntimeException runtimeException) {
                return persistFailure(
                        runner,
                        req,
                        currentCorrelationId,
                        runtimeException.getMessage() == null
                                ? "Scheduled event persist failed"
                                : runtimeException.getMessage()
                );
            }
            if (!scheduled) {
                return persistFailure(
                        runner,
                        req,
                        currentCorrelationId,
                        "Scheduled event could not be persisted for later delivery"
                );
            }
            stepInfo.put("scheduledEventName", envelope.eventName());
            stepInfo.put("scheduledEventId", envelope.eventId());
            stepInfo.put("deliveryMode", "scheduled");
            stepInfo.put("delaySeconds", safeDelaySeconds);
            stepInfo.put("scheduledForEpochMs", scheduledForEpochMs);
            return null;
        }

        try {
            runner.eventStore.append(envelope);
        } catch (RuntimeException runtimeException) {
            return persistFailure(
                    runner,
                    req,
                    currentCorrelationId,
                    runtimeException.getMessage() == null
                            ? "Scheduled event append failed"
                            : runtimeException.getMessage()
            );
        }
        runner.eventBus.publish(envelope);
        ResumeCoordinator.resumeWaitingExecutionsFor(runner, envelope, executionId, envelope.correlationId());
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

    /**
     * The step's one failure shape, factored out when R2.4 added a third and fourth site for it.
     * Every caller reports EVENT_PERSIST_FAILED with an empty capability-call list and a null
     * result -- the message is the only thing that varies, and it is what tells an operator whether
     * the missing collaborator was the event store or the deferred scheduler.
     */
    private static KernelRunner.StepExecutionOutcome persistFailure(
            KernelRunner runner,
            StepExecutionRequest req,
            String currentCorrelationId,
            String message
    ) {
        runner.traceFailedStep(
                req.traceMeta(),
                req.step(),
                req.traceStepIndex(),
                req.stepStartedAt(),
                req.stateBefore(),
                req.state(),
                req.stepInfo(),
                List.of(),
                null,
                req.stepTraces()
        );
        return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.eventPersistFailed(
                req.flow().getName(),
                req.emittedEvents(),
                req.step().getName(),
                req.traceStepIndex(),
                req.step().getEventName(),
                message,
                req.executionId(),
                currentCorrelationId,
                req.executionId()
        ));
    }
}
