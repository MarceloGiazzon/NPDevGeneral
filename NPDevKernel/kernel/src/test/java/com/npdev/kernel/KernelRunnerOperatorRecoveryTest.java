package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROC-7: operator recovery. Retry re-enters a FAILED execution at the step that failed (earlier
 * steps are NOT re-run); cancel parks a WAITING_EVENT / STUCK one as FAILED_PERMANENT with
 * {@code CANCELLED_BY_OPERATOR} so an awaited event can no longer resume it.
 */
class KernelRunnerOperatorRecoveryTest {

    @Test
    void retryReEntersAtTheFailedStepAndNeverRerunsEarlierSteps() {
        List<String> calls = new CopyOnWriteArrayList<>();
        AtomicInteger shipAttempts = new AtomicInteger();
        CapabilityDispatcher dispatcher = (call, state) -> {
            calls.add(call.operation());
            if ("ship".equals(call.operation()) && shipAttempts.incrementAndGet() == 1) {
                return CapabilityResult.failure(new CapabilityError(
                        "gateway_down", "gateway unavailable", CapabilityErrorKind.TRANSIENT, Map.of()));
            }
            return CapabilityResult.success(call.input());
        };
        InMemoryStore store = new InMemoryStore();
        KernelRunner runner = runner(chargeThenShipFlow(false), dispatcher, store);

        ExecutionResult first = runner.execute("ChargeAndShip", Map.of("correlationId", "corr-retry-1"));
        FlowInstance failed = store.findByExecutionId(first.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.FAILED, failed.status(), "a transient capability failure is retryable FAILED");
        assertEquals(List.of("charge", "ship"), calls);

        ExecutionResult retried = runner.retryFailedExecution(first.getExecutionId(), ExecutionContext.anonymous())
                .orElseThrow();
        assertEquals(ExecutionStatus.OK, retried.getStatus());
        assertEquals(FlowInstanceStatus.COMPLETED, store.findByExecutionId(first.getExecutionId()).orElseThrow().status());
        assertEquals(List.of("charge", "ship", "ship"), calls, "step 1 must have run exactly once");
        assertEquals(null, store.findByExecutionId(first.getExecutionId()).orElseThrow().lastErrorCode(),
                "the failure quartet is cleared by the retry");
    }

