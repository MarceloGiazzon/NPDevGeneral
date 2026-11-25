package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.DeferredEventScheduler;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventSchemaProvider;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.JsonCodec;
import com.npdev.kernel.ports.SchemaValidator;
import com.npdev.kernel.schema.SchemaObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failure and dedupe paths of the {@code scheduleEvent} step. {@code KernelRunnerTest} already pins the
 * happy delayed path, the zero-delay path, the missing scheduler and the scheduler that answers false; these
 * are the rest of {@link ScheduleEventStep}: the author-chosen {@code dedupeKey} (literal, reference, and a key
 * that resolves to nothing), a missing event store, a scheduler or a store that throws (with and without a
 * message), and a payload the event schema refuses. Each failing case must end as EVENT_PERSIST_FAILED (or
 * EVENT_PAYLOAD_INVALID) and must publish nothing -- a schedule that quietly degrades into an immediate
 * publish, or into silence, is the defect R2.4 removed.
 */
class KernelRunnerScheduleEventFailurePathsTest {

    private static final InvariantEngine NO_INVARIANTS = (entityName, payload) -> List.of();
    private static final CapabilityDispatcher NO_CAPABILITIES = (call, state) -> CapabilityResult.success(Map.of());

    @Test
    void aLiteralDedupeKeyIsStampedOnTheEnvelopeOfAnImmediateSchedule() {
        InMemoryEventStore store = new InMemoryEventStore();
        KernelRunner runner = runner(store, store, flows(schedule(0L).withDedupeKey("  reminder-7  ")), null);

        ExecutionResult result = runner.execute("ScheduleReminder", Map.of("appointmentId", "apt-1", "correlationId", "corr-lit"));

        assertEquals(ExecutionStatus.OK, result.getStatus());
        EventEnvelope envelope = store.readByCorrelation("corr-lit").get(0);
        assertEquals("reminder-7", meta(envelope).get(ScheduleEventStep.DEDUPE_KEY_META),
                "a literal key is trimmed and carried in _meta so a delayed duplicate can be recognised");
    }

    @Test
    void aReferenceDedupeKeyIsResolvedFromTheInputBeforeTheEnvelopeIsHandedToTheScheduler() {
        InMemoryEventStore store = new InMemoryEventStore();
        RecordingScheduler scheduler = new RecordingScheduler(true, null);
        KernelRunner runner = runner(store, store, flows(schedule(300L).withDedupeKey("$input.orderId")), scheduler);

        ExecutionResult result = runner.execute("ScheduleReminder",
                Map.of("appointmentId", "apt-1", "orderId", "order-42", "correlationId", "corr-ref"));

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals(1, scheduler.envelopes.size());
        assertEquals("order-42", meta(scheduler.envelopes.get(0)).get(ScheduleEventStep.DEDUPE_KEY_META));
        assertTrue(store.readByCorrelation("corr-ref").isEmpty(), "a delayed event is not in the store yet");
    }

    @Test
    void aDedupeKeyThatResolvesToNothingFailsTheStepInsteadOfSchedulingWithoutIdentity() {
        InMemoryEventStore store = new InMemoryEventStore();
        RecordingScheduler scheduler = new RecordingScheduler(true, null);
        KernelRunner runner = runner(store, store, flows(schedule(300L).withDedupeKey("$input.missing")), scheduler);

        ExecutionResult result = runner.execute("ScheduleReminder", Map.of("appointmentId", "apt-1", "correlationId", "corr-none"));

        assertEquals(ExecutionStatus.EVENT_PERSIST_FAILED, result.getStatus());
        assertNotNull(result.getError());
        assertTrue(result.getError().contains("resolved to nothing") && result.getError().contains("$input.missing"),
                "the failure must name the key expression, got: " + result.getError());
        assertTrue(scheduler.envelopes.isEmpty(), "falling back to a per-execution schedule would silently double-schedule");
    }

