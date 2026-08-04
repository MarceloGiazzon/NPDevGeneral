package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave 3 (S8_DEFERRED_FIVE_PLAN.md, 2026-08-04) I1: {@code w3-isolation-vectors.json} vectors 1, 3,
 * and 4, made executable against a genuinely MULTI-STEP {@code parallelAwait} loop body (a
 * capability call before the await, another after) -- proving {@link ParallelLoopIterationScope}
 * closes the clobber the single-step-body cap (B15(B), S6) existed to avoid, without walling off
 * ordinary reads of state set before the loop.
 *
 * <p>Every assertion here reads the per-iteration SHADOW state directly ({@link
 * ParallelLoopIterationScope#readScoped}) rather than the final folded result, because I2's own
 * decision (see {@link FlowStateCodec}'s {@code PARALLEL_LOOP_*} javadoc) is that everything except
 * the awaitRef payload is scoped-and-discarded once the step completes -- the isolation vectors are
 * about what happens DURING the loop, which is only observable before that final cleanup.
 */
class ParallelAwaitForEachStepIsolationTest {

    private static final String AWAIT_STEP_NAME = "await-approval";
    private static final String LOOP_STEP_NAME = "iso-loop";

    /** Vector 1: two iterations both write the FIXED singleton key {@code "last"} (via both {@link
     *  CapabilityCallStep}'s own unconditional {@code state.put("last", output)} and {@link
     *  ReturnStep}) -- each iteration's own value must be independently readable, never clobbered by
     *  a sibling's. */
    @Test
    void twoIterationsBothWritingLastDoNotClobberEachOther() {
        RecordingEventInfrastructure events = new RecordingEventInfrastructure();
        InMemoryFlowInstanceStore store = new InMemoryFlowInstanceStore();
        KernelRunner runner = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProvider(), echoingDispatcher(), events, store);

        Map<String, Object> input = Map.of(
                "correlationId", "corr-vector1",
                "items", List.of("a", "b", "c"),
                "outerSeed", "outer-value"
        );
        ExecutionResult started = runner.execute("IsolationLoopFlow", input);
        assertEquals(ExecutionStatus.WAITING_EVENT, started.getStatus());
        String executionId = started.getExecutionId();

        // Resolve iterations 0 and 2 only -- iteration 1 stays outstanding so the step cannot
        // complete (and clearAll() cannot run yet), letting this test inspect the shadow state
        // directly instead of the post-completion folded result.
        events.append(approvalEvent(FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 0)));
        events.append(approvalEvent(FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 2)));
        ExecutionResult afterResume = runner.resumeExecution(executionId);
        assertEquals(ExecutionStatus.WAITING_EVENT, afterResume.getStatus(), "iteration 1 has no event yet");

        Map<String, Object> state = store.findByExecutionId(executionId).orElseThrow().state();
        assertEquals("calc-a", ParallelLoopIterationScope.readScoped(state, LOOP_STEP_NAME, 0, "last"),
                "iteration 0's own \"last\" must reflect its own item, not iteration 2's");
        assertEquals("calc-c", ParallelLoopIterationScope.readScoped(state, LOOP_STEP_NAME, 2, "last"),
                "iteration 2's own \"last\" must reflect its own item, not iteration 0's");
        // Iteration 1 is still blocked AT its await (return never ran), but the capabilityCall
        // BEFORE the await already set "last" -- it must be iteration 1's OWN value, never
        // iteration 0's or 2's, confirming isolation even for a step before the await resolves.
        assertEquals("calc-b", ParallelLoopIterationScope.readScoped(state, LOOP_STEP_NAME, 1, "last"));
    }

    /** Vector 3 + 4, proved together as the spec requires ("an over-aggressive namespace passes 4
     *  and breaks 3"): a key set BEFORE the loop must be readable by every iteration (3), while a
     *  key an iteration writes DURING the loop must never leak into a sibling's turn (4). */
    @Test
    void outerStateIsReadableButSiblingWritesAreNotVisible() {
        RecordingEventInfrastructure events = new RecordingEventInfrastructure();
        InMemoryFlowInstanceStore store = new InMemoryFlowInstanceStore();
        KernelRunner runner = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProvider(), echoingDispatcher(), events, store);

        Map<String, Object> input = Map.of(
                "correlationId", "corr-vector3-4",
                "items", List.of("a", "b", "c"),
                "outerSeed", "outer-value"
        );
        ExecutionResult started = runner.execute("IsolationLoopFlow", input);
        assertEquals(ExecutionStatus.WAITING_EVENT, started.getStatus());
        String executionId = started.getExecutionId();

        events.append(approvalEvent(FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 0)));
        events.append(approvalEvent(FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 2)));
        ExecutionResult afterResume = runner.resumeExecution(executionId);
        assertEquals(ExecutionStatus.WAITING_EVENT, afterResume.getStatus());

        Map<String, Object> state = store.findByExecutionId(executionId).orElseThrow().state();

        // Vector 3: "outer" was set BEFORE the forEach step ever ran (see flowProvider()'s input
        // handling below via the "outer" seed step) -- every iteration's body copies it to
        // "seenOuter"; an over-aggressive namespace would make this UNRESOLVED (the literal string
        // "outer", KernelRunner.resolveReference's own not-found fallback) instead.
        assertEquals("outer-value", ParallelLoopIterationScope.readScoped(state, LOOP_STEP_NAME, 0, "seenOuter"));
        assertEquals("outer-value", ParallelLoopIterationScope.readScoped(state, LOOP_STEP_NAME, 2, "seenOuter"));

        // Vector 4: each iteration's body FIRST reads whatever bare "tally" currently holds (before
        // writing its own) into "leakCheck". Both iterations 0 and 2 parked on their FIRST attempt
        // (no event queued yet at the time of the initial execute() call) and are now on their
        // SECOND attempt (this resume): each correctly re-sees its OWN prior committed value
        // (idempotent re-run, the same assumption B15(A) already relies on) -- the leak this guards
        // against is iteration 2 (attempted AFTER iteration 0 within this SAME resume call) instead
        // seeing iteration 0's "calc-a", which would mean exit(0) failed to reset bare state back to
        // baseline before iteration 2's enter() ran.
        assertEquals("calc-a", ParallelLoopIterationScope.readScoped(state, LOOP_STEP_NAME, 0, "leakCheck"),
                "iteration 0 re-sees its OWN prior tally on this second attempt, not a sibling's");
        assertEquals("calc-c", ParallelLoopIterationScope.readScoped(state, LOOP_STEP_NAME, 2, "leakCheck"),
                "iteration 2 must see its OWN prior tally, never iteration 0's freshly-committed value");
        assertEquals("calc-a", ParallelLoopIterationScope.readScoped(state, LOOP_STEP_NAME, 0, "tally"));
        assertEquals("calc-c", ParallelLoopIterationScope.readScoped(state, LOOP_STEP_NAME, 2, "tally"));
    }

    /** I2 (vector 5): once ALL iterations resolve, everything a multi-step body wrote is
     *  scoped-and-discarded EXCEPT the await's own resolved payload, which folds into one ordered
     *  list under the awaitRef -- exactly the existing single-step-body convention, now
     *  reconstructed from each iteration's relocated shadow entry. */
    @Test
    void onlyTheAwaitPayloadSurvivesPastTheLoopEverythingElseIsDiscarded() {
        RecordingEventInfrastructure events = new RecordingEventInfrastructure();
        InMemoryFlowInstanceStore store = new InMemoryFlowInstanceStore();
        KernelRunner runner = new KernelRunner(
                events, (entityName, payload) -> List.of(), flowProvider(), echoingDispatcher(), events, store);

        Map<String, Object> input = Map.of(
                "correlationId", "corr-vector5",
                "items", List.of("a", "b"),
                "outerSeed", "outer-value"
        );
        ExecutionResult started = runner.execute("IsolationLoopFlow", input);
        assertEquals(ExecutionStatus.WAITING_EVENT, started.getStatus());
        String executionId = started.getExecutionId();

        events.append(approvalEvent(FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 0)));
        events.append(approvalEvent(FlowStateCodec.deriveForEachIterationCorrelationId(executionId, AWAIT_STEP_NAME, 1)));
        ExecutionResult finalResult = runner.resumeExecution(executionId);
        assertEquals(ExecutionStatus.OK, finalResult.getStatus());

        Map<String, Object> state = store.findByExecutionId(executionId).orElseThrow().state();
        assertEquals(List.of(Map.of("value", "approved"), Map.of("value", "approved")), state.get("approval"),
                "the await's own payload survives, folded into one ordered list under awaitRef");
        assertFalse(state.containsKey("tally"), "everything else the body wrote must be discarded");
        assertFalse(state.containsKey("seenOuter"), "everything else the body wrote must be discarded");
        assertFalse(state.containsKey("leakCheck"), "everything else the body wrote must be discarded");
        assertEquals("outer-value", state.get("outer"), "the PRE-loop outer key itself must be untouched");
        for (int i = 0; i < 2; i++) {
            assertFalse(state.containsKey(FlowStateCodec.parallelLoopIterationDoneKey(LOOP_STEP_NAME, i)));
        }
    }

    /** I3 (vector 9), kernel-level defense in depth: {@link FlowValidation} (DSL layer,
     *  {@code FlowForEachValidationTest.parallelAwaitWithNestedForEachIsRejectedWithNamedError})
     *  already refuses this at compile time -- this proves the RUNTIME guard in {@link
     *  ParallelAwaitForEachStep} independently rejects it too, for any flow constructed directly via
     *  {@link FlowStepDefinition} (bypassing the DSL entirely, as every test in this file and {@code
     *  KernelRunnerParallelAwaitInLoopRestartProofTest} already does). */
    @Test
    void nestedForEachInsideParallelAwaitBodyFailsAtRuntimeWithNamedError() {
        RecordingEventInfrastructure events = new RecordingEventInfrastructure();
        InMemoryFlowInstanceStore store = new InMemoryFlowInstanceStore();
        InMemoryFlowDefinitionProvider provider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "NestedForEachFlow",
                        "Order",
                        List.of(FlowStepDefinition.forEach(
                                LOOP_STEP_NAME,
                                "input.items",
                                "item",
                                List.of(
                                        FlowStepDefinition.awaitEvent(AWAIT_STEP_NAME, "Approved", "approval"),
                                        FlowStepDefinition.forEach(
                                                "inner-loop", "item.lines", "line",
                                                List.of(FlowStepDefinition.returnValue("ret", "line")),
                                                10)
                                ),
                                10,
                                true
                        ))
                ));
        KernelRunner runner = new KernelRunner(
                events, (entityName, payload) -> List.of(), provider, echoingDispatcher(), events, store);

        ExecutionResult result = runner.execute("NestedForEachFlow", Map.of(
                "correlationId", "corr-i3-runtime", "items", List.of("a")));

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getError() != null && result.getError().contains("nests another forEach step"),
                "expected the named nested-forEach runtime error, got: " + result.getError());
    }

    private static CapabilityDispatcher echoingDispatcher() {
        return (call, state) -> {
            if ("identity".equals(call.operation())) {
                return CapabilityResult.success("calc-" + call.input());
            }
            if ("captureInput".equals(call.operation())) {
                return CapabilityResult.success(call.input());
            }
            if ("approvalMarker".equals(call.operation())) {
                return CapabilityResult.success("approved");
            }
            return CapabilityResult.success(null);
        };
    }

    private static EventEnvelope approvalEvent(String correlationId) {
        return new EventEnvelope(
                "evt-" + correlationId,
                "Approved",
                System.currentTimeMillis(),
                Map.of("value", "approved"),
                correlationId,
                "test:approval",
                "IsolationLoopFlow",
                0,
                "default",
                null
        );
    }

    private static InMemoryFlowDefinitionProvider flowProvider() {
        List<FlowStepDefinition> loopSteps = List.of(
                FlowStepDefinition.capabilityCall(
                        "captureLeak", "test-capability", "captureInput", "tally", "leakCheck"),
                FlowStepDefinition.map("copyOuter", "outer", "seenOuter"),
                FlowStepDefinition.capabilityCall(
                        "calc", "test-capability", "identity", "item", "tally"),
                FlowStepDefinition.awaitEvent(AWAIT_STEP_NAME, "Approved", "approval"),
                FlowStepDefinition.returnValue("ret", "tally")
        );
        return new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "IsolationLoopFlow",
                        "Order",
                        List.of(
                                // Seeds "outer" in bare state BEFORE the loop -- vector 3's setup.
                                FlowStepDefinition.map("seedOuter", "input.outerSeed", "outer"),
                                FlowStepDefinition.forEach(
                                        LOOP_STEP_NAME,
                                        "input.items",
                                        "item",
                                        loopSteps,
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

    private static final class RecordingEventInfrastructure implements EventBus, EventStore {
        private final List<EventEnvelope> stored = new CopyOnWriteArrayList<>();

        @Override
        public void publish(EventEnvelope event) {
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
