package com.npdev.kernel;

import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.events.EventEnvelope;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RUN-3: an {@code onFailure} declared on a step NESTED in a branch then/else body or a forEach body
 * used to be silently ignored -- only top-level steps were compensated. Semantics pinned here:
 * when a step inside a nested body fails, the steps of THAT body that already completed are
 * compensated in reverse order (for forEach: the failing iteration only), and then the failure
 * propagates to the parent level, which compensates its own completed steps as before.
 */
class KernelRunnerNestedCompensationTest {

    private static CapabilityDispatcher dispatcher(List<String> log, String failingOperation, int failOnNthCall) {
        AtomicInteger calls = new AtomicInteger();
        return (call, state) -> {
            log.add(call.operation());
            if (failingOperation.equals(call.operation()) && calls.incrementAndGet() == failOnNthCall) {
                return CapabilityResult.failure(new CapabilityError(
                        "boom", "step failed", CapabilityErrorKind.PERMANENT, Map.of()));
            }
            return CapabilityResult.success(call.input());
        };
    }

    private static FlowStepDefinition step(String name, String operation) {
        return FlowStepDefinition.capabilityCall(name, "svc", operation, "input", name + "Out");
    }

    private static FlowStepDefinition stepWithCompensation(String name, String operation, String compensation) {
        return step(name, operation).withOnFailure(List.of(step("undo-" + name, compensation)));
    }

    private static KernelRunner runner(InMemoryFlowDefinitionProvider provider, CapabilityDispatcher dispatcher,
                                       FlowInstanceStore store) {
        return new KernelRunner(
                new RecordingEventInfrastructure(),
                (entityName, payload) -> List.of(),
                provider,
                dispatcher,
                new RecordingEventInfrastructure(),
                store
        );
    }

