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
 * LNCH-17: proves the {@code onFailure} compensation contract -- when a later step in a flow
 * terminally fails, declared compensation steps for already-completed earlier steps run in
 * reverse completion order (the saga pattern), and that this holds even across a simulated
 * process crash mid-compensation (same freeze-thread technique as
 * {@link KernelRunnerForEachDurabilityTest}, reused here per the DoD's own suggestion).
 */
class KernelRunnerCompensationTest {

    @Test
    void terminalFailureRunsCompletedStepsCompensationInReverseOrder() {
        List<String> log = new CopyOnWriteArrayList<>();
        CapabilityDispatcher dispatcher = failFinalStepDispatcher(log);
        InMemoryFlowInstanceStore flowInstanceStore = new InMemoryFlowInstanceStore();

        KernelRunner runner = new KernelRunner(
                new RecordingEventInfrastructure(),
                (entityName, payload) -> List.of(),
                flowProviderForOrderFlow(),
                dispatcher,
                new RecordingEventInfrastructure(),
                flowInstanceStore
        );

        ExecutionResult result = runner.execute("PlaceOrder", Map.of("correlationId", "corr-comp-1"));

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertEquals(
                List.of("reserve", "charge", "ship", "refund", "release"),
                log,
                "compensations for the two completed steps must run in reverse order after the failure"
        );

        FlowInstance failed = flowInstanceStore.findByExecutionId(result.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.FAILED_PERMANENT, failed.status());
    }

    @Test
    void crashMidCompensationThenResumeOnFreshRunnerFinishesRemainingCompensationsExactlyOnce()
            throws InterruptedException {
        List<String> log = new CopyOnWriteArrayList<>();
        CapabilityDispatcher dispatcher = failFinalStepDispatcher(log);
        // 2 successful step checkpoints (reserve-inventory, charge-payment) reach the store
        // before ship-order fails; ship-order's own failure writes no checkpoint (it never
        // completes). The 3rd store write is the FIRST compensation checkpoint, written right
        // after charge-payment's compensation (refund-payment) runs -- freeze there, simulating
        // a crash after that one compensating step lands durably but before release-inventory's
        // compensation (reserve-inventory's) runs.
        PausingFlowInstanceStore flowInstanceStore = new PausingFlowInstanceStore(3);

        KernelRunner firstRunner = new KernelRunner(
                new RecordingEventInfrastructure(),
                (entityName, payload) -> List.of(),
                flowProviderForOrderFlow(),
                dispatcher,
                new RecordingEventInfrastructure(),
                flowInstanceStore
        );

        Thread crashedProcessThread = new Thread(
                () -> firstRunner.execute("PlaceOrder", Map.of("correlationId", "corr-comp-crash-1")),
                "simulated-crashed-process-compensation"
        );
        crashedProcessThread.setDaemon(true);
        crashedProcessThread.start();

        assertTrue(
                flowInstanceStore.awaitPausePoint(10, TimeUnit.SECONDS),
                "timed out waiting for the simulated crash point mid-compensation"
        );

        assertEquals(
                List.of("reserve", "charge", "ship", "refund"),
                log,
                "expected exactly the pre-crash steps (including one compensation) to have run once"
        );

        String executionId = flowInstanceStore.onlyExecutionId();
        FlowInstance stored = flowInstanceStore.findByExecutionId(executionId).orElseThrow();
        assertEquals(FlowInstanceStatus.RUNNING, stored.status(), "mid-compensation checkpoints persist as RUNNING");
        assertEquals(Boolean.TRUE, stored.state().get("__npdev_compensating__"));

        KernelRunner secondRunner = new KernelRunner(
                new RecordingEventInfrastructure(),
                (entityName, payload) -> List.of(),
                flowProviderForOrderFlow(),
                dispatcher,
                new RecordingEventInfrastructure(),
                flowInstanceStore
        );

        ExecutionResult resumed = secondRunner.resumeExecution(executionId);

        assertEquals(ExecutionStatus.FAILED, resumed.getStatus());
        assertEquals(
                List.of("reserve", "charge", "ship", "refund", "release"),
                log,
                "release-inventory's compensation must run exactly once after resume, not be skipped or duplicated"
        );

        FlowInstance finalInstance = flowInstanceStore.findByExecutionId(executionId).orElseThrow();
        assertEquals(FlowInstanceStatus.FAILED_PERMANENT, finalInstance.status());
        assertEquals(null, finalInstance.state().get("__npdev_compensating__"), "compensation marker must be cleared");
    }

    private static CapabilityDispatcher failFinalStepDispatcher(List<String> log) {
        return (call, state) -> {
            log.add(call.operation());
            if ("ship".equals(call.operation())) {
                return CapabilityResult.failure(new CapabilityError(
                        "carrier_rejected", "carrier rejected the shipment", CapabilityErrorKind.PERMANENT, Map.of()
                ));
            }
            return CapabilityResult.success(call.input());
        };
    }

    private static InMemoryFlowDefinitionProvider flowProviderForOrderFlow() {
        return new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "PlaceOrder",
                        "Order",
                        List.of(
                                FlowStepDefinition.capabilityCall(
                                        "reserve-inventory", "inventory", "reserve", "input", "reserved"
                                ).withOnFailure(List.of(
                                        FlowStepDefinition.capabilityCall(
                                                "release-inventory", "inventory", "release", "input", "released"
                                        )
                                )),
                                FlowStepDefinition.capabilityCall(
                                        "charge-payment", "payments", "charge", "input", "charged"
                                ).withOnFailure(List.of(
                                        FlowStepDefinition.capabilityCall(
                                                "refund-payment", "payments", "refund", "input", "refunded"
                                        )
                                )),
                                FlowStepDefinition.capabilityCall(
                                        "ship-order", "shipping", "ship", "input", "shipped"
                                )
                        )
                ));
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