    @Test
    void aDedupeKeyThatResolvesToBlankTextFailsTheStepToo() {
        InMemoryEventStore store = new InMemoryEventStore();
        KernelRunner runner = runner(store, store, flows(schedule(0L).withDedupeKey("$input.orderId")), null);

        ExecutionResult result = runner.execute("ScheduleReminder",
                Map.of("appointmentId", "apt-1", "orderId", "   ", "correlationId", "corr-blank"));

        assertEquals(ExecutionStatus.EVENT_PERSIST_FAILED, result.getStatus());
        assertTrue(store.readByCorrelation("corr-blank").isEmpty());
    }

    @Test
    void anImmediateScheduleFailsWhenNoEventStoreIsConfigured() {
        List<EventEnvelope> published = new ArrayList<>();
        EventBus busThatIsNotAStore = published::add;
        KernelRunner runner = new KernelRunner(busThatIsNotAStore, NO_INVARIANTS, flows(schedule(0L)), NO_CAPABILITIES);

        ExecutionResult result = runner.execute("ScheduleReminder", Map.of("appointmentId", "apt-1", "correlationId", "corr-nostore"));

        assertEquals(ExecutionStatus.EVENT_PERSIST_FAILED, result.getStatus());
        assertNotNull(result.getError());
        assertTrue(result.getError().contains("EventStore is required"), "got: " + result.getError());
        assertTrue(published.isEmpty());
    }

    @Test
    void aSchedulerThatThrowsFailsTheStepWithItsOwnMessage() {
        InMemoryEventStore store = new InMemoryEventStore();
        KernelRunner runner = runner(store, store, flows(schedule(300L)),
                new RecordingScheduler(true, new IllegalStateException("schedule table is down")));

        ExecutionResult result = runner.execute("ScheduleReminder", Map.of("appointmentId", "apt-1", "correlationId", "corr-throw"));

        assertEquals(ExecutionStatus.EVENT_PERSIST_FAILED, result.getStatus());
        assertTrue(result.getError().contains("schedule table is down"), "got: " + result.getError());
        assertTrue(store.readByCorrelation("corr-throw").isEmpty());
    }

    @Test
    void aSchedulerThatThrowsWithoutAMessageGetsAGenericOne() {
        InMemoryEventStore store = new InMemoryEventStore();
        KernelRunner runner = runner(store, store, flows(schedule(300L)),
                new RecordingScheduler(true, new IllegalStateException((String) null)));

        ExecutionResult result = runner.execute("ScheduleReminder", Map.of("appointmentId", "apt-1", "correlationId", "corr-throw2"));

        assertEquals(ExecutionStatus.EVENT_PERSIST_FAILED, result.getStatus());
        assertTrue(result.getError().contains("Scheduled event persist failed"), "got: " + result.getError());
    }

    @Test
    void anEventStoreThatRefusesTheAppendFailsAnImmediateScheduleAndPublishesNothing() {
        List<EventEnvelope> published = new ArrayList<>();
        EventBus bus = published::add;
        KernelRunner runner = runner(bus, new ThrowingStore(new IllegalStateException("disk full")), flows(schedule(0L)), null);

        ExecutionResult result = runner.execute("ScheduleReminder", Map.of("appointmentId", "apt-1", "correlationId", "corr-append"));

        assertEquals(ExecutionStatus.EVENT_PERSIST_FAILED, result.getStatus());
        assertTrue(result.getError().contains("disk full"), "got: " + result.getError());
        assertTrue(published.isEmpty(), "an event that could not be stored must not be published");
    }

    @Test
    void anEventStoreThatRefusesTheAppendWithoutAMessageGetsAGenericOne() {
        EventBus bus = envelope -> {
        };
        KernelRunner runner = runner(bus, new ThrowingStore(new IllegalStateException((String) null)), flows(schedule(0L)), null);

        ExecutionResult result = runner.execute("ScheduleReminder", Map.of("appointmentId", "apt-1", "correlationId", "corr-append2"));

        assertEquals(ExecutionStatus.EVENT_PERSIST_FAILED, result.getStatus());
        assertTrue(result.getError().contains("Scheduled event append failed"), "got: " + result.getError());
    }

