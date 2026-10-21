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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-261: publishing the awaited event of every iteration of a {@code parallelAwait} forEach must
 * resume the execution BY ITSELF, exactly as a single awaitEvent resumes on its event -- no explicit
 * {@code resumeExecution} call. The existing restart-proof tests all call {@code resumeExecution}
 * directly (and their store's {@code findWaitingByEvent} is a stub), so they never exercised the
 * event-arrival path, which asks {@code FlowStateCodec.resolveOutstandingParallelAwaitSlots} whether
 * the incoming event satisfies one of the instance's parked iterations.
 */
class KernelRunnerParallelAwaitEventResumeTest {

    private static final String AWAIT_STEP_NAME = "await-approval";
    private static final String LOOP_STEP_NAME = "await-loop";

    @Test
    void publishingTheAwaitedEventOfEveryIterationCompletesTheExecutionWithoutAnExplicitResume() {
        Infrastructure events = new Infrastructure();
        WaitingAwareStore store = new WaitingAwareStore();
        KernelRunner runner = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProvider(),
                (CapabilityDispatcher) (call, state) -> CapabilityResult.success(null), events, store);

        ExecutionResult started = runner.execute("ParallelAwaitLoopFlow",
                Map.of("correlationId", "corr-reg261", "items", List.of("a", "b")));
        assertEquals(ExecutionStatus.WAITING_EVENT, started.getStatus());
        String executionId = started.getExecutionId();

        // The parked iterations are discoverable from the persisted state alone: one slot each,
        // keyed on the per-iteration correlation id the event will carry.
        List<FlowStateCodec.ParallelAwaitSlot> slots = FlowStateCodec.resolveOutstandingParallelAwaitSlots(
                executionId, store.findByExecutionId(executionId).orElseThrow().state());
        assertEquals(2, slots.size(), "both parked iterations must be visible as outstanding slots");
        assertTrue(slots.stream().allMatch(slot -> AWAIT_STEP_NAME.equals(slot.stepName())));

        String corr0 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 0);
        String corr1 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 1);

        runner.publishExternalEvent("ItemApproved", Map.of("value", "approved-a"), corr0, "test");
        FlowInstance midway = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(FlowInstanceStatus.WAITING_EVENT, midway.status(), "iteration 1 has no event yet");
        assertEquals(Boolean.TRUE, midway.state().get(FlowStateCodec.parallelLoopIterationDoneKey(LOOP_STEP_NAME, 0)),
                "the first publish must already have resolved iteration 0, with no resume call");
        assertEquals(1, FlowStateCodec.resolveOutstandingParallelAwaitSlots(executionId, midway.state()).size(),
                "only iteration 1 is still outstanding");

        runner.publishExternalEvent("ItemApproved", Map.of("value", "approved-b"), corr1, "test");
        FlowInstance completed = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, completed.status(),
                "the last awaited event must finish the execution by itself");
    }

    @Test
    void anEventWithAnUnrelatedCorrelationLeavesTheParallelWaitUntouched() {
        Infrastructure events = new Infrastructure();
        WaitingAwareStore store = new WaitingAwareStore();
        KernelRunner runner = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProvider(),
                (CapabilityDispatcher) (call, state) -> CapabilityResult.success(null), events, store);

        ExecutionResult started = runner.execute("ParallelAwaitLoopFlow",
                Map.of("correlationId", "corr-reg261-other", "items", List.of("a", "b")));
        String executionId = started.getExecutionId();

        runner.publishExternalEvent("ItemApproved", Map.of("value", "x"), "someone-else", "test");

        FlowInstance still = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(FlowInstanceStatus.WAITING_EVENT, still.status());
        assertEquals(2, FlowStateCodec.resolveOutstandingParallelAwaitSlots(executionId, still.state()).size());
    }

    private static InMemoryFlowDefinitionProvider flowProvider() {
        return new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "ParallelAwaitLoopFlow",
                        "Order",
                        List.of(
                                FlowStepDefinition.forEach(
                                        LOOP_STEP_NAME,
                                        "input.items",
                                        "item",
                                        List.of(FlowStepDefinition.awaitEvent(AWAIT_STEP_NAME, "ItemApproved", "approval")),
                                        10,
                                        true
                                )
                        )
                ));
    }

    /** Unlike the restart-proof tests' stub, this store answers the waiting lookups the event-arrival path makes. */
    private static final class WaitingAwareStore implements FlowInstanceStore {
        private final Map<String, FlowInstance> byExecutionId = new ConcurrentHashMap<>();

        @Override
        public void save(FlowInstance instance) {
            byExecutionId.put(instance.executionId(), instance);
        }

        @Override
        public void update(FlowInstance instance) {
            byExecutionId.put(instance.executionId(), instance);
        }

        @Override
        public Optional<FlowInstance> findByExecutionId(String executionId) {
            return Optional.ofNullable(byExecutionId.get(executionId));
        }

        @Override
        public List<FlowInstance> findWaitingByCorrelation(String correlationId) {
            return List.of();
        }

        @Override
        public List<FlowInstance> findWaitingByEvent(String eventName) {
            List<FlowInstance> out = new ArrayList<>();
            for (FlowInstance instance : byExecutionId.values()) {
                if (instance.status() == FlowInstanceStatus.WAITING_EVENT
                        && eventName != null && eventName.equals(instance.waitingForEventName())) {
                    out.add(instance);
                }
            }
            return out;
        }

        @Override
        public List<FlowInstance> findAllWaiting(int limit) {
            return List.of();
        }

        @Override
        public List<FlowInstance> findWaitingEligibleToResume(String tenantId, long nowEpochMs, int limit) {
            return List.of();
        }

        @Override
        public List<FlowInstance> findStaleWaiting(String tenantId, long olderThanEpochMs, int limit, int offset) {
            return List.of();
        }
    }

    private static final class Infrastructure implements EventBus, EventStore {
        private final List<EventEnvelope> stored = new CopyOnWriteArrayList<>();

        @Override
        public void publish(EventEnvelope event) {
            // the store append below is what the await lookup reads
        }

        @Override
        public void append(EventEnvelope event) {
            stored.add(event);
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
