package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMerge;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FLOW-1: the REAL kernel emits the segment tags FlowTraceMerge relies on. A tracer that merges on
 * save (what InProcExecutionTracer and JdbcTraceStore do) must end up with ONE complete trace for a
 * flow that parked and resumed, and for a flow an operator retried -- never just the last segment.
 */
class KernelRunnerTraceMergeTest {

    /** Saves exactly like the production stores: merge the new segment into what is stored. */
    private static final class MergingTracer implements ExecutionTracer {
        final Map<String, FlowTrace> stored = new ConcurrentHashMap<>();
        final List<FlowTrace> segments = new CopyOnWriteArrayList<>();

        @Override
        public void onFlowEnd(FlowTrace flowTrace) {
            segments.add(flowTrace);
            stored.merge(flowTrace.meta().executionId(), flowTrace, FlowTraceMerge::merge);
        }
    }

    @Test
    void parkedThenResumedFlowKeepsAllStepsOriginalStartAndTheWait() throws Exception {
        MergingTracer tracer = new MergingTracer();
        Store store = new Store();
        Events events = new Events();
        InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "AwaitApproval", "Invoice",
                List.of(FlowStepDefinition.capabilityCall("prepare", "docs", "prepare", "input", "prepared"),
                        FlowStepDefinition.awaitEvent("wait-approval", "InvoiceApproved", "$approval"),
                        FlowStepDefinition.returnValue("return-status", "$approval.status"))));
        KernelRunner runner = new KernelRunner(events, (e, p) -> List.of(), flows,
                (call, state) -> CapabilityResult.success(call.input()), tracer, events, store,
                CorrelationOwnershipStore.noop());

        ExecutionResult waiting = runner.execute("AwaitApproval", Map.of("correlationId", "corr-merge-1"));
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());
        FlowTrace parked = tracer.stored.get(waiting.getExecutionId());
        assertEquals(List.of("prepare", "wait-approval"), parked.steps().stream().map(StepTrace::stepName).toList());

        Thread.sleep(60);
        events.append(new EventEnvelope("evt-1", "InvoiceApproved", 1000L, Map.of("status", "APPROVED"),
                "corr-merge-1", "cause", "external", 0, "default", "anonymous"));
        ExecutionResult resumed = runner.resumeExecution(waiting.getExecutionId());
        assertEquals(ExecutionStatus.OK, resumed.getStatus());

        assertEquals(2, tracer.segments.size(), "two segments were saved");
        FlowTrace merged = tracer.stored.get(waiting.getExecutionId());
        assertEquals(List.of("prepare", "wait-approval", "return-status"),
                merged.steps().stream().map(StepTrace::stepName).toList(), "the pre-park step survives the resume");
        assertEquals(StepOutcome.OK, merged.outcome());
        assertEquals(parked.startedAtEpochMs(), merged.startedAtEpochMs());
        StepTrace awaited = merged.steps().get(1);
        assertEquals(StepOutcome.OK, awaited.outcome());
        long waitedMs = ((Number) awaited.info().get("waitedMs")).longValue();
        assertTrue(waitedMs >= 50, "waited about 60ms between park and resume, recorded " + waitedMs);
        assertFalse("WAITING".equals(String.valueOf(awaited.info().get("awaitedEventStatus"))),
                "a completed wait must not keep reading as WAITING");
    }

    @Test
    void aFlowThatParksOnItsVeryFirstStepStillMergesOnResume() throws Exception {
        // Park at step 0: startStepIndex is 0 for BOTH the first run and the resume, so only the
        // "resumed" tag tells the store the second segment continues the first.
        MergingTracer tracer = new MergingTracer();
        Events events = new Events();
        InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "AwaitFirst", "Invoice",
                List.of(FlowStepDefinition.awaitEvent("wait-first", "InvoiceApproved", "$approval"),
                        FlowStepDefinition.returnValue("return-status", "$approval.status"))));
        KernelRunner runner = new KernelRunner(events, (e, p) -> List.of(), flows,
                (call, state) -> CapabilityResult.success(call.input()), tracer, events, new Store(),
                CorrelationOwnershipStore.noop());

        ExecutionResult waiting = runner.execute("AwaitFirst", Map.of("correlationId", "corr-merge-3"));
        long parkedStart = tracer.stored.get(waiting.getExecutionId()).startedAtEpochMs();
        Thread.sleep(40);
        events.append(new EventEnvelope("evt-3", "InvoiceApproved", 1000L, Map.of("status", "APPROVED"),
                "corr-merge-3", "cause", "external", 0, "default", "anonymous"));
        assertEquals(ExecutionStatus.OK, runner.resumeExecution(waiting.getExecutionId()).getStatus());

        FlowTrace merged = tracer.stored.get(waiting.getExecutionId());
        assertEquals(parkedStart, merged.startedAtEpochMs());
        assertEquals(List.of("wait-first", "return-status"), merged.steps().stream().map(StepTrace::stepName).toList());
        assertTrue(((Number) merged.steps().get(0).info().get("waitedMs")).longValue() >= 30);
        assertEquals(Boolean.TRUE, tracer.segments.get(1).meta().tags().get("resumed"));
        assertFalse(tracer.segments.get(0).meta().tags().containsKey("resumed"));
    }

    @Test
    void operatorRetryDoesNotDuplicateTheFailedStep() {
        MergingTracer tracer = new MergingTracer();
        Store store = new Store();
        Events events = new Events();
        AtomicInteger shipAttempts = new AtomicInteger();
        CapabilityDispatcher dispatcher = (call, state) -> {
            if ("ship".equals(call.operation()) && shipAttempts.incrementAndGet() == 1) {
                return CapabilityResult.failure(new CapabilityError(
                        "gateway_down", "gateway unavailable", CapabilityErrorKind.TRANSIENT, Map.of()));
            }
            return CapabilityResult.success(call.input());
        };
        InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "ChargeAndShip", "Order",
                List.of(FlowStepDefinition.capabilityCall("charge-card", "payments", "charge", "input", "charged"),
                        FlowStepDefinition.capabilityCall("ship-order", "shipping", "ship", "input", "shipped"))));
        KernelRunner runner = new KernelRunner(events, (e, p) -> List.of(), flows, dispatcher, tracer, events, store,
                CorrelationOwnershipStore.noop());

        ExecutionResult first = runner.execute("ChargeAndShip", Map.of("correlationId", "corr-merge-2"));
        assertEquals(FlowInstanceStatus.FAILED, store.findByExecutionId(first.getExecutionId()).orElseThrow().status());
        assertEquals(StepOutcome.FAILED, tracer.stored.get(first.getExecutionId()).outcome());

        runner.retryFailedExecution(first.getExecutionId(), ExecutionContext.anonymous()).orElseThrow();

        FlowTrace merged = tracer.stored.get(first.getExecutionId());
        assertEquals(List.of("charge-card", "ship-order"), merged.steps().stream().map(StepTrace::stepName).toList());
        assertEquals(StepOutcome.OK, merged.outcome());
        assertEquals(StepOutcome.OK, merged.steps().get(1).outcome());
        assertEquals(2L, ((Number) merged.steps().get(1).info().get("stepAttempts")).longValue());
        assertFalse(merged.steps().get(1).info().containsKey("waitedMs"));
    }

    private static final class Store implements FlowInstanceStore {
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
