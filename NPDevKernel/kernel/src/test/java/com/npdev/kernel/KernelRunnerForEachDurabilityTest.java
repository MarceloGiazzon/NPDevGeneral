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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LIFT-LOOP-P2: proves durable, crash-and-resume execution of a {@code forEach} flow step.
 * The scenario: a process persists a per-iteration checkpoint to a shared, external
 * {@link FlowInstanceStore} and then dies -- simulated here by freezing the executing thread
 * forever right after a successful checkpoint write, rather than throwing (KernelRunner already
 * treats any thrown RuntimeException from a step, including a store write failure, as a terminal
 * flow failure it marks FAILED and rethrows -- see the {@code catch (RuntimeException)} in
 * {@code executeFlowInstance} -- so a thrown exception models a write failure, not a process
 * crash). Freezing the thread means neither that catch nor the loop's own {@code finally} ever
 * runs, so nothing in the live process gets a chance to react -- exactly like a real crash. A
 * brand-new {@link KernelRunner} -- standing in for a freshly restarted process, with no
 * in-memory state carried over -- then resumes the same execution id from the durable store and
 * must complete exactly the remaining iterations, without reprocessing the ones already
 * committed.
 */
class KernelRunnerForEachDurabilityTest {

    @Test
    void crashMidLoopThenResumeOnFreshRunnerProcessesEachItemExactlyOnce() throws InterruptedException {
        List<String> processedItems = new CopyOnWriteArrayList<>();
        CapabilityDispatcher recordingDispatcher = (call, state) -> {
            processedItems.add(String.valueOf(call.input()));
            return CapabilityResult.success(call.input());
        };

        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        PausingFlowInstanceStore flowInstanceStore = new PausingFlowInstanceStore(2);

        KernelRunner firstRunner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForForEachFlow(),
                recordingDispatcher,
                eventInfrastructure,
                flowInstanceStore
        );

        Map<String, Object> input = Map.of(
                "correlationId", "corr-foreach-durability-1",
                "orders", List.of("o1", "o2", "o3", "o4")
        );

        Thread crashedProcessThread = new Thread(
                () -> firstRunner.execute("ProcessOrders", input),
                "simulated-crashed-process"
        );
        crashedProcessThread.setDaemon(true);
        crashedProcessThread.start();

        assertTrue(
                flowInstanceStore.awaitPausePoint(10, TimeUnit.SECONDS),
                "timed out waiting for the simulated crash point"
        );

        // The "process" is now permanently frozen mid-checkpoint -- the store must already hold
        // the durably-committed snapshot written right before it froze.
        assertEquals(List.of("o1", "o2"), processedItems, "expected exactly the pre-crash items to have run once");

        String executionId = flowInstanceStore.onlyExecutionId();
        FlowInstance stored = flowInstanceStore.findByExecutionId(executionId).orElseThrow();
        assertEquals(FlowInstanceStatus.RUNNING, stored.status());
        assertEquals(0, stored.currentStepIndex(), "forEach occupies a single flat step position");

        // Simulate a full process restart: a brand-new KernelRunner, sharing only the durable
        // FlowInstanceStore (no in-memory state, no reference to firstRunner, its local `state`
        // map, or the frozen thread) resumes the crashed execution.
        KernelRunner secondRunner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForForEachFlow(),
                recordingDispatcher,
                eventInfrastructure,
                flowInstanceStore
        );

        ExecutionResult resumed = secondRunner.resumeExecution(executionId);

        assertEquals(ExecutionStatus.OK, resumed.getStatus());
        assertEquals(
                List.of("o1", "o2", "o3", "o4"),
                processedItems,
                "each item must be processed exactly once across the crash and resume, in order, with no duplicates"
        );

        FlowInstance completed = flowInstanceStore.findByExecutionId(executionId).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, completed.status());
    }

    private static InMemoryFlowDefinitionProvider flowProviderForForEachFlow() {
        return new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "ProcessOrders",
                        "Order",
                        List.of(
                                FlowStepDefinition.forEach(
                                        "process-orders",
                                        "input.orders",
                                        "order",
                                        List.of(
                                                FlowStepDefinition.capabilityCall(
                                                        "process-item",
                                                        "orderProcessor",
                                                        "process",
                                                        "order",
                                                        "processedOrder"
                                                )
                                        ),
                                        10
                                )
                        )
                ));
    }

    /**
     * Wraps an in-memory {@link FlowInstanceStore}. Every {@code update()} call is persisted for
     * real (so durability is genuine, not skipped), but once the configured call count is
     * reached the wrapper freezes the calling thread forever -- modelling a process that dies
     * right after a durable write lands, before it can run any further Java code (including
     * KernelRunner's own failure-handling catch/finally).
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