    @Test
    void retryRefusesAnythingButFailed() {
        InMemoryStore store = new InMemoryStore();
        KernelRunner runner = runner(chargeThenShipFlow(false),
                (call, state) -> CapabilityResult.failure(new CapabilityError(
                        "bad_request", "contract mismatch", CapabilityErrorKind.CONTRACT, Map.of())),
                store);
        ExecutionResult result = runner.execute("ChargeAndShip", Map.of("correlationId", "corr-retry-2"));
        FlowInstance permanent = store.findByExecutionId(result.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.FAILED_PERMANENT, permanent.status());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> runner.retryFailedExecution(result.getExecutionId(), ExecutionContext.anonymous()));
        assertTrue(refused.getMessage().startsWith("NOT_RETRYABLE:"), refused.getMessage());
        assertEquals(Optional.empty(), runner.retryFailedExecution("no-such-execution", ExecutionContext.anonymous()));
    }

    @Test
    void retryRefusesAFlowThatDeclaresCompensation() {
        InMemoryStore store = new InMemoryStore();
        KernelRunner runner = runner(chargeThenShipFlow(true),
                (call, state) -> "ship".equals(call.operation())
                        ? CapabilityResult.failure(new CapabilityError(
                                "gateway_down", "gateway unavailable", CapabilityErrorKind.TRANSIENT, Map.of()))
                        : CapabilityResult.success(call.input()),
                store);
        ExecutionResult result = runner.execute("ChargeAndShip", Map.of("correlationId", "corr-retry-3"));
        // Seed the row as FAILED (compensation may settle a failure as FAILED_PERMANENT on its own):
        FlowInstance row = store.findByExecutionId(result.getExecutionId()).orElseThrow();
        store.update(row.markFailed(row.state(), 5000L));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> runner.retryFailedExecution(result.getExecutionId(), ExecutionContext.anonymous()));
        assertTrue(refused.getMessage().startsWith("NOT_RETRYABLE_COMPENSATED:"), refused.getMessage());
    }

    @Test
    void cancelParksAWaitingExecutionAndALateEventDoesNotResumeIt() {
        InMemoryStore store = new InMemoryStore();
        Events events = new Events();
        KernelRunner runner = new KernelRunner(events, (entity, payload) -> List.of(), awaitFlow(),
                (call, state) -> CapabilityResult.success(null), events, store);

        ExecutionResult waiting = runner.execute("AwaitApproval", Map.of("correlationId", "corr-cancel-1"));
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());

        FlowInstance cancelled = runner.cancelExecution(waiting.getExecutionId(), "super-user").orElseThrow();
        assertEquals(FlowInstanceStatus.FAILED_PERMANENT, cancelled.status());
        assertEquals("CANCELLED_BY_OPERATOR", cancelled.lastErrorCode());
        assertTrue(cancelled.lastErrorMessage().contains("super-user"), cancelled.lastErrorMessage());
        assertEquals(cancelled, store.findByExecutionId(waiting.getExecutionId()).orElseThrow());

        EventEnvelope approved = new EventEnvelope("evt-late", "InvoiceApproved", 1000L,
                Map.of("status", "APPROVED"), "corr-cancel-1", "cause", "external", 0, "default", "anonymous");
        events.append(approved);
        runner.resumeFlow("corr-cancel-1", approved);
        assertEquals(FlowInstanceStatus.FAILED_PERMANENT,
                store.findByExecutionId(waiting.getExecutionId()).orElseThrow().status(),
                "a cancelled execution must stay cancelled when the awaited event arrives");
        assertEquals(Optional.empty(), runner.cancelExecution("no-such-execution", "super-user"));
    }

    @Test
    void cancelRefusesCompletedAndRunningExecutions() {
        InMemoryStore store = new InMemoryStore();
        KernelRunner runner = runner(chargeThenShipFlow(false),
                (call, state) -> CapabilityResult.success(call.input()), store);
        ExecutionResult done = runner.execute("ChargeAndShip", Map.of("correlationId", "corr-cancel-2"));
        assertEquals(FlowInstanceStatus.COMPLETED, store.findByExecutionId(done.getExecutionId()).orElseThrow().status());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> runner.cancelExecution(done.getExecutionId(), "super-user"));
        assertTrue(refused.getMessage().startsWith("NOT_CANCELLABLE:"), refused.getMessage());

        FlowInstance completed = store.findByExecutionId(done.getExecutionId()).orElseThrow();
        FlowInstance running = completed.markRunning(0, completed.state(), 9000L);
        assertThrows(IllegalStateException.class, () -> running.markCancelled("super-user", 9001L));
    }

    @Test
    void cancelAlsoAcceptsAStuckExecution() {
        FlowInstance waiting = new FlowInstance("exec-stuck", "AwaitApproval", "corr-stuck", "default", "anonymous",
                0, FlowInstanceStatus.WAITING_EVENT, Map.of("input", Map.of()), "InvoiceApproved",
                1000L, 2000L, 3, 1500L, "boom", 1500L, 1000L);
        FlowInstance stuck = waiting.markStuck(waiting.state(), 3000L, null);
        assertEquals(FlowInstanceStatus.STUCK, stuck.status());
        assertEquals(FlowInstanceStatus.FAILED_PERMANENT, stuck.markCancelled("op", 4000L).status());
    }

    private static KernelRunner runner(
            InMemoryFlowDefinitionProvider flows, CapabilityDispatcher dispatcher, InMemoryStore store) {
        Events events = new Events();
        return new KernelRunner(events, (entity, payload) -> List.of(), flows, dispatcher, events, store);
    }

    private static InMemoryFlowDefinitionProvider chargeThenShipFlow(boolean withCompensation) {
        FlowStepDefinition charge = FlowStepDefinition.capabilityCall("charge-card", "payments", "charge", "input", "charged");
        if (withCompensation) {
            charge = charge.withOnFailure(List.of(
                    FlowStepDefinition.capabilityCall("refund-card", "payments", "refund", "input", "refunded")));
        }
        return new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "ChargeAndShip", "Order",
                List.of(charge, FlowStepDefinition.capabilityCall("ship-order", "shipping", "ship", "input", "shipped"))));
    }

    private static InMemoryFlowDefinitionProvider awaitFlow() {
        return new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "AwaitApproval", "Invoice",
                List.of(FlowStepDefinition.awaitEvent("wait-approval", "InvoiceApproved", "$approval"),
                        FlowStepDefinition.returnValue("return-status", "$approval.status"))));
    }

    private static final class InMemoryStore implements FlowInstanceStore {
        private final Map<String, FlowInstance> byId = new ConcurrentHashMap<>();

        @Override
        public void save(FlowInstance instance) {
            byId.put(instance.executionId(), instance);
        }

        @Override
        public void update(FlowInstance instance) {
            byId.put(instance.executionId(), instance);
        }

        @Override
        public Optional<FlowInstance> findByExecutionId(String executionId) {
            return Optional.ofNullable(byId.get(executionId));
        }

        private List<FlowInstance> waiting() {
            return byId.values().stream().filter(i -> i.status() == FlowInstanceStatus.WAITING_EVENT).toList();
        }

        @Override
        public List<FlowInstance> findWaitingByCorrelation(String correlationId) {
            return waiting().stream().filter(i -> correlationId.equals(i.correlationId())).toList();
        }

        @Override
        public List<FlowInstance> findWaitingByEvent(String eventName) {
            return waiting().stream().filter(i -> eventName.equals(i.waitingForEventName())).toList();
        }

        @Override
        public List<FlowInstance> findAllWaiting(int limit) {
            return waiting();
        }

        @Override
        public List<FlowInstance> findWaitingEligibleToResume(String tenantId, long nowEpochMs, int limit) {
            return waiting();
        }

        @Override
        public List<FlowInstance> findStaleWaiting(String tenantId, long olderThanEpochMs, int limit, int offset) {
            return List.of();
        }
    }

    private static final class Events implements EventBus, EventStore {
        private final List<EventEnvelope> stored = new CopyOnWriteArrayList<>();

        @Override
        public void publish(EventEnvelope event) {
        }

        @Override
        public void append(EventEnvelope event) {
            stored.add(event);
        }

        @Override
        public Optional<EventEnvelope> findFirst(String eventName, String correlationId) {
            return stored.stream()
                    .filter(e -> eventName.equals(e.eventName()) && correlationId.equals(e.correlationId()))
                    .findFirst();
        }

        @Override
        public List<EventEnvelope> readByCorrelation(String correlationId) {
            List<EventEnvelope> out = new ArrayList<>();
            for (EventEnvelope event : stored) {
                if (correlationId.equals(event.correlationId())) {
                    out.add(event);
                }
            }
            return List.copyOf(out);
        }

        @Override
        public List<EventEnvelope> readByEventName(String eventName) {
            List<EventEnvelope> out = new ArrayList<>();
            for (EventEnvelope event : stored) {
                if (eventName.equals(event.eventName())) {
                    out.add(event);
                }
            }
            return List.copyOf(out);
        }
    }
}