    @Test
    void aPayloadTheEventSchemaRefusesIsRejectedBeforeAnythingIsScheduled() {
        InMemoryEventStore store = new InMemoryEventStore();
        RecordingScheduler scheduler = new RecordingScheduler(true, null);
        SchemaObject requiresReminderId = new SchemaObject(
                "object",
                Map.of("reminderId", new SchemaObject("string", Map.of(), List.of(), null, null, null, null, null, null)),
                List.of("reminderId"),
                null, null, null, null, null, null
        );
        EventSchemaProvider schemas = eventName ->
                "AppointmentReminderDue".equals(eventName) ? Optional.of(requiresReminderId) : Optional.empty();
        SchemaValidator validator = (schema, payload) ->
                payload instanceof Map<?, ?> map && map.containsKey("reminderId")
                        ? List.of()
                        : List.of(new InputValidationError("$.reminderId", "required_missing", "Required field is missing"));

        KernelRunner runner = new KernelRunner(
                store, NO_INVARIANTS, flows(schedule(300L)), NO_CAPABILITIES, ExecutionTracer.NOOP, store,
                FlowInstanceStore.noop(), CorrelationOwnershipStore.noop(), CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(), IdempotencyStore.noop(), JsonCodec.noop(), validator
        ).withEventSchemaProvider(schemas).withDeferredEventScheduler(scheduler);

        ExecutionResult result = runner.execute("ScheduleReminder", Map.of("appointmentId", "apt-1", "correlationId", "corr-schema"));

        assertEquals(ExecutionStatus.EVENT_PAYLOAD_INVALID, result.getStatus());
        assertNotNull(result.getFailureInfo());
        assertEquals("event_payload_invalid", result.getFailureInfo().code());
        assertTrue(scheduler.envelopes.isEmpty(), "an invalid event must never reach the durable scheduler");
        // The flow may record other events on a failure; what must not exist is the scheduled event itself.
        assertTrue(store.readByCorrelation("corr-schema").stream()
                        .noneMatch(event -> "AppointmentReminderDue".equals(event.eventName())),
                "the refused event must not be stored, got: " + store.readByCorrelation("corr-schema").stream()
                        .map(EventEnvelope::eventName).toList());
    }

    // ----------------------------------------------------------------------------------------------------------

    private static FlowStepDefinition schedule(long delaySeconds) {
        return FlowStepDefinition.scheduleEvent("queue-reminder", "AppointmentReminderDue", "$input", Map.of(), delaySeconds);
    }

    private static InMemoryFlowDefinitionProvider flows(FlowStepDefinition scheduleStep) {
        return new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "ScheduleReminder",
                "Appointment",
                List.of(scheduleStep, FlowStepDefinition.returnValue("return-input", "$input"))
        ));
    }

    private static KernelRunner runner(EventBus bus, EventStore store, InMemoryFlowDefinitionProvider flows, DeferredEventScheduler scheduler) {
        KernelRunner runner = new KernelRunner(
                bus, NO_INVARIANTS, flows, NO_CAPABILITIES, ExecutionTracer.NOOP, store,
                FlowInstanceStore.noop(), CorrelationOwnershipStore.noop()
        );
        return scheduler == null ? runner : runner.withDeferredEventScheduler(scheduler);
    }

    private static Map<?, ?> meta(EventEnvelope envelope) {
        return (Map<?, ?>) envelope.payload().get("_meta");
    }

    private static final class RecordingScheduler implements DeferredEventScheduler {
        private final List<EventEnvelope> envelopes = new ArrayList<>();
        private final boolean persisted;
        private final RuntimeException toThrow;

        private RecordingScheduler(boolean persisted, RuntimeException toThrow) {
            this.persisted = persisted;
            this.toThrow = toThrow;
        }

        @Override
        public boolean scheduleForLaterDelivery(EventEnvelope envelope, long dueAtEpochMs) {
            if (toThrow != null) {
                throw toThrow;
            }
            envelopes.add(envelope);
            return persisted;
        }
    }

    private static final class ThrowingStore implements EventStore {
        private final RuntimeException toThrow;

        private ThrowingStore(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public void append(EventEnvelope event) {
            throw toThrow;
        }

        @Override
        public List<EventEnvelope> readByCorrelation(String correlationId) {
            return List.of();
        }

        @Override
        public List<EventEnvelope> readByEventName(String eventName) {
            return List.of();
        }
    }
}