    private static InMemoryFlowDefinitionProvider branchFlow() {
        return new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "ShipOrder", "Order",
                List.of(FlowStepDefinition.branch(
                        "route", "true",
                        List.of(
                                stepWithCompensation("book-courier", "book", "cancelBooking"),
                                step("plain-step", "plain"),
                                stepWithCompensation("reserve-slot", "reserve", "releaseSlot"),
                                step("charge", "charge")),
                        List.of()))));
    }

    @Test
    void failureInABranchBodyCompensatesTheBodysCompletedStepsInReverseOrder() {
        List<String> log = new CopyOnWriteArrayList<>();
        InMemoryFlowInstanceStore store = new InMemoryFlowInstanceStore();
        KernelRunner runner = runner(branchFlow(), dispatcher(log, "charge", 1), store);

        ExecutionResult result = runner.execute("ShipOrder", Map.of("correlationId", "corr-nested-1"));

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertEquals(
                List.of("book", "plain", "reserve", "charge", "releaseSlot", "cancelBooking"),
                log,
                "the two completed steps that declared onFailure are compensated, newest first; "
                        + "the failing step and the step without onFailure are not");
        FlowInstance failed = store.findByExecutionId(result.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.FAILED_PERMANENT, failed.status());
        assertEquals(2, failed.state().get(CompensationRunner.COMPENSATED_COUNT_KEY));
        assertNull(failed.state().get(CompensationRunner.COMPENSATION_ACTIVE_KEY), "marker cleared");
    }

    @Test
    void failureOnTheFirstNestedStepCompensatesNothing() {
        List<String> log = new CopyOnWriteArrayList<>();
        InMemoryFlowInstanceStore store = new InMemoryFlowInstanceStore();
        KernelRunner runner = runner(branchFlow(), dispatcher(log, "book", 1), store);

        ExecutionResult result = runner.execute("ShipOrder", Map.of("correlationId", "corr-nested-2"));

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertEquals(List.of("book"), log);
    }

    @Test
    void failureInAForEachBodyCompensatesTheFailingIterationOnly() {
        List<String> log = new CopyOnWriteArrayList<>();
        InMemoryFlowInstanceStore store = new InMemoryFlowInstanceStore();
        InMemoryFlowDefinitionProvider provider = new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "ProcessLines", "Order",
                List.of(FlowStepDefinition.forEach(
                        "lines", "$input.lines", "line",
                        List.of(stepWithCompensation("reserve-line", "reserve", "releaseLine"), step("ship-line", "ship")),
                        null))));
        // the 2nd ship call fails: iteration 0 completes untouched, iteration 1 is compensated
        KernelRunner runner = runner(provider, dispatcher(log, "ship", 2), store);

        ExecutionResult result = runner.execute("ProcessLines",
                Map.of("correlationId", "corr-nested-3", "lines", List.of("a", "b", "c")));

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(List.of("reserve", "ship", "reserve", "ship", "releaseLine"), log,
                "only the failing iteration's completed step is compensated; earlier iterations stay done");
    }

    @Test
    void flowsWithoutAnyOnFailureWriteNothingExtra() {
        List<String> log = new CopyOnWriteArrayList<>();
        InMemoryFlowInstanceStore store = new InMemoryFlowInstanceStore();
        InMemoryFlowDefinitionProvider provider = new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "Plain", "Order",
                List.of(FlowStepDefinition.branch("route", "true",
                        List.of(step("a", "a"), step("b", "b")), List.of()))));
        KernelRunner runner = runner(provider, dispatcher(log, "b", 1), store);

        ExecutionResult result = runner.execute("Plain", Map.of("correlationId", "corr-nested-4"));

        FlowInstance failed = store.findByExecutionId(result.getExecutionId()).orElseThrow();
        assertNull(failed.state().get(CompensationRunner.COMPENSATED_COUNT_KEY));
        assertNull(failed.state().get(CompensationRunner.NESTED_PLAN_KEY));
        assertNull(failed.state().get(CompensationRunner.COMPENSATION_ACTIVE_KEY));
    }

    @Test
    void crashMidNestedCompensationThenResumeRunsEachOnFailureExactlyOnce() throws InterruptedException {
        List<String> log = new CopyOnWriteArrayList<>();
        CapabilityDispatcher dispatcher = dispatcher(log, "charge", 1);
        // Durable writes before the freeze: book-courier done (1), plain-step done (2), reserve-slot
        // done (3); charge fails and writes nothing. The 4th write is the FIRST compensation
        // checkpoint, right after releaseSlot ran -- freeze there: releaseSlot is durable,
        // cancelBooking has not run.
        PausingFlowInstanceStore store = new PausingFlowInstanceStore(4);
        KernelRunner firstRunner = runner(branchFlow(), dispatcher, store);

        Thread crashed = new Thread(
                () -> firstRunner.execute("ShipOrder", Map.of("correlationId", "corr-nested-crash")),
                "simulated-crashed-process-nested-compensation");
        crashed.setDaemon(true);
        crashed.start();

        assertTrue(store.awaitPausePoint(10, TimeUnit.SECONDS), "timed out waiting for the simulated crash point");
        assertEquals(List.of("book", "plain", "reserve", "charge", "releaseSlot"), log);
        String executionId = store.onlyExecutionId();
        assertEquals(FlowInstanceStatus.RUNNING, store.findByExecutionId(executionId).orElseThrow().status());

        KernelRunner secondRunner = runner(branchFlow(), dispatcher, store);
        ExecutionResult resumed = secondRunner.resumeExecution(executionId);

        assertEquals(ExecutionStatus.FAILED, resumed.getStatus());
        assertEquals(List.of("book", "plain", "reserve", "charge", "releaseSlot", "cancelBooking"), log,
                "cancelBooking runs exactly once after the resume; releaseSlot is not repeated");
        FlowInstance finalInstance = store.findByExecutionId(executionId).orElseThrow();
        assertEquals(FlowInstanceStatus.FAILED_PERMANENT, finalInstance.status());
        assertEquals(2, finalInstance.state().get(CompensationRunner.COMPENSATED_COUNT_KEY));
        assertNull(finalInstance.state().get(CompensationRunner.NESTED_PLAN_KEY), "plan cleared when settled");
    }

    /** Straightforward in-memory {@link FlowInstanceStore}, no pausing. */
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
     * Same technique as {@link KernelRunnerForEachDurabilityTest}'s wrapper: every {@code
     * update()} persists for real, and once the configured call count is reached the wrapper
     * freezes the calling thread forever, modelling a process that dies right after a durable
     * checkpoint write lands -- here, mid-compensation rather than mid-loop.
     */
    private static final class PausingFlowInstanceStore implements FlowInstanceStore {
        private final Map<String, FlowInstance> byExecutionId = new ConcurrentHashMap<>();
        private final int pauseOnUpdateCall;
        private final CountDownLatch reachedPausePoint = new CountDownLatch(1);
        private int updateCalls = 0;

        PausingFlowInstanceStore(int pauseOnUpdateCall) {
            this.pauseOnUpdateCall = pauseOnUpdateCall;
        }

        boolean awaitPausePoint(long timeout, TimeUnit unit) throws InterruptedException {
            return reachedPausePoint.await(timeout, unit);
        }

        String onlyExecutionId() {
            return byExecutionId.keySet().stream().findFirst().orElseThrow();
        }

        @Override
        public void save(FlowInstance instance) {
            byExecutionId.put(instance.executionId(), instance);
        }

        @Override
        public void update(FlowInstance instance) {
            byExecutionId.put(instance.executionId(), instance);
            updateCalls++;
            if (updateCalls == pauseOnUpdateCall) {
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
        public Optional<EventEnvelope> findFirst(String eventName, String correlationId) {
            return List.copyOf(stored).stream()
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
