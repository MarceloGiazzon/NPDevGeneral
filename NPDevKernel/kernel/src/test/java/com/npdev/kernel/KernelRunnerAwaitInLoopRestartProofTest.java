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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 16 Phase B increment 4 (docs/BOUNDARY_LIFT_ROADMAP.md, B15(A)): the required, non-optional
 * restart proof for a SEQUENTIAL {@code await} nested in a {@code forEach} loop body -- per this
 * Move's own spec, a per-iteration correlation fix that only compiles is not enough; it must be
 * proven durable across a real process restart, with events arriving out of order, or the boundary
 * has to stay standing. Two scenarios:
 *
 * <ol>
 *   <li>{@link #outOfOrderEventsAcrossFullRestartsResolveEachIterationExactlyOnce()} -- a brand-new
 *   {@link KernelRunner} (no in-memory state, only the shared durable stores) resumes after each
 *   iteration parks, and the LAST iteration's own reply event is delivered and already sitting in
 *   the store BEFORE the currently-waiting (earlier) iteration's own reply arrives. Proves the
 *   per-iteration correlation id (see {@link FlowStateCodec#deriveForEachIterationCorrelationId})
 *   discriminates correctly: the earlier iteration must not consume the later iteration's event.</li>
 *   <li>{@link #crashBetweenEventConsumedAndProgressAdvancedDoesNotReAwaitForever()} -- freezes the
 *   executing thread (same technique as {@code KernelRunnerForEachDurabilityTest}: a real durable
 *   write lands, then the thread never returns, so neither {@code executeFlowInstance}'s own
 *   catch/finally nor the loop's own bookkeeping ever runs -- indistinguishable from a real crash)
 *   at the exact instant the mid-iteration checkpoint persists the satisfaction marker, i.e. AFTER
 *   the event is durably consumed but BEFORE the outer loop's own per-iteration progress advances.
 *   A fresh runner resuming from that frozen point must complete the iteration using the marker,
 *   not by re-querying the event store -- the one satisfying event is already marked processed by
 *   the (separate) idempotency store from the frozen attempt, so a naive re-query would find
 *   nothing and park the flow WAITING forever on an event that will never arrive again.</li>
 * </ol>
 */
class KernelRunnerAwaitInLoopRestartProofTest {

    private static final String AWAIT_STEP_NAME = "await-approval";

    @Test
    void outOfOrderEventsAcrossFullRestartsResolveEachIterationExactlyOnce() {
        List<String> recordedPairs = new CopyOnWriteArrayList<>();
        CapabilityDispatcher recordingDispatcher = (call, state) ->
                recordApprovalPair(recordedPairs, state, call);

        RecordingEventInfrastructure events = new RecordingEventInfrastructure();
        InMemoryFlowInstanceStore store = new InMemoryFlowInstanceStore();

        KernelRunner runner1 = new KernelRunner(
                events,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitInLoopFlow(),
                recordingDispatcher,
                events,
                store
        );

        Map<String, Object> input = Map.of(
                "correlationId", "corr-b15-restart-1",
                "items", List.of("a", "b", "c")
        );

        ExecutionResult started = runner1.execute("AwaitLoopFlow", input);
        assertEquals(ExecutionStatus.WAITING_EVENT, started.getStatus(), "iteration 0 has no event yet");
        String executionId = started.getExecutionId();
        String corr0 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 0);
        assertEquals(corr0, started.getAwaitedCorrelationId(),
                "the WAITING result must expose the exact per-iteration correlation id an external caller needs to reply with");

        events.append(approvalEvent(corr0, "approved-a"));

        // Restart 1: brand-new runner, only the durable store carries over.
        KernelRunner runner2 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForAwaitInLoopFlow(), recordingDispatcher, events, store);
        ExecutionResult afterIteration0 = runner2.resumeExecution(executionId);
        assertEquals(ExecutionStatus.WAITING_EVENT, afterIteration0.getStatus(), "iteration 1 has no event yet");
        assertEquals(List.of("a->approved-a"), recordedPairs);
        String corr1 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 1);
        assertEquals(corr1, afterIteration0.getAwaitedCorrelationId());

        // "Kill the process with iteration 1 satisfied and 2 unstarted" (1-indexed in the spec's own
        // language == iteration index 0 done, index 1 currently waiting, index 2 not yet reached).
        // Deliver iteration 2's own event FIRST, out of order, while iteration 1 is still the one
        // actually blocking -- it must sit unconsumed in the store until iteration 1 is done.
        String corr2 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 2);
        events.append(approvalEvent(corr2, "approved-c"));
        events.append(approvalEvent(corr1, "approved-b"));

        // Restart 2: another brand-new runner.
        KernelRunner runner3 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForAwaitInLoopFlow(), recordingDispatcher, events, store);
        ExecutionResult finalResult = runner3.resumeExecution(executionId);

        assertEquals(ExecutionStatus.OK, finalResult.getStatus());
        assertEquals(
                List.of("a->approved-a", "b->approved-b", "c->approved-c"),
                recordedPairs,
                "each iteration must resume exactly once, using its own event, in order -- iteration 1 "
                        + "must not have consumed iteration 2's event just because it arrived first"
        );
        FlowInstance completed = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, completed.status());
    }

    @Test
    void crashBetweenEventConsumedAndProgressAdvancedDoesNotReAwaitForever() throws InterruptedException {
        List<String> recordedPairs = new CopyOnWriteArrayList<>();
        CapabilityDispatcher recordingDispatcher = (call, state) ->
                recordApprovalPair(recordedPairs, state, call);

        RecordingEventInfrastructure events = new RecordingEventInfrastructure();
        String satisfiedMarkerKey = FlowStateCodec.FOR_EACH_AWAIT_SATISFIED_KEY_PREFIX + AWAIT_STEP_NAME;
        RaceWindowFlowInstanceStore store = new RaceWindowFlowInstanceStore(satisfiedMarkerKey);

        KernelRunner runner1 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForAwaitInLoopFlow(), recordingDispatcher, events, store);

        Map<String, Object> input = Map.of(
                "correlationId", "corr-b15-restart-2",
                "items", List.of("a", "b")
        );
        ExecutionResult started = runner1.execute("AwaitLoopFlow", input);
        assertEquals(ExecutionStatus.WAITING_EVENT, started.getStatus());
        String executionId = started.getExecutionId();
        String corr0 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 0);
        events.append(approvalEvent(corr0, "approved-a"));

        KernelRunner runner2 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForAwaitInLoopFlow(), recordingDispatcher, events, store);
        Thread crashedProcessThread = new Thread(
                () -> runner2.resumeExecution(executionId),
                "simulated-crashed-process-mid-race"
        );
        crashedProcessThread.setDaemon(true);
        crashedProcessThread.start();

        assertTrue(
                store.awaitPausePoint(10, TimeUnit.SECONDS),
                "timed out waiting for the simulated crash at the mid-iteration checkpoint"
        );

        // The frozen process durably persisted the satisfaction marker (and the consumed event's
        // state) but never got a chance to run the loop body's remaining step or advance progress.
        assertTrue(recordedPairs.isEmpty(), "the capability call after the await must not have run yet");
        FlowInstance frozenSnapshot = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(Boolean.TRUE, frozenSnapshot.state().get(satisfiedMarkerKey));
        Object frozenProgress = frozenSnapshot.state().get("__forEachProgress.await-loop");
        assertTrue(frozenProgress == null || Integer.valueOf(0).equals(frozenProgress),
                "progress must NOT have advanced past iteration 0 yet -- that's the exact race window, was: " + frozenProgress);

        // Restart: a brand-new runner resumes from that exact frozen checkpoint. Without the
        // satisfaction marker, this would call awaitEvent() again, find the one satisfying event
        // already marked processed by the idempotency store from runner2's frozen attempt, and park
        // the flow WAITING forever on an event that will never come again.
        KernelRunner runner3 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForAwaitInLoopFlow(), recordingDispatcher, events, store);
        ExecutionResult afterRestart = runner3.resumeExecution(executionId);

        assertEquals(ExecutionStatus.WAITING_EVENT, afterRestart.getStatus(), "iteration 1 has no event yet");
        assertEquals(List.of("a->approved-a"), recordedPairs,
                "iteration 0 must complete exactly once via the marker, not be re-queried or duplicated");
        String corr1 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 1);
        assertEquals(corr1, afterRestart.getAwaitedCorrelationId());
    }

    private static CapabilityResult recordApprovalPair(List<String> recordedPairs, Map<String, Object> state, CapabilityCall call) {
        Object approvalPayload = call.input();
        Object approvalValue = approvalPayload instanceof Map<?, ?> payloadMap ? payloadMap.get("value") : approvalPayload;
        recordedPairs.add(state.get("item") + "->" + approvalValue);
        return CapabilityResult.success(approvalPayload);
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
                "AwaitLoopFlow",
                0,
                "default",
                null
        );
    }

    private static InMemoryFlowDefinitionProvider flowProviderForAwaitInLoopFlow() {
        return new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "AwaitLoopFlow",
                        "Order",
                        List.of(
                                FlowStepDefinition.forEach(
                                        "await-loop",
                                        "input.items",
                                        "item",
                                        List.of(
                                                FlowStepDefinition.awaitEvent(
                                                        AWAIT_STEP_NAME,
                                                        "ItemApproved",
                                                        "approval"
                                                ),
                                                FlowStepDefinition.capabilityCall(
                                                        "record-approval",
                                                        "recorder",
                                                        "record",
                                                        "approval",
                                                        "recorded"
                                                )
                                        ),
                                        10
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
     * {@link FlowInstance} whose state already shows the target await's satisfaction marker set --
     * i.e. the exact mid-iteration checkpoint {@link ForEachStep}'s pinned progress recorder writes
     * right after {@link AwaitEventStep} consumes the awaited event, before the outer per-iteration
     * progress advance. Content-based (not a raw call-count) so it targets the checkpoint itself
     * rather than an easily-drifted call number.
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
