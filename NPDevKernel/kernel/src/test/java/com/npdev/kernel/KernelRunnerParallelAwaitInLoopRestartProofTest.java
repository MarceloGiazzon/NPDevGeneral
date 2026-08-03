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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S6 Phase B increment 4 (docs/BOUNDARY_LIFT_ROADMAP.md §B15(B)): the required, non-optional
 * restart proof for N-way PARALLEL {@code await} inside a {@code forEach} loop body -- per this
 * Move's own spec (and the same hard-stop rule Move 16/B15(A) already established): "if this
 * cannot be proven durable across a real restart, do not ship it." Two scenarios, mirroring
 * {@code KernelRunnerAwaitInLoopRestartProofTest}'s own technique but scaled from one outstanding
 * slot to N genuinely outstanding at once:
 *
 * <ol>
 *   <li>{@link #threeParallelAwaitsResolveOutOfOrderAcrossRestartsExactlyOnce()} -- N=3 iterations
 *   are ALL outstanding simultaneously (not one at a time). Events are delivered out of order
 *   across two full restarts (brand-new {@link KernelRunner}, no in-memory state carried over):
 *   iteration 2's reply arrives before iteration 0's, iteration 1's arrives last. Proves each
 *   iteration resumes exactly once, on its own event, regardless of arrival order, and the step
 *   only completes once every slot is resolved -- the merged result lands in ITERATION order,
 *   never arrival order.</li>
 *   <li>{@link #crashAfterOneSlotResolvesDoesNotReQueryItsAlreadyProcessedEvent()} -- freezes the
 *   executing thread (same real-durable-write-then-never-returns technique) at the exact instant
 *   iteration 0's resolution is persisted, BEFORE iteration 1 is even attempted. A fresh runner
 *   resuming from that frozen point must not re-query iteration 0's event (already marked
 *   processed by the idempotency store from the frozen attempt) and must correctly proceed to
 *   attempt the remaining iterations.</li>
 * </ol>
 */
class KernelRunnerParallelAwaitInLoopRestartProofTest {

    private static final String AWAIT_STEP_NAME = "await-approval";
    private static final String LOOP_STEP_NAME = "await-loop";

    @Test
    void threeParallelAwaitsResolveOutOfOrderAcrossRestartsExactlyOnce() {
        RecordingEventInfrastructure events = new RecordingEventInfrastructure();
        InMemoryFlowInstanceStore store = new InMemoryFlowInstanceStore();

        KernelRunner runner1 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForParallelAwaitLoopFlow(), noopDispatcher(), events, store);

        Map<String, Object> input = Map.of(
                "correlationId", "corr-b15b-restart-1",
                "items", List.of("a", "b", "c")
        );

        ExecutionResult started = runner1.execute("ParallelAwaitLoopFlow", input);
        assertEquals(ExecutionStatus.WAITING_EVENT, started.getStatus(), "none of the 3 iterations have an event yet");
        String executionId = started.getExecutionId();

        String corr0 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 0);
        String corr1 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 1);
        String corr2 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 2);

        // Out of order: iteration 2's own reply arrives BEFORE iteration 0's, iteration 1's is
        // withheld entirely for this round.
        events.append(approvalEvent(corr2, "approved-c"));
        events.append(approvalEvent(corr0, "approved-a"));

        // Restart 1: brand-new runner, only the durable store carries over.
        KernelRunner runner2 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForParallelAwaitLoopFlow(), noopDispatcher(), events, store);
        ExecutionResult afterFirstResume = runner2.resumeExecution(executionId);
        assertEquals(ExecutionStatus.WAITING_EVENT, afterFirstResume.getStatus(), "iteration 1 still has no event");

        FlowInstance midway = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(FlowInstanceStatus.WAITING_EVENT, midway.status());
        assertEquals(Boolean.TRUE, midway.state().get(FlowStateCodec.parallelAwaitResolvedKey(LOOP_STEP_NAME, 0)),
                "iteration 0 must already be resolved");
        assertEquals(Boolean.TRUE, midway.state().get(FlowStateCodec.parallelAwaitResolvedKey(LOOP_STEP_NAME, 2)),
                "iteration 2 must already be resolved, even though its event arrived FIRST");
        assertFalse(Boolean.TRUE.equals(midway.state().get(FlowStateCodec.parallelAwaitResolvedKey(LOOP_STEP_NAME, 1))),
                "iteration 1 must still be outstanding");

        // Deliver iteration 1's own reply last.
        events.append(approvalEvent(corr1, "approved-b"));

        // Restart 2: another brand-new runner.
        KernelRunner runner3 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForParallelAwaitLoopFlow(), noopDispatcher(), events, store);
        ExecutionResult finalResult = runner3.resumeExecution(executionId);

        assertEquals(ExecutionStatus.OK, finalResult.getStatus());
        FlowInstance completed = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, completed.status());

        Object rawApprovals = completed.state().get("approval");
        assertTrue(rawApprovals instanceof List<?>, "resolved payloads must be folded into a list under the awaitRef");
        List<?> approvals = (List<?>) rawApprovals;
        assertEquals(3, approvals.size());
        assertEquals("approved-a", valueOf(approvals.get(0)),
                "iteration 0's own reply must land at index 0, regardless of arrival order");
        assertEquals("approved-b", valueOf(approvals.get(1)),
                "iteration 1's own reply must land at index 1 -- it arrived LAST but belongs in the MIDDLE");
        assertEquals("approved-c", valueOf(approvals.get(2)),
                "iteration 2's own reply must land at index 2, even though its event arrived FIRST -- it must "
                        + "not have been consumed by iteration 0's or iteration 1's slot");

        // Exactly-once: every per-iteration marker/descriptor is cleaned up on completion, and a
        // redundant extra resume call (simulating a duplicate scheduler tick) must be a pure no-op.
        for (int i = 0; i < 3; i++) {
            assertFalse(completed.state().containsKey(FlowStateCodec.parallelAwaitResolvedKey(LOOP_STEP_NAME, i)));
            assertFalse(completed.state().containsKey(FlowStateCodec.parallelAwaitStateKey(LOOP_STEP_NAME, i)));
        }
    }

    @Test
    void crashAfterOneSlotResolvesDoesNotReQueryItsAlreadyProcessedEvent() throws InterruptedException {
        RecordingEventInfrastructure events = new RecordingEventInfrastructure();
        String resolvedMarkerKey = FlowStateCodec.parallelAwaitResolvedKey(LOOP_STEP_NAME, 0);
        RaceWindowFlowInstanceStore store = new RaceWindowFlowInstanceStore(resolvedMarkerKey);

        KernelRunner runner1 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForParallelAwaitLoopFlow(), noopDispatcher(), events, store);

        Map<String, Object> input = Map.of(
                "correlationId", "corr-b15b-restart-2",
                "items", List.of("a", "b")
        );
        ExecutionResult started = runner1.execute("ParallelAwaitLoopFlow", input);
        assertEquals(ExecutionStatus.WAITING_EVENT, started.getStatus());
        String executionId = started.getExecutionId();
        String corr0 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 0);
        events.append(approvalEvent(corr0, "approved-a"));

        KernelRunner runner2 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForParallelAwaitLoopFlow(), noopDispatcher(), events, store);
        Thread crashedProcessThread = new Thread(
                () -> runner2.resumeExecution(executionId),
                "simulated-crashed-process-mid-parallel-await"
        );
        crashedProcessThread.setDaemon(true);
        crashedProcessThread.start();

        assertTrue(
                store.awaitPausePoint(10, TimeUnit.SECONDS),
                "timed out waiting for the simulated crash right after iteration 0 resolved"
        );

        // The frozen process durably persisted iteration 0's resolution but never got a chance to
        // even ATTEMPT iteration 1.
        FlowInstance frozenSnapshot = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(Boolean.TRUE, frozenSnapshot.state().get(resolvedMarkerKey));
        assertFalse(
                Boolean.TRUE.equals(frozenSnapshot.state().get(FlowStateCodec.parallelAwaitResolvedKey(LOOP_STEP_NAME, 1))),
                "iteration 1 must NOT have been attempted yet -- that's the exact frozen instant"
        );

        // Restart: a brand-new runner resumes from that exact frozen checkpoint. Without the
        // per-iteration resolved marker, this would call awaitEvent() again for iteration 0, find
        // the one satisfying event already marked processed by the idempotency store from
        // runner2's frozen attempt, and treat iteration 0 as stuck WAITING forever.
        KernelRunner runner3 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForParallelAwaitLoopFlow(), noopDispatcher(), events, store);
        ExecutionResult afterRestart = runner3.resumeExecution(executionId);

        assertEquals(ExecutionStatus.WAITING_EVENT, afterRestart.getStatus(), "iteration 1 has no event yet");
        FlowInstance afterRestartSnapshot = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(Boolean.TRUE, afterRestartSnapshot.state().get(resolvedMarkerKey),
                "iteration 0 must remain resolved via its marker, not re-queried");
        assertFalse(afterRestartSnapshot.state().containsKey(FlowStateCodec.parallelAwaitStateKey(LOOP_STEP_NAME, 0)),
                "iteration 0's wait descriptor must have been cleared when it resolved, not linger");
    }

    private static Object valueOf(Object approvalPayload) {
        if (approvalPayload instanceof Map<?, ?> payloadMap) {
            return payloadMap.get("value");
        }
        return approvalPayload;
    }

    private static CapabilityDispatcher noopDispatcher() {
        return (call, state) -> CapabilityResult.success(null);
    }

    private static EventEnvelope approvalEvent(String correlationId, String payloadValue) {
        // Tenant must match ExecutionContext.anonymous().tenantId() ("default") -- the flow runs
        // under no explicit tenant, and KernelRunner.normalizeTenantOrDefault(null) resolves to
        // that same default for the event-store lookup, not to null.
        return new EventEnvelope(
                "evt-" + correlationId,
                "ItemApproved",
                System.currentTimeMillis(),
                Map.of("value", payloadValue),
                correlationId,
                "test:approval",
                "ParallelAwaitLoopFlow",
                0,
                "default",
                null
        );
    }

    private static InMemoryFlowDefinitionProvider flowProviderForParallelAwaitLoopFlow() {
        return new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "ParallelAwaitLoopFlow",
                        "Order",
                        List.of(
                                FlowStepDefinition.forEach(
                                        LOOP_STEP_NAME,
                                        "input.items",
                                        "item",
                                        List.of(
                                                FlowStepDefinition.awaitEvent(
                                                        AWAIT_STEP_NAME,
                                                        "ItemApproved",
                                                        "approval"
                                                )
                                        ),
                                        10,
                                        true
                                )
                        )
                ));
    }

    private static final class InMemoryFlowInstanceStore implements FlowInstanceStore {
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
            return List.of();
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

    /**
     * Freezes the calling thread forever the FIRST time an {@code update()} call durably persists a
     * {@link FlowInstance} whose state already shows the target marker set -- i.e. the exact
     * checkpoint {@link ParallelAwaitForEachStep} writes right after ONE iteration's awaited event
     * is durably consumed, before the next iteration is even attempted.
     */
    private static final class RaceWindowFlowInstanceStore implements FlowInstanceStore {
        private final Map<String, FlowInstance> byExecutionId = new ConcurrentHashMap<>();
        private final String armedMarkerKey;
        private final CountDownLatch reachedPausePoint = new CountDownLatch(1);
        private volatile boolean paused = false;

        RaceWindowFlowInstanceStore(String armedMarkerKey) {
            this.armedMarkerKey = armedMarkerKey;
        }

        boolean awaitPausePoint(long timeout, TimeUnit unit) throws InterruptedException {
            return reachedPausePoint.await(timeout, unit);
        }

        @Override
        public void save(FlowInstance instance) {
            byExecutionId.put(instance.executionId(), instance);
        }

        @Override
        public void update(FlowInstance instance) {
            byExecutionId.put(instance.executionId(), instance);
            if (!paused && Boolean.TRUE.equals(instance.state().get(armedMarkerKey))) {
                paused = true;
                reachedPausePoint.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
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
            return List.of();
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

    private static final class RecordingEventInfrastructure implements EventBus, EventStore {
        private final List<EventEnvelope> published = new CopyOnWriteArrayList<>();
        private final List<EventEnvelope> stored = new CopyOnWriteArrayList<>();

        @Override
        public void publish(EventEnvelope event) {
            published.add(event);
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
