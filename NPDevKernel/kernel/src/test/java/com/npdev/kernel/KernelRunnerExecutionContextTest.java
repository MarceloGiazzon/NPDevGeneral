package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelRunnerExecutionContextTest {

    @Test
    void executePropagatesExecutionContextToInstanceEventsAndTrace() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();
        RecordingTracer tracer = new RecordingTracer();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(
                                FlowStepDefinition.emitEvent("emit-created", "UserCreated", "$input"),
                                FlowStepDefinition.returnValue("return-user", "$input")
                        )
                )),
                (call, state) -> CapabilityResult.success(null),
                tracer,
                eventInfrastructure,
                flowInstanceStore
        );

        ExecutionResult result = runner.execute(
                "CreateUser",
                Map.of("email", "a@b.com", "correlationId", "corr-ctx-1"),
                ExecutionContext.of("tenant-1", "actor-1")
        );

        assertEquals(ExecutionStatus.OK, result.getStatus());
        FlowInstance instance = flowInstanceStore.findByExecutionId(result.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, instance.status());
        assertEquals("tenant-1", instance.tenantId());
        assertEquals("actor-1", instance.actorId());

        assertEquals(1, eventInfrastructure.stored.size());
        EventEnvelope emitted = eventInfrastructure.stored.get(0);
        assertEquals("tenant-1", emitted.tenantId());
        assertEquals("actor-1", emitted.actorId());

        assertNotNull(tracer.flowTrace);
        assertEquals("tenant-1", tracer.flowTrace.meta().tenantId());
        assertEquals("actor-1", tracer.flowTrace.meta().actorId());
    }

    @Test
    void publishExternalEventCarriesExecutionContext() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of()
        );

        EventEnvelope envelope = runner.publishExternalEvent(
                "UserApproved",
                Map.of("userId", "u-1"),
                "corr-external-1",
                "cause-external-1",
                ExecutionContext.of("tenant-ext", "actor-ext")
        );

        assertEquals("tenant-ext", envelope.tenantId());
        assertEquals("actor-ext", envelope.actorId());
        assertTrue(eventInfrastructure.published.stream().anyMatch(event -> "UserApproved".equals(event.eventName())));
    }

    private static final class RecordingTracer implements ExecutionTracer {
        private FlowTrace flowTrace;

        @Override
        public void onFlowStart(FlowTraceMeta meta, long startedAtEpochMs) {
            // no-op
        }

        @Override
        public void onFlowEnd(FlowTrace flowTrace) {
            this.flowTrace = flowTrace;
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
            return read(eventName, correlationId).stream()
                    .sorted(Comparator.comparingLong(EventEnvelope::timestampEpochMs).thenComparing(EventEnvelope::eventId))
                    .findFirst();
        }

        @Override
        public List<EventEnvelope> readByCorrelation(String correlationId) {
            if (correlationId == null || correlationId.isBlank()) {
                return List.of();
            }
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
            if (eventName == null || eventName.isBlank()) {
                return List.of();
            }
            List<EventEnvelope> out = new ArrayList<>();
            for (EventEnvelope event : stored) {
                if (eventName.equals(event.eventName())) {
                    out.add(event);
                }
            }
            return List.copyOf(out);
        }
    }

    private static final class FlowInstanceStoreStub implements FlowInstanceStore {
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
            return byExecutionId.values().stream()
                    .filter(instance -> instance.status() == FlowInstanceStatus.WAITING_EVENT)
                    .filter(instance -> correlationId.equals(instance.correlationId()))
                    .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).thenComparing(FlowInstance::executionId))
                    .toList();
        }

        @Override
        public List<FlowInstance> findWaitingByEvent(String eventName) {
            return byExecutionId.values().stream()
                    .filter(instance -> instance.status() == FlowInstanceStatus.WAITING_EVENT)
                    .filter(instance -> eventName.equals(instance.waitingForEventName()))
                    .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).thenComparing(FlowInstance::executionId))
                    .toList();
        }

        @Override
        public List<FlowInstance> findAllWaiting(int limit) {
            int effectiveLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
            List<FlowInstance> waiting = byExecutionId.values().stream()
                    .filter(instance -> instance.status() == FlowInstanceStatus.WAITING_EVENT)
                    .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).thenComparing(FlowInstance::executionId))
                    .toList();
            if (waiting.size() <= effectiveLimit) {
                return waiting;
            }
            return waiting.subList(0, effectiveLimit);
        }

        @Override
        public List<FlowInstance> findWaitingEligibleToResume(String tenantId, long nowEpochMs, int limit) {
            int effectiveLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
            return byExecutionId.values().stream()
                    .filter(instance -> instance.status() == FlowInstanceStatus.WAITING_EVENT)
                    .filter(instance -> tenantId == null || tenantId.isBlank() || tenantId.equals(instance.tenantId()))
                    .filter(instance -> instance.isResumeEligible(nowEpochMs))
                    .sorted(Comparator
                            .comparingLong((FlowInstance instance) -> instance.nextEligibleResumeAtEpochMs() == null
                                    ? 0L
                                    : instance.nextEligibleResumeAtEpochMs())
                            .thenComparing(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).reversed())
                            .thenComparing(FlowInstance::executionId))
                    .limit(effectiveLimit)
                    .toList();
        }

        @Override
        public List<FlowInstance> findStaleWaiting(String tenantId, long olderThanEpochMs, int limit, int offset) {
            int effectiveLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
            int effectiveOffset = Math.max(offset, 0);
            return byExecutionId.values().stream()
                    .filter(instance -> instance.status() == FlowInstanceStatus.WAITING_EVENT)
                    .filter(instance -> tenantId == null || tenantId.isBlank() || tenantId.equals(instance.tenantId()))
                    .filter(instance -> (instance.lastProgressAtEpochMs() == null ? 0L : instance.lastProgressAtEpochMs())
                            <= olderThanEpochMs)
                    .sorted(Comparator.comparingLong(instance ->
                            instance.lastProgressAtEpochMs() == null ? 0L : instance.lastProgressAtEpochMs()))
                    .skip(effectiveOffset)
                    .limit(effectiveLimit)
                    .toList();
        }
    }
}
