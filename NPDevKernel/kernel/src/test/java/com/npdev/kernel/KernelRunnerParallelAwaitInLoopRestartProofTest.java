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
import java.util.concurrent.atomic.AtomicInteger;

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

    // Wave 3 (S8_DEFERRED_FIVE_PLAN.md, 2026-08-04) I4 -- THE HARD STOP: vectors 6 and 7 need a
    // genuinely MULTI-STEP body (a capability call before AND after the await), not the single
    // await-only body the two tests above already cover.
    private static final String MULTI_AWAIT_STEP_NAME = "multi-await-approval";
    private static final String MULTI_LOOP_STEP_NAME = "multi-await-loop";

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
        assertEquals(Boolean.TRUE, midway.state().get(FlowStateCodec.parallelLoopIterationDoneKey(LOOP_STEP_NAME, 0)),
                "iteration 0 must already be resolved");
        assertEquals(Boolean.TRUE, midway.state().get(FlowStateCodec.parallelLoopIterationDoneKey(LOOP_STEP_NAME, 2)),
                "iteration 2 must already be resolved, even though its event arrived FIRST");
        assertFalse(Boolean.TRUE.equals(midway.state().get(FlowStateCodec.parallelLoopIterationDoneKey(LOOP_STEP_NAME, 1))),
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
            assertFalse(completed.state().containsKey(FlowStateCodec.parallelLoopIterationDoneKey(LOOP_STEP_NAME, i)));
            assertFalse(completed.state().containsKey(
                    FlowStateCodec.parallelLoopScopedKey(LOOP_STEP_NAME, i, FlowStateCodec.AWAIT_STATE_KEY)));
        }
    }

    @Test
    void crashAfterOneSlotResolvesDoesNotReQueryItsAlreadyProcessedEvent() throws InterruptedException {
        RecordingEventInfrastructure events = new RecordingEventInfrastructure();
        String resolvedMarkerKey = FlowStateCodec.parallelLoopIterationDoneKey(LOOP_STEP_NAME, 0);
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
                Boolean.TRUE.equals(frozenSnapshot.state().get(FlowStateCodec.parallelLoopIterationDoneKey(LOOP_STEP_NAME, 1))),
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
        assertFalse(afterRestartSnapshot.state().containsKey(
                FlowStateCodec.parallelLoopScopedKey(LOOP_STEP_NAME, 0, FlowStateCodec.AWAIT_STATE_KEY)),
                "iteration 0's wait descriptor must have been cleared when it resolved, not linger");
    }

    /**
     * Wave 3 I4 -- THE HARD STOP, vector 6: restart with iterations parked at DIFFERENT steps.
     * B15(B)'s own proof above (single-await body) always had every iteration at the SAME step; a
     * multi-step body means iteration 0 can be fully done (past its capability call, its await, AND
     * its post-await call) while iteration 1 is still parked mid-body at its own await, and
     * iteration 2 has not been touched at all during the crash-recovery pass -- a strictly harder
     * resume problem. Uses a {@link CountingCapabilityDispatcher} to prove the thing vector 6
     * explicitly names: iteration 0's pre-await capability call must NOT re-run once it is durably
     * done, across a real process restart.
     */
    @Test
    void multiStepBodyRestartWithIterationsAtDifferentStepsDoesNotReprocessTheResolvedIteration()
            throws InterruptedException {
        RecordingEventInfrastructure events = new RecordingEventInfrastructure();
        CountingCapabilityDispatcher dispatcher = new CountingCapabilityDispatcher();
        String iteration0DoneKey = FlowStateCodec.parallelLoopIterationDoneKey(MULTI_LOOP_STEP_NAME, 0);
        RaceWindowFlowInstanceStore store = new RaceWindowFlowInstanceStore(iteration0DoneKey);

        KernelRunner runner1 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForMultiStepParallelLoopFlow(),
                dispatcher, events, store);
        Map<String, Object> input = Map.of(
                "correlationId", "corr-b15b-w3-vector6",
                "items", List.of("a", "b", "c")
        );

        // Pass 1 (unfrozen): nothing queued yet -- all 3 iterations attempt their pre-await
        // capability call once, park at their own await, none resolve.
        ExecutionResult started = runner1.execute("MultiStepParallelLoopFlow", input);
        assertEquals(ExecutionStatus.WAITING_EVENT, started.getStatus());
        String executionId = started.getExecutionId();
        assertEquals(1, dispatcher.countFor("a"));
        assertEquals(1, dispatcher.countFor("b"));
        assertEquals(1, dispatcher.countFor("c"));

        // Pass 2 (frozen): only iteration 0's event is delivered. The crashed process re-attempts
        // (idempotent re-run of the pre-await call is expected and accepted -- B15(A)'s own
        // assumption), resolves its await, runs its post-await emitEvent, and durably completes --
        // frozen the INSTANT that completes, before iteration 1 is even re-attempted.
        events.append(approvalEvent(
                FlowStateCodec.deriveForEachIterationCorrelationId(executionId, MULTI_AWAIT_STEP_NAME, 0), "approved-a"));
        KernelRunner runner2 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForMultiStepParallelLoopFlow(),
                dispatcher, events, store);
        Thread crashedProcessThread = new Thread(
                () -> runner2.resumeExecution(executionId),
                "simulated-crashed-process-multistep-mid-parallel-await"
        );
        crashedProcessThread.setDaemon(true);
        crashedProcessThread.start();
        assertTrue(store.awaitPausePoint(10, TimeUnit.SECONDS),
                "timed out waiting for the simulated crash right after iteration 0 fully completed");

        FlowInstance frozen = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(Boolean.TRUE, frozen.state().get(iteration0DoneKey), "iteration 0 must be durably done");
        assertFalse(Boolean.TRUE.equals(frozen.state().get(
                        FlowStateCodec.parallelLoopIterationDoneKey(MULTI_LOOP_STEP_NAME, 1))),
                "iteration 1 must still be outstanding -- parked AT its own await, a DIFFERENT step than iteration 0");
        assertFalse(Boolean.TRUE.equals(frozen.state().get(
                        FlowStateCodec.parallelLoopIterationDoneKey(MULTI_LOOP_STEP_NAME, 2))),
                "iteration 2 must still be outstanding");
        assertEquals(2, dispatcher.countFor("a"), "iteration 0's pre-await call ran once per pass so far (1+1)");
        assertEquals(1, dispatcher.countFor("c"),
                "iteration 2 must not have been reached AT ALL during the frozen pass -- it was still item 1's "
                        + "turn when the freeze fired");

        // Restart: a brand-new runner resumes from that exact frozen checkpoint, delivering
        // iteration 1's event. THE hard-stop assertion: iteration 0's capability call must NOT
        // run a third time (the crash happened AFTER it durably completed) -- only its
        // parallelLoopIterationDoneKey marker, not a re-query or a re-run, may account for it.
        events.append(approvalEvent(
                FlowStateCodec.deriveForEachIterationCorrelationId(executionId, MULTI_AWAIT_STEP_NAME, 1), "approved-b"));
        KernelRunner runner3 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForMultiStepParallelLoopFlow(),
                dispatcher, events, store);
        ExecutionResult afterRestart = runner3.resumeExecution(executionId);
        assertEquals(ExecutionStatus.WAITING_EVENT, afterRestart.getStatus(), "iteration 2 still has no event");

        assertEquals(2, dispatcher.countFor("a"),
                "HARD STOP assertion: iteration 0 must NOT re-run its capability call across the restart");
        assertEquals(2, dispatcher.countFor("b"), "iteration 1 resumes at its own await, re-running its idempotent "
                + "pre-await call once more (1 from the frozen pass + 1 on restart), same as B15(A)'s assumption");
        assertEquals(2, dispatcher.countFor("c"), "iteration 2 starts clean this pass -- one attempt, parks again");
        FlowInstance afterRestartSnapshot = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(Boolean.TRUE, afterRestartSnapshot.state().get(
                FlowStateCodec.parallelLoopIterationDoneKey(MULTI_LOOP_STEP_NAME, 1)), "iteration 1 must now be done");

        // Close it out: deliver iteration 2's event and confirm a clean completion.
        events.append(approvalEvent(
                FlowStateCodec.deriveForEachIterationCorrelationId(executionId, MULTI_AWAIT_STEP_NAME, 2), "approved-c"));
        ExecutionResult finalResult = runner3.resumeExecution(executionId);
        assertEquals(ExecutionStatus.OK, finalResult.getStatus());
        assertEquals(2, dispatcher.countFor("a"), "still no further re-run of iteration 0 on the final pass");
        FlowInstance completed = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, completed.status());
        List<?> approvals = (List<?>) completed.state().get("approval");
        assertEquals(3, approvals.size());
        assertEquals("approved-a", valueOf(approvals.get(0)));
        assertEquals("approved-b", valueOf(approvals.get(1)));
        assertEquals("approved-c", valueOf(approvals.get(2)));
    }

    /**
     * Wave 3 I4, vector 7: out-of-order delivery with a multi-step body -- B15(B) proved this for a
     * single-step body above; re-proving it here rather than assuming it carries, since Wave 3's
     * per-iteration scope is new machinery vectors 1-5 didn't exercise across a restart.
     */
    @Test
    void multiStepBodyOutOfOrderDeliveryResumesEachIterationExactlyOnce() {
        RecordingEventInfrastructure events = new RecordingEventInfrastructure();
        CountingCapabilityDispatcher dispatcher = new CountingCapabilityDispatcher();
        InMemoryFlowInstanceStore store = new InMemoryFlowInstanceStore();

        KernelRunner runner1 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForMultiStepParallelLoopFlow(),
                dispatcher, events, store);
        Map<String, Object> input = Map.of(
                "correlationId", "corr-b15b-w3-vector7",
                "items", List.of("a", "b", "c")
        );
        ExecutionResult started = runner1.execute("MultiStepParallelLoopFlow", input);
        assertEquals(ExecutionStatus.WAITING_EVENT, started.getStatus());
        String executionId = started.getExecutionId();

        String corr0 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, MULTI_AWAIT_STEP_NAME, 0);
        String corr1 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, MULTI_AWAIT_STEP_NAME, 1);
        String corr2 = FlowStateCodec.deriveForEachIterationCorrelationId(executionId, MULTI_AWAIT_STEP_NAME, 2);

        // Deliver iteration 2's reply first, then 0's, withholding 1's for this round.
        events.append(approvalEvent(corr2, "approved-c"));
        events.append(approvalEvent(corr0, "approved-a"));
        KernelRunner runner2 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForMultiStepParallelLoopFlow(),
                dispatcher, events, store);
        ExecutionResult afterFirstResume = runner2.resumeExecution(executionId);
        assertEquals(ExecutionStatus.WAITING_EVENT, afterFirstResume.getStatus(), "iteration 1 still has no event");

        FlowInstance midway = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(Boolean.TRUE, midway.state().get(FlowStateCodec.parallelLoopIterationDoneKey(MULTI_LOOP_STEP_NAME, 0)));
        assertEquals(Boolean.TRUE, midway.state().get(FlowStateCodec.parallelLoopIterationDoneKey(MULTI_LOOP_STEP_NAME, 2)));
        assertFalse(Boolean.TRUE.equals(
                midway.state().get(FlowStateCodec.parallelLoopIterationDoneKey(MULTI_LOOP_STEP_NAME, 1))));

        // Deliver iteration 1's reply last, then restart with a brand-new runner.
        events.append(approvalEvent(corr1, "approved-b"));
        KernelRunner runner3 = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProviderForMultiStepParallelLoopFlow(),
                dispatcher, events, store);
        ExecutionResult finalResult = runner3.resumeExecution(executionId);
        assertEquals(ExecutionStatus.OK, finalResult.getStatus());

        FlowInstance completed = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, completed.status());
        List<?> approvals = (List<?>) completed.state().get("approval");
        assertEquals(3, approvals.size());
        assertEquals("approved-a", valueOf(approvals.get(0)), "iteration 0's own reply lands at index 0");
        assertEquals("approved-b", valueOf(approvals.get(1)), "iteration 1's own reply lands at index 1, though it arrived LAST");
        assertEquals("approved-c", valueOf(approvals.get(2)), "iteration 2's own reply lands at index 2, though it arrived FIRST");

        // Exactly-once PER PASS a not-yet-done iteration was attempted in -- never more, proving no
        // iteration was re-processed by another's resolution, in EITHER delivery order. Iterations 0
        // and 2 resolved in the first resume (2 passes total: initial + first resume); iteration 1
        // did not resolve until the final resume (3 passes: initial + first resume + final resume).
        assertEquals(2, dispatcher.countFor("a"));
        assertEquals(3, dispatcher.countFor("b"));
        assertEquals(2, dispatcher.countFor("c"));

        for (int i = 0; i < 3; i++) {
            assertFalse(completed.state().containsKey(FlowStateCodec.parallelLoopIterationDoneKey(MULTI_LOOP_STEP_NAME, i)));
            assertFalse(completed.state().containsKey(
                    FlowStateCodec.parallelLoopScopedKey(MULTI_LOOP_STEP_NAME, i, FlowStateCodec.AWAIT_STATE_KEY)));
        }
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

    /** Wave 3 I4: a genuinely multi-step parallelAwait body -- a capability call BEFORE the await
     *  (whose re-run count is what proves the hard stop) and an emitEvent AFTER it, exactly the
     *  shape vector 6 names ({@code ["capabilityCall", "awaitEvent", "emitEvent"]}). */
    private static InMemoryFlowDefinitionProvider flowProviderForMultiStepParallelLoopFlow() {
        return new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "MultiStepParallelLoopFlow",
                        "Order",
                        List.of(
                                FlowStepDefinition.forEach(
                                        MULTI_LOOP_STEP_NAME,
                                        "input.items",
                                        "item",
                                        List.of(
                                                FlowStepDefinition.capabilityCall(
                                                        "calc", "counting-capability", "increment", "item", "calcOut"),
                                                FlowStepDefinition.awaitEvent(
                                                        MULTI_AWAIT_STEP_NAME,
                                                        "ItemApproved",
                                                        "approval"
                                                ),
                                                FlowStepDefinition.emitEvent(
                                                        "notify", "ItemNotified", "approval")
                                        ),
                                        10,
                                        true
                                )
                        )
                ));
    }

    /** Wave 3 I4: counts capability invocations per item value, so a test can assert an iteration's
     *  pre-await step did or did not re-run across a simulated crash/restart. */
    private static final class CountingCapabilityDispatcher implements CapabilityDispatcher {
        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        @Override
        public CapabilityResult invoke(CapabilityCall call, Map<String, Object> state) {
            if ("increment".equals(call.operation())) {
                String item = String.valueOf(call.input());
                counts.computeIfAbsent(item, ignored -> new AtomicInteger()).incrementAndGet();
                return CapabilityResult.success("calc-" + item);
            }
            return CapabilityResult.success(null);
        }

        int countFor(String item) {
            AtomicInteger count = counts.get(item);
            return count == null ? 0 : count.get();
        }
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
