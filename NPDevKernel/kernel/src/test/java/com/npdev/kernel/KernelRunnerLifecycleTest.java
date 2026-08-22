package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.errors.ErrorKind;
import com.npdev.kernel.errors.FailureCodes;
import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.kernel.capability.CapabilityPolicyOverrides;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowDefinitionProvider;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdProvider;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.JsonCodec;
import com.npdev.kernel.ports.MetricsSink;
import com.npdev.kernel.ports.SchemaValidator;
import com.npdev.kernel.trace.FlowTrace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelRunnerLifecycleTest {

    @Test
    void executeWaitingEventPersistsFlowInstanceSnapshot() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );

        ExecutionResult result = runner.execute("AwaitApproval", Map.of("correlationId", "corr-lifecycle-1"));

        assertEquals(ExecutionStatus.WAITING_EVENT, result.getStatus());
        assertNotNull(result.getExecutionId());
        FlowInstance stored = flowInstanceStore.findByExecutionId(result.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.WAITING_EVENT, stored.status());
        assertEquals("InvoiceApproved", stored.waitingForEventName());
        assertEquals(0, stored.currentStepIndex());
    }

    @Test
    void manualResumeContinuesFromStoredWaitingSnapshot() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );

        ExecutionResult waiting = runner.execute("AwaitApproval", Map.of("correlationId", "corr-lifecycle-2"));
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());

        eventInfrastructure.append(new EventEnvelope(
                "evt-approved-1",
                "InvoiceApproved",
                1000L,
                Map.of("status", "APPROVED"),
                "corr-lifecycle-2",
                "cause-manual",
                "external",
                0,
                "default",
                "anonymous"
        ));

        ExecutionResult resumed = runner.resumeExecution(waiting.getExecutionId());
        assertEquals(ExecutionStatus.OK, resumed.getStatus());
        assertEquals("APPROVED", resumed.getOutput());

        FlowInstance completed = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, completed.status());
        assertTrue(completed.currentStepIndex() >= 2);
    }

    @Test
    void startFlowAndResumeFlowCompleteAwaitingExecution() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();
        InMemoryIdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();

        KernelRunner runner = newLifecycleRunner(
                eventInfrastructure,
                flowProviderForAwaitFlow(),
                flowInstanceStore,
                idempotencyStore,
                ExecutionTracer.NOOP,
                IdProvider.uuid()
        );

        ExecutionResult waiting = runner.startFlow(
                "AwaitApproval",
                Map.of("correlationId", "corr-phase5-start"),
                ExecutionContext.anonymous()
        );
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());

        EventEnvelope approvalEvent = new EventEnvelope(
                "evt-phase5-start-1",
                "InvoiceApproved",
                1_000L,
                Map.of("status", "APPROVED"),
                "corr-phase5-start",
                "cause-phase5-start",
                "external",
                0,
                "default",
                "anonymous"
        );
        eventInfrastructure.append(approvalEvent);
        FlowEngine.ResumeOutcome outcome = runner.resumeFlow("corr-phase5-start", approvalEvent);

        assertEquals(1, outcome.matchedWaiters());
        assertEquals(1, outcome.resumedWaiters());
        assertTrue(outcome.resumedExecutionIds().contains(waiting.getExecutionId()));

        FlowInstance completed = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, completed.status());
        assertEquals("APPROVED", completed.state().get("last"));
    }

    @Test
    void mismatchedEventIsNoopAndExecutionRemainsWaiting() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();
        InMemoryIdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();

        KernelRunner runner = newLifecycleRunner(
                eventInfrastructure,
                flowProviderForAwaitFlowWithPayloadMatch(),
                flowInstanceStore,
                idempotencyStore,
                ExecutionTracer.NOOP,
                IdProvider.uuid()
        );

        ExecutionResult waiting = runner.startFlow(
                "AwaitApprovalWithPayloadMatch",
                Map.of(
                        "correlationId", "corr-phase5-mismatch",
                        "expectedReceipt", "receipt-expected"
                ),
                ExecutionContext.anonymous()
        );
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());

        FlowInstance before = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertTrue(before.state().containsKey("_npdev.await"));

        EventEnvelope mismatched = new EventEnvelope(
                "evt-phase5-mismatch-1",
                "InvoiceApproved",
                1_500L,
                Map.of("receipt", "receipt-other", "status", "IGNORED"),
                "corr-phase5-mismatch",
                "cause-phase5-mismatch",
                "external",
                0,
                "default",
                "anonymous"
        );
        eventInfrastructure.append(mismatched);
        FlowEngine.ResumeOutcome outcome = runner.resumeFlow("corr-phase5-mismatch", mismatched);

        assertEquals(0, outcome.matchedWaiters());
        assertEquals(0, outcome.resumedWaiters());
        FlowInstance after = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.WAITING_EVENT, after.status());
        assertEquals(before.currentStepIndex(), after.currentStepIndex());
        assertEquals(0, after.resumeAttemptCount());
    }

    @Test
    void duplicateEventDeliveryDoesNotAdvanceTwice() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();
        InMemoryIdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();

        KernelRunner runner = newLifecycleRunner(
                eventInfrastructure,
                flowProviderForDoubleAwaitFlow(),
                flowInstanceStore,
                idempotencyStore,
                ExecutionTracer.NOOP,
                IdProvider.uuid()
        );

        ExecutionResult waiting = runner.startFlow(
                "AwaitApprovalTwice",
                Map.of("correlationId", "corr-phase5-dup"),
                ExecutionContext.anonymous()
        );
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());

        EventEnvelope firstDelivery = new EventEnvelope(
                "evt-phase5-dup-1",
                "InvoiceApproved",
                2_000L,
                Map.of("status", "FIRST"),
                "corr-phase5-dup",
                "cause-phase5-dup-1",
                "external",
                0,
                "default",
                "anonymous"
        );
        eventInfrastructure.append(firstDelivery);
        FlowEngine.ResumeOutcome firstOutcome = runner.resumeFlow("corr-phase5-dup", firstDelivery);
        assertEquals(1, firstOutcome.matchedWaiters());
        assertEquals(0, firstOutcome.resumedWaiters());

        FlowInstance afterFirst = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.WAITING_EVENT, afterFirst.status());
        int stepAfterFirst = afterFirst.currentStepIndex();

        EventEnvelope duplicateDelivery = new EventEnvelope(
                "evt-phase5-dup-1",
                "InvoiceApproved",
                2_100L,
                Map.of("status", "FIRST-DUPLICATE"),
                "corr-phase5-dup",
                "cause-phase5-dup-1-duplicate",
                "external",
                0,
                "default",
                "anonymous"
        );
        eventInfrastructure.append(duplicateDelivery);
        FlowEngine.ResumeOutcome duplicateOutcome = runner.resumeFlow("corr-phase5-dup", duplicateDelivery);

        assertEquals(0, duplicateOutcome.matchedWaiters());
        assertEquals(0, duplicateOutcome.resumedWaiters());
        FlowInstance afterDuplicate = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.WAITING_EVENT, afterDuplicate.status());
        assertEquals(stepAfterFirst, afterDuplicate.currentStepIndex());

        EventEnvelope secondUniqueDelivery = new EventEnvelope(
                "evt-phase5-dup-2",
                "InvoiceApproved",
                2_200L,
                Map.of("status", "SECOND"),
                "corr-phase5-dup",
                "cause-phase5-dup-2",
                "external",
                0,
                "default",
                "anonymous"
        );
        eventInfrastructure.append(secondUniqueDelivery);
        FlowEngine.ResumeOutcome secondOutcome = runner.resumeFlow("corr-phase5-dup", secondUniqueDelivery);
        assertEquals(1, secondOutcome.matchedWaiters());
        assertEquals(1, secondOutcome.resumedWaiters());

        FlowInstance completed = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, completed.status());
        assertEquals("SECOND", completed.state().get("last"));
    }

    @Test
    void deterministicEventSequenceProducesSameFinalStateAndTraceJson() {
        DeterminismSnapshot first = runDeterministicDoubleAwaitSequence();
        DeterminismSnapshot second = runDeterministicDoubleAwaitSequence();

        assertEquals(first.finalState(), second.finalState());
        assertEquals(first.traceJson(), second.traceJson());
        assertFalse(first.traceJson().isBlank());
    }

    @Test
    void persistedEventTriggersAutomaticResumeUsingPublishExternalEvent() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );

        ExecutionResult waiting = runner.execute("AwaitApproval", Map.of("correlationId", "corr-lifecycle-3"));
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());

        runner.publishExternalEvent(
                "InvoiceApproved",
                Map.of("status", "AUTO"),
                "corr-lifecycle-3",
                "cause-auto"
        );

        FlowInstance completed = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, completed.status());
        assertTrue(eventInfrastructure.published.stream().anyMatch(event -> "InvoiceApproved".equals(event.eventName())));
    }

    @Test
    void resumeRemainsPossibleWithNewKernelRunnerUsingSameStores() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner firstRunner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );
        ExecutionResult waiting = firstRunner.execute("AwaitApproval", Map.of("correlationId", "corr-lifecycle-4"));
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());

        eventInfrastructure.append(new EventEnvelope(
                "evt-approved-2",
                "InvoiceApproved",
                2000L,
                Map.of("status", "RESTART"),
                "corr-lifecycle-4",
                "cause-restart",
                "external",
                0,
                "default",
                "anonymous"
        ));

        KernelRunner secondRunner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );

        ExecutionResult resumed = secondRunner.resumeExecution(waiting.getExecutionId());
        assertEquals(ExecutionStatus.OK, resumed.getStatus());
        assertEquals("RESTART", resumed.getOutput());
    }

    @Test
    void resumeAllWaitingExecutionsResumesOnlyInstancesWithMatchingPersistedEvent() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );

        ExecutionResult waitingA = runner.execute("AwaitApproval", Map.of("correlationId", "corr-lifecycle-5a"));
        ExecutionResult waitingB = runner.execute("AwaitApproval", Map.of("correlationId", "corr-lifecycle-5b"));
        assertEquals(ExecutionStatus.WAITING_EVENT, waitingA.getStatus());
        assertEquals(ExecutionStatus.WAITING_EVENT, waitingB.getStatus());

        eventInfrastructure.append(new EventEnvelope(
                "evt-approved-5",
                "InvoiceApproved",
                2500L,
                Map.of("status", "RESUMED"),
                "corr-lifecycle-5a",
                "cause-resume-scan",
                "external",
                0,
                "default",
                "anonymous"
        ));

        int resumed = runner.resumeAllWaitingExecutions(100);
        assertEquals(1, resumed);

        FlowInstance resumedInstance = flowInstanceStore.findByExecutionId(waitingA.getExecutionId()).orElseThrow();
        FlowInstance stillWaitingInstance = flowInstanceStore.findByExecutionId(waitingB.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, resumedInstance.status());
        assertEquals(FlowInstanceStatus.WAITING_EVENT, stillWaitingInstance.status());
    }

    /**
     * R2.4's back-compat guard, at the level that actually matters: a {@code delaySeconds: 0}
     * scheduleEvent still wakes a parked {@code AWAIT_EVENT} instance <b>inside the same
     * {@code execute()} call</b>. Routing zero-delay through the durable table would leave the
     * waiter WAITING_EVENT here until the next drain tick, and this assertion -- read immediately
     * after execute returns, with no sweep, no sleep and no poll -- is what would catch it.
     */
    @Test
    void zeroDelayScheduleEventResumesAWaitingInstanceSynchronously() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();
        CountingDeferredEventScheduler scheduler = new CountingDeferredEventScheduler();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitAndScheduleFlows(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        ).withDeferredEventScheduler(scheduler);

        ExecutionResult waiting = runner.execute("AwaitApproval", Map.of("correlationId", "corr-r24-zero"));
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());

        ExecutionResult scheduled = runner.execute(
                "ApproveNow",
                Map.of("correlationId", "corr-r24-zero", "status", "APPROVED")
        );

        assertEquals(ExecutionStatus.OK, scheduled.getStatus());
        assertEquals(0, scheduler.calls.get(), "delaySeconds=0 must not reach the durable scheduler");
        FlowInstance resumedInstance = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, resumedInstance.status());
        assertEquals("APPROVED", resumedInstance.state().get("last"));
    }

    /** R2.4's other half, asserted against the same harness so the pair reads as one contract. */
    @Test
    void delayedScheduleEventLeavesTheWaiterParkedAndOnlyWritesADurableRow() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();
        CountingDeferredEventScheduler scheduler = new CountingDeferredEventScheduler();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitAndScheduleFlows(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        ).withDeferredEventScheduler(scheduler);

        ExecutionResult waiting = runner.execute("AwaitApproval", Map.of("correlationId", "corr-r24-later"));
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());

        ExecutionResult scheduled = runner.execute(
                "ApproveLater",
                Map.of("correlationId", "corr-r24-later", "status", "APPROVED")
        );

        assertEquals(ExecutionStatus.OK, scheduled.getStatus());
        assertEquals(1, scheduler.calls.get());
        FlowInstance stillWaiting = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.WAITING_EVENT, stillWaiting.status());

        // And no sweep can find it either: the delayed envelope was never appended, so there is
        // nothing in the event store for findAwaitedEvent to resume on before the row comes due.
        assertEquals(0, runner.resumeAllWaitingExecutions(100));
        assertEquals(FlowInstanceStatus.WAITING_EVENT,
                flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow().status());
    }

    @Test
    void awaitEventUsesTenantScopedLookupWhenCorrelationCollidesAcrossTenants() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );

        eventInfrastructure.append(new EventEnvelope(
                "evt-tenant-b",
                "InvoiceApproved",
                1000L,
                Map.of("status", "TENANT_B"),
                "corr-shared-tenant",
                "cause-b",
                "external",
                0,
                "tenant-b",
                "actor-b"
        ));
        eventInfrastructure.append(new EventEnvelope(
                "evt-tenant-a",
                "InvoiceApproved",
                2000L,
                Map.of("status", "TENANT_A"),
                "corr-shared-tenant",
                "cause-a",
                "external",
                0,
                "tenant-a",
                "actor-a"
        ));

        ExecutionResult result = runner.execute(
                "AwaitApproval",
                Map.of("correlationId", "corr-shared-tenant"),
                ExecutionContext.of("tenant-a", "actor-a")
        );

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals("TENANT_A", result.getOutput());
    }

    @Test
    void resumeAllWaitingExecutionsUsesTenantScopedEventLookup() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );

        ExecutionResult waitingTenantA = runner.execute(
                "AwaitApproval",
                Map.of("correlationId", "corr-shared-resume"),
                ExecutionContext.of("tenant-a", "actor-a")
        );
        ExecutionResult waitingTenantB = runner.execute(
                "AwaitApproval",
                Map.of("correlationId", "corr-shared-resume"),
                ExecutionContext.of("tenant-b", "actor-b")
        );
        assertEquals(ExecutionStatus.WAITING_EVENT, waitingTenantA.getStatus());
        assertEquals(ExecutionStatus.WAITING_EVENT, waitingTenantB.getStatus());

        eventInfrastructure.append(new EventEnvelope(
                "evt-tenant-a-only",
                "InvoiceApproved",
                3000L,
                Map.of("status", "ONLY_A"),
                "corr-shared-resume",
                "cause-a",
                "external",
                0,
                "tenant-a",
                "actor-a"
        ));

        int resumed = runner.resumeAllWaitingExecutions(100);
        assertEquals(1, resumed);

        FlowInstance tenantAInstance = flowInstanceStore.findByExecutionId(waitingTenantA.getExecutionId()).orElseThrow();
        FlowInstance tenantBInstance = flowInstanceStore.findByExecutionId(waitingTenantB.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, tenantAInstance.status());
        assertEquals(FlowInstanceStatus.WAITING_EVENT, tenantBInstance.status());
    }

    @Test
    void resumeAllWaitingExecutionsAppliesBackoffWhenEventIsMissing() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );

        ExecutionResult waiting = runner.execute("AwaitApproval", Map.of("correlationId", "corr-backoff-1"));
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());

        int resumed = runner.resumeAllWaitingExecutions(100);
        assertEquals(0, resumed);

        FlowInstance after = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.WAITING_EVENT, after.status());
        assertEquals(1, after.resumeAttemptCount());
        assertEquals("missing_event", after.lastResumeErrorCode());
        assertTrue(after.nextEligibleResumeAtEpochMs() != null && after.nextEligibleResumeAtEpochMs() > after.updatedAtEpochMs());
    }

    /**
     * R2.1: this test used to seed the near-cap instance with {@code missing_event} and assert
     * STUCK -- it encoded the very defect R2.1 removes (a quiet wait counting toward the cap), so
     * it now drives the cap through the path the cap actually exists for: a resume that keeps
     * THROWING. That path is unchanged by R2.1 and this is its regression guard.
     */
    @Test
    void resumeAllWaitingExecutionsMarksInstanceStuckWhenResumeKeepsThrowing() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = newRunnerWithFailingResume(eventInfrastructure, flowInstanceStore);

        flowInstanceStore.save(waitingInstanceAtAttempt(
                "exec-cap-1", "corr-cap-1", 19, "exception:IllegalStateException"));
        eventInfrastructure.append(awaitedApprovalEvent("evt-cap-1", "corr-cap-1"));

        int resumed = runner.resumeAllWaitingExecutions(100);
        assertEquals(0, resumed);

        FlowInstance after = flowInstanceStore.findByExecutionId("exec-cap-1").orElseThrow();
        assertEquals(FlowInstanceStatus.STUCK, after.status());
        assertEquals(20, after.resumeAttemptCount());
        assertEquals("resume_attempt_cap", after.lastResumeErrorCode());
        assertEquals("resume_attempt_cap", after.lastErrorCode());
        assertTrue(eventInfrastructure.stored.stream()
                .anyMatch(event -> "ExecutionStuck".equals(event.eventName())));
    }

    /**
     * R2.2, the whole point of the operation: a STUCK execution is a dead end -- the sweep only
     * claims WAITING_EVENT rows and {@code resumeExecution} refuses anything else -- so before
     * {@code unstickExecution} the only recovery was an UPDATE by hand. This drives the real
     * sequence: keep-throwing resume to STUCK, fix the fault, un-stick, and let the ORDINARY sweep
     * carry it to completion against the event that was sitting in the store the whole time.
     */
    @Test
    void unstickExecutionReturnsStuckInstanceToTheSweepAndItCompletes() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner faulting = newRunnerWithFailingResume(eventInfrastructure, flowInstanceStore);
        flowInstanceStore.save(waitingInstanceAtAttempt(
                "exec-unstick-1", "corr-unstick-1", 19, "exception:IllegalStateException"));
        eventInfrastructure.append(awaitedApprovalEvent("evt-unstick-1", "corr-unstick-1"));
        assertEquals(0, faulting.resumeAllWaitingExecutions(100));
        assertEquals(
                FlowInstanceStatus.STUCK,
                flowInstanceStore.findByExecutionId("exec-unstick-1").orElseThrow().status(),
                "precondition: the instance must actually be stuck"
        );

        // The operator's claim that un-sticking encodes: the underlying fault is fixed. Same
        // durable stores, a runner whose flow definitions now resolve.
        KernelRunner recovered = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );

        FlowInstance unstuck = recovered.unstickExecution("exec-unstick-1").orElseThrow();
        assertEquals(FlowInstanceStatus.WAITING_EVENT, unstuck.status());
        assertEquals(0, unstuck.resumeAttemptCount(), "the next fault must get a full retry budget");
        assertNull(unstuck.nextEligibleResumeAtEpochMs());
        assertTrue(
                unstuck.isResumeEligible(System.currentTimeMillis()),
                "an operator who just un-stuck an instance must not then wait out a 300s backoff"
        );
        assertNull(unstuck.failedAtEpochMs(), "a live waiter must not report a failure instant");
        assertNull(unstuck.lastErrorCode());
        assertNull(unstuck.lastErrorKind());
        assertEquals(
                "resume_attempt_cap",
                unstuck.lastResumeErrorCode(),
                "the resume pair is the only surviving evidence of why it got stuck"
        );
        assertEquals(unstuck, flowInstanceStore.findByExecutionId("exec-unstick-1").orElseThrow());

        assertEquals(1, recovered.resumeAllWaitingExecutions(100));
        assertEquals(
                FlowInstanceStatus.COMPLETED,
                flowInstanceStore.findByExecutionId("exec-unstick-1").orElseThrow().status()
        );
    }

    /**
     * R2.2: the two refusals. A missing execution is an empty Optional (the REST surface's 404); a
     * wrong-status one throws (its 409). The second case matters most for a COMPLETED instance --
     * silently rewinding one to WAITING_EVENT would re-run its remaining steps.
     */
    @Test
    void unstickExecutionRefusesUnknownAndNotStuckExecutions() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );

        assertTrue(runner.unstickExecution("exec-does-not-exist").isEmpty());
        assertTrue(runner.unstickExecution("  ").isEmpty());

        ExecutionResult waiting = runner.execute("AwaitApproval", Map.of("correlationId", "corr-not-stuck-1"));
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());
        assertThrows(IllegalStateException.class, () -> runner.unstickExecution(waiting.getExecutionId()));

        runner.publishExternalEvent(
                "InvoiceApproved", Map.of("status", "APPROVED"), "corr-not-stuck-1", "cause-not-stuck");
        FlowInstance completed = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, completed.status());
        assertThrows(IllegalStateException.class, () -> runner.unstickExecution(waiting.getExecutionId()));
    }

    /**
     * R2.2: STUCK is also reachable from RUNNING ({@code resolveFailureTerminalStatus}), and such a
     * row has no awaited event -- WAITING_EVENT is a status it cannot legally hold, so un-stick has
     * to refuse rather than let the record's own constructor throw an opaque "waitingForEventName
     * must be non-blank" from inside a persistence call.
     */
    @Test
    void unstickExecutionRefusesStuckInstanceThatWasNeverWaitingOnAnEvent() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );

        FlowInstance stuckWithoutEvent = new FlowInstance(
                "exec-no-event-2",
                "AwaitApproval",
                "corr-no-event-2",
                "default",
                "anonymous",
                0,
                FlowInstanceStatus.STUCK,
                Map.of(),
                null,
                1000L,
                2000L
        );
        flowInstanceStore.save(stuckWithoutEvent);

        assertThrows(IllegalStateException.class, () -> runner.unstickExecution("exec-no-event-2"));
        assertEquals(
                FlowInstanceStatus.STUCK,
                flowInstanceStore.findByExecutionId("exec-no-event-2").orElseThrow().status(),
                "a refused un-stick must not have written anything"
        );
    }

    /**
     * R2.1 headline behaviour: a flow that is merely WAITING_EVENT with nothing to resume ON must
     * never be driven to STUCK. Before R2.1 the quiet branches shared the attempt cap with real
     * failures, and the delay ladder (5s doubling, capped at 300s) summed to 4_515s across 20
     * misses -- so ~75 minutes of quiet permanently killed a flow that docs/FLOWS.md says may wait
     * for days or weeks, and no later event could revive it.
     */
    @Test
    void resumeAllWaitingExecutionsNeverMarksQuietWaitStuck() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );

        // Walk the instance straight THROUGH the old cap of 20 and well past it. Each iteration
        // re-seeds the row at the next attempt count with a long-past nextEligibleResumeAtEpochMs,
        // which is exactly how the row looks once the sweep interval has elapsed in a real
        // deployment; there is no injectable clock in this kernel (nowEpochMillis() is a plain
        // System.currentTimeMillis()), so re-seeding is how a test drives N sweeps in one run.
        for (int seededAttempts = 18; seededAttempts <= 30; seededAttempts++) {
            flowInstanceStore.save(waitingInstanceAtAttempt(
                    "exec-quiet-1", "corr-quiet-1", seededAttempts, "missing_event"));

            assertEquals(0, runner.resumeAllWaitingExecutions(100));

            FlowInstance after = flowInstanceStore.findByExecutionId("exec-quiet-1").orElseThrow();
            assertEquals(
                    FlowInstanceStatus.WAITING_EVENT,
                    after.status(),
                    "a wait with no event yet must stay resumable at attempt " + (seededAttempts + 1)
            );
            assertEquals(seededAttempts + 1, after.resumeAttemptCount());
            assertEquals("missing_event", after.lastResumeErrorCode());
            assertNotNull(after.nextEligibleResumeAtEpochMs());
        }

        assertTrue(
                eventInfrastructure.stored.stream().noneMatch(event -> "ExecutionStuck".equals(event.eventName())),
                "no ExecutionStuck may be emitted for a merely-quiet wait"
        );
    }

    /**
     * R2.1: quiet misses and real failures share {@code resumeAttemptCount}, so once quiet waits
     * stopped exhausting the cap that counter can sit far above RESUME_MAX_ATTEMPTS by the time an
     * event finally arrives. Without a fresh streak, the first transient adapter fault after a long
     * wait would be an instant, unrecoverable STUCK -- strictly worse than the 20 retries that path
     * had before R2.1.
     */
    @Test
    void realFailureAfterALongQuietWaitStartsAFreshAttemptStreak() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = newRunnerWithFailingResume(eventInfrastructure, flowInstanceStore);

        flowInstanceStore.save(waitingInstanceAtAttempt(
                "exec-streak-1", "corr-streak-1", 137, "missing_event"));
        eventInfrastructure.append(awaitedApprovalEvent("evt-streak-1", "corr-streak-1"));

        assertEquals(0, runner.resumeAllWaitingExecutions(100));

        FlowInstance after = flowInstanceStore.findByExecutionId("exec-streak-1").orElseThrow();
        assertEquals(
                FlowInstanceStatus.WAITING_EVENT,
                after.status(),
                "one real failure after 137 quiet misses must not be an instant STUCK"
        );
        assertEquals(1, after.resumeAttemptCount());
        assertEquals("exception:IllegalStateException", after.lastResumeErrorCode());
    }

    /**
     * R2.1 (second half): the synchronous event-arrival path must revive a matching waiter whatever
     * its backoff state. {@code ResumeCoordinator.resumeWaitingExecutionsFor}'s own comment already
     * states the invariant -- "Backoff is only for scheduled scans" -- but an eligibility check ran
     * ahead of the criteria match and skipped exactly the instance the event was for.
     */
    @Test
    void publishExternalEventResumesInstanceParkedInResumeBackoff() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );

        ExecutionResult waiting = runner.execute("AwaitApproval", Map.of("correlationId", "corr-backoff-revive"));
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());

        // One fruitless sweep parks the instance in a 5s backoff window -- the precondition asserted
        // below, and the state a real waiter is in for all but an instant of its life.
        assertEquals(0, runner.resumeAllWaitingExecutions(100));
        FlowInstance parked = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertFalse(
                parked.isResumeEligible(System.currentTimeMillis()),
                "precondition: the instance must be inside its scheduled-sweep backoff window"
        );

        runner.publishExternalEvent(
                "InvoiceApproved",
                Map.of("status", "APPROVED"),
                "corr-backoff-revive",
                "cause-revive"
        );

        FlowInstance after = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(
                FlowInstanceStatus.COMPLETED,
                after.status(),
                "a matching event must resume its waiter regardless of scheduled-sweep backoff"
        );
    }

    @Test
    void executeClaimsCorrelationOwnershipAndRejectsDifferentTenant() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();
        CorrelationOwnershipStoreStub ownershipStore = new CorrelationOwnershipStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                com.npdev.kernel.ports.ExecutionTracer.NOOP,
                eventInfrastructure,
                flowInstanceStore,
                ownershipStore
        );

        ExecutionResult first = runner.execute(
                "AwaitApproval",
                Map.of("correlationId", "corr-owned-shared"),
                ExecutionContext.of("tenant-a", "actor-a")
        );
        assertEquals(ExecutionStatus.WAITING_EVENT, first.getStatus());

        assertThrows(
                CorrelationOwnershipViolationException.class,
                () -> runner.execute(
                        "AwaitApproval",
                        Map.of("correlationId", "corr-owned-shared"),
                        ExecutionContext.of("tenant-b", "actor-b")
                )
        );
    }

    @Test
    void publishExternalEventRejectsDifferentTenantOnOwnedCorrelation() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();
        CorrelationOwnershipStoreStub ownershipStore = new CorrelationOwnershipStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlow(),
                (call, state) -> CapabilityResult.success(null),
                com.npdev.kernel.ports.ExecutionTracer.NOOP,
                eventInfrastructure,
                flowInstanceStore,
                ownershipStore
        );

        runner.execute(
                "AwaitApproval",
                Map.of("correlationId", "corr-event-owned"),
                ExecutionContext.of("tenant-a", "actor-a")
        );

        assertThrows(
                CorrelationOwnershipViolationException.class,
                () -> runner.publishExternalEvent(
                        "InvoiceApproved",
                        Map.of("status", "DENIED"),
                        "corr-event-owned",
                        "cause-b",
                        ExecutionContext.of("tenant-b", "actor-b")
                )
        );
    }

    @Test
    void contractCapabilityFailureMarksInstanceFailedPermanentAndEmitsEscalationEvent() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForContractFailureFlow(),
                (call, state) -> CapabilityResult.failure(
                        "VALIDATION_FAIL",
                        "Contract mismatch",
                        CapabilityErrorKind.CONTRACT,
                        Map.of("capability", call.capability())
                ),
                eventInfrastructure,
                flowInstanceStore
        );

        ExecutionResult result = runner.execute("CreateWithContractFailure", Map.of("correlationId", "corr-contract-fail-1"));

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertNotNull(result.getFailureInfo());
        assertEquals(ErrorKind.CONTRACT, result.getFailureInfo().kind());
        assertEquals(FailureCodes.CAPABILITY_CONTRACT, result.getFailureInfo().code());

        FlowInstance stored = flowInstanceStore.findByExecutionId(result.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.FAILED_PERMANENT, stored.status());
        assertEquals(FailureCodes.CAPABILITY_CONTRACT, stored.lastErrorCode());
        assertTrue(eventInfrastructure.stored.stream()
                .anyMatch(event -> "ExecutionFailedPermanent".equals(event.eventName())));
    }

    /**
     * R2.5 (durable await timeouts + onTimeout): a crash-injection restart proof that the timeout
     * branch runs durably after a JVM restart, i.e. the deadline lives in the persisted flow
     * instance's state, not in any in-memory field only the process that parked it would have.
     * There is no injectable clock (KernelRunner.nowEpochMillis() is a plain
     * System.currentTimeMillis(), per CLAUDE.md), so this seeds the ALREADY-EXPIRED deadline
     * directly into the store -- the same idiom {@link #waitingInstanceAtAttempt} uses for
     * {@code nextEligibleResumeAtEpochMs} below -- rather than waiting out a real 60-second
     * timeout. {@code firstRunner} parks the instance for real (computing and persisting a
     * genuine, not-yet-expired deadline via {@code AwaitEventStep}/{@code FlowStateCodec}); the
     * test then rewrites ONLY the persisted state's deadline field to a moment in the deep past,
     * and a brand-new {@code secondRunner} -- sharing nothing with {@code firstRunner} except
     * {@code flowInstanceStore}, exactly like {@link
     * #resumeRemainsPossibleWithNewKernelRunnerUsingSameStores} above -- discovers it on its own
     * {@code resumeAllWaitingExecutions} sweep. This exercises both halves of the feature at once:
     * {@code ResumeCoordinator}'s sweep must resume a timed-out instance even though it has NO
     * candidate event at all (the ordinary quiet-wait branch would otherwise just re-back-off
     * forever), and {@code AwaitEventStep} must re-read the SAME persisted deadline rather than
     * recomputing "now + timeout" on this fresh resume attempt.
     */
    @Test
    @SuppressWarnings("unchecked")
    void timeoutBranchRunsDurablyAfterRestartWhenPersistedDeadlineHasAlreadyPassed() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();

        KernelRunner firstRunner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlowWithTimeout(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );
        ExecutionResult waiting = firstRunner.execute(
                "AwaitApprovalWithTimeout", Map.of("correlationId", "corr-timeout-1"));
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());

        FlowInstance parked = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        Map<String, Object> mutatedAwaitState = new LinkedHashMap<>(
                (Map<String, Object>) parked.state().get("_npdev.await"));
        assertNotNull(mutatedAwaitState.get("timeoutDeadlineEpochMs"),
                "firstRunner must have persisted a real deadline for this crash-injection test to rewrite");
        mutatedAwaitState.put("timeoutDeadlineEpochMs", 1000L);
        Map<String, Object> mutatedState = new LinkedHashMap<>(parked.state());
        mutatedState.put("_npdev.await", mutatedAwaitState);
        flowInstanceStore.update(parked.markWaiting(
                parked.currentStepIndex(), parked.waitingForEventName(), mutatedState, parked.updatedAtEpochMs()));

        KernelRunner secondRunner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProviderForAwaitFlowWithTimeout(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );
        int resumed = secondRunner.resumeAllWaitingExecutions(100);
        assertEquals(1, resumed);

        FlowInstance completed = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, completed.status());
        assertEquals("escalated", completed.state().get("timeoutOutcome"));
        assertNull(completed.state().get("_npdev.await"), "a resolved await (by timeout or by event) must clear its parked state");
    }

    /**
     * R2.1: a WAITING_EVENT row parked at an arbitrary attempt count, so a test can stand one sweep
     * away from the cap without driving 20 real sweeps through a wall clock this kernel gives no
     * seam to fake. {@code nextEligibleResumeAtEpochMs} is deliberately far in the past so every
     * sweep finds the instance eligible.
     */
    private static FlowInstance waitingInstanceAtAttempt(
            String executionId,
            String correlationId,
            int resumeAttemptCount,
            String lastResumeErrorCode
    ) {
        return new FlowInstance(
                executionId,
                "AwaitApproval",
                correlationId,
                "default",
                "anonymous",
                0,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("input", Map.of("correlationId", correlationId)),
                "InvoiceApproved",
                1000L,
                2000L,
                resumeAttemptCount,
                1500L,
                lastResumeErrorCode,
                1500L,
                1000L
        );
    }

    private static EventEnvelope awaitedApprovalEvent(String eventId, String correlationId) {
        return new EventEnvelope(
                eventId,
                "InvoiceApproved",
                1000L,
                Map.of("status", "APPROVED"),
                correlationId,
                "cause-" + eventId,
                "external",
                0,
                "default",
                "anonymous"
        );
    }

    /**
     * R2.1: the cheapest stand-in for what the attempt cap actually exists for -- a collaborator
     * that faults every time the sweep tries to rehydrate. Throwing from the {@code findFlow}
     * lookup means the exception escapes {@code resumeExecution} BEFORE any instance mutation, so
     * the only state change per sweep is the resume backoff under test.
     */
    private static KernelRunner newRunnerWithFailingResume(
            RecordingEventInfrastructure eventInfrastructure,
            FlowInstanceStore flowInstanceStore
    ) {
        FlowDefinitionProvider alwaysThrowingProvider = flowName -> {
            throw new IllegalStateException("flow definition store unavailable");
        };
        return new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                alwaysThrowingProvider,
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                flowInstanceStore
        );
    }

    private static InMemoryFlowDefinitionProvider flowProviderForAwaitFlow() {
        return new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "AwaitApproval",
                        "Invoice",
                        List.of(
                                FlowStepDefinition.awaitEvent("wait-approval", "InvoiceApproved", "$approval"),
                                FlowStepDefinition.returnValue("return-status", "$approval.status")
                        )
                ));
    }

    /**
     * R2.4: the awaiting flow above plus two schedulers of the SAME event, differing only in delay,
     * so a test can compare zero-delay and delayed behaviour with nothing else moving.
     */
    private static InMemoryFlowDefinitionProvider flowProviderForAwaitAndScheduleFlows() {
        return flowProviderForAwaitFlow()
                .register(new FlowDefinition(
                        "ApproveNow",
                        "Invoice",
                        List.of(
                                FlowStepDefinition.scheduleEvent(
                                        "approve-now", "InvoiceApproved", "$input", Map.of(), 0L),
                                FlowStepDefinition.returnValue("return-input", "$input")
                        )
                ))
                .register(new FlowDefinition(
                        "ApproveLater",
                        "Invoice",
                        List.of(
                                FlowStepDefinition.scheduleEvent(
                                        "approve-later", "InvoiceApproved", "$input", Map.of(), 3600L),
                                FlowStepDefinition.returnValue("return-input", "$input")
                        )
                ));
    }

    private static final class CountingDeferredEventScheduler
            implements com.npdev.kernel.ports.DeferredEventScheduler {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean scheduleForLaterDelivery(EventEnvelope envelope, long dueAtEpochMs) {
            calls.incrementAndGet();
            return true;
        }
    }

    private static InMemoryFlowDefinitionProvider flowProviderForAwaitFlowWithPayloadMatch() {
        return new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "AwaitApprovalWithPayloadMatch",
                        "Invoice",
                        List.of(
                                FlowStepDefinition.awaitEvent(
                                        "wait-approval",
                                        "InvoiceApproved",
                                        "$approval",
                                        true,
                                        Map.of("receipt", "$input.expectedReceipt")
                                ),
                                FlowStepDefinition.returnValue("return-status", "$approval.status")
                        )
                ));
    }

    /**
     * R2.5 (durable await timeouts + onTimeout): the awaiting flow above, plus a 60-second timeout
     * and an escalation branch that stamps state.timeoutOutcome -- the marker the crash-injection
     * test asserts on to prove the timeout branch (not the normal found-the-event branch) is what
     * ran.
     */
    private static InMemoryFlowDefinitionProvider flowProviderForAwaitFlowWithTimeout() {
        return new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "AwaitApprovalWithTimeout",
                        "Invoice",
                        List.of(
                                FlowStepDefinition.awaitEvent("wait-approval", "InvoiceApproved", "$approval")
                                        .withTimeout(60L, List.of(
                                                FlowStepDefinition.map("mark-escalated", "escalated", "$timeoutOutcome")
                                        )),
                                FlowStepDefinition.returnValue("return-status", "$timeoutOutcome")
                        )
                ));
    }

    private static InMemoryFlowDefinitionProvider flowProviderForDoubleAwaitFlow() {
        return new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "AwaitApprovalTwice",
                        "Invoice",
                        List.of(
                                FlowStepDefinition.awaitEvent("wait-approval-1", "InvoiceApproved", "$firstApproval"),
                                FlowStepDefinition.awaitEvent("wait-approval-2", "InvoiceApproved", "$secondApproval"),
                                FlowStepDefinition.returnValue("return-second-status", "$secondApproval.status")
                        )
                ));
    }

    private static KernelRunner newLifecycleRunner(
            RecordingEventInfrastructure eventInfrastructure,
            InMemoryFlowDefinitionProvider flowProvider,
            FlowInstanceStore flowInstanceStore,
            IdempotencyStore idempotencyStore,
            ExecutionTracer executionTracer,
            IdProvider idProvider
    ) {
        return new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> CapabilityResult.success(null),
                executionTracer,
                eventInfrastructure,
                flowInstanceStore,
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                idempotencyStore,
                CapabilityPolicyOverrides.empty(),
                JsonCodec.noop(),
                SchemaValidator.noop(),
                MetricsSink.noop(),
                idProvider
        );
    }

    private static DeterminismSnapshot runDeterministicDoubleAwaitSequence() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        FlowInstanceStoreStub flowInstanceStore = new FlowInstanceStoreStub();
        InMemoryIdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();
        TraceCollector tracer = new TraceCollector();

        KernelRunner runner = newLifecycleRunner(
                eventInfrastructure,
                flowProviderForDoubleAwaitFlow(),
                flowInstanceStore,
                idempotencyStore,
                tracer,
                deterministicIdProvider()
        );

        ExecutionResult waiting = runner.startFlow(
                "AwaitApprovalTwice",
                Map.of("correlationId", "corr-phase5-deterministic"),
                ExecutionContext.anonymous()
        );
        assertEquals(ExecutionStatus.WAITING_EVENT, waiting.getStatus());

        EventEnvelope event1 = new EventEnvelope(
                "evt-phase5-det-1",
                "InvoiceApproved",
                10_000L,
                Map.of("status", "FIRST"),
                "corr-phase5-deterministic",
                "cause-phase5-det-1",
                "external",
                0,
                "default",
                "anonymous"
        );
        eventInfrastructure.append(event1);
        FlowEngine.ResumeOutcome firstOutcome = runner.resumeFlow("corr-phase5-deterministic", event1);
        assertEquals(1, firstOutcome.matchedWaiters());
        assertEquals(0, firstOutcome.resumedWaiters());

        EventEnvelope event2 = new EventEnvelope(
                "evt-phase5-det-2",
                "InvoiceApproved",
                20_000L,
                Map.of("status", "SECOND"),
                "corr-phase5-deterministic",
                "cause-phase5-det-2",
                "external",
                0,
                "default",
                "anonymous"
        );
        eventInfrastructure.append(event2);
        FlowEngine.ResumeOutcome secondOutcome = runner.resumeFlow("corr-phase5-deterministic", event2);
        assertEquals(1, secondOutcome.resumedWaiters());

        FlowInstance completed = flowInstanceStore.findByExecutionId(waiting.getExecutionId()).orElseThrow();
        assertEquals(FlowInstanceStatus.COMPLETED, completed.status());
        return new DeterminismSnapshot(
                canonicalJson(completed.state()),
                tracer.toStableTraceJson()
        );
    }

    private static IdProvider deterministicIdProvider() {
        AtomicInteger sequence = new AtomicInteger();
        return scope -> {
            String normalizedScope = (scope == null || scope.isBlank()) ? "default" : scope.trim();
            return normalizedScope + "-det-" + sequence.incrementAndGet();
        };
    }

    private static String canonicalJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + text
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof EventEnvelope envelope) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("eventId", envelope.eventId());
            normalized.put("eventName", envelope.eventName());
            normalized.put("timestampEpochMs", envelope.timestampEpochMs());
            normalized.put("payload", envelope.payload());
            normalized.put("correlationId", envelope.correlationId());
            normalized.put("causationId", envelope.causationId());
            normalized.put("flowName", envelope.flowName());
            normalized.put("stepIndex", envelope.stepIndex());
            normalized.put("tenantId", envelope.tenantId());
            normalized.put("actorId", envelope.actorId());
            return canonicalJson(normalized);
        }
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                entries.add(entry);
            }
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : entries) {
                if (!first) {
                    out.append(',');
                }
                String key = String.valueOf(entry.getKey());
                out.append(canonicalJson(key));
                out.append(':');
                out.append(canonicalJson(entry.getValue()));
                first = false;
            }
            out.append('}');
            return out.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    out.append(',');
                }
                out.append(canonicalJson(list.get(index)));
            }
            out.append(']');
            return out.toString();
        }
        return canonicalJson(String.valueOf(value));
    }

    private static InMemoryFlowDefinitionProvider flowProviderForContractFailureFlow() {
        return new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateWithContractFailure",
                        "User",
                        List.of(
                                FlowStepDefinition.capabilityCall(
                                        "save-user",
                                        "persistence",
                                        "PersistenceCapability",
                                        "inmemory",
                                        "save",
                                        List.of("$input"),
                                        "$saved",
                                        new com.npdev.kernel.capabilities.CapabilityExecutionPolicy(1, 0, 0, 0, 0, 0, null, null)
                                ),
                                FlowStepDefinition.returnValue("return-saved", "$saved")
                        )
                ));
    }

    private record DeterminismSnapshot(
            String finalState,
            String traceJson
    ) {
    }

    private static final class TraceCollector implements ExecutionTracer {
        private final List<FlowTrace> traces = new CopyOnWriteArrayList<>();

        @Override
        public void onFlowEnd(FlowTrace flowTrace) {
            traces.add(flowTrace);
        }

        String toStableTraceJson() {
            List<Map<String, Object>> normalized = traces.stream()
                    .map(trace -> {
                        Map<String, Object> flow = new LinkedHashMap<>();
                        flow.put("executionId", trace.meta().executionId());
                        flow.put("correlationId", trace.meta().correlationId());
                        flow.put("flowName", trace.meta().flowName());
                        flow.put("tenantId", trace.meta().tenantId());
                        flow.put("actorId", trace.meta().actorId());
                        flow.put("tags", trace.meta().tags());
                        flow.put("outcome", trace.outcome().name());
                        List<Map<String, Object>> steps = trace.steps().stream()
                                .map(step -> {
                                    Map<String, Object> normalizedStep = new LinkedHashMap<>();
                                    normalizedStep.put("stepIndex", step.stepIndex());
                                    normalizedStep.put("stepName", step.stepName());
                                    normalizedStep.put("stepType", step.stepType());
                                    normalizedStep.put("outcome", step.outcome().name());
                                    normalizedStep.put("info", step.info());
                                    normalizedStep.put("invariantViolationCount", step.invariantViolations().size());
                                    normalizedStep.put(
                                            "capabilityErrorCode",
                                            step.capabilityError() == null ? null : step.capabilityError().code()
                                    );
                                    return normalizedStep;
                                })
                                .toList();
                        flow.put("steps", steps);
                        return flow;
                    })
                    .toList();
            return canonicalJson(normalized);
        }
    }

    private static final class InMemoryIdempotencyStore implements IdempotencyStore {
        private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

        @Override
        public Optional<IdempotencyRecord> find(String tenantId, String capability, String operation, String idempotencyKey) {
            return Optional.ofNullable(records.get(key(tenantId, capability, operation, idempotencyKey)));
        }

        @Override
        public void saveSuccess(
                String tenantId,
                String capability,
                String operation,
                String idempotencyKey,
                String resultJsonRedacted,
                long createdAtMs
        ) {
            records.put(
                    key(tenantId, capability, operation, idempotencyKey),
                    new IdempotencyRecord(
                            tenantId,
                            idempotencyKey,
                            capability,
                            operation,
                            createdAtMs,
                            IdempotencyRecord.STATUS_SUCCESS,
                            resultJsonRedacted,
                            null
                    )
            );
        }

        @Override
        public void saveFailure(
                String tenantId,
                String capability,
                String operation,
                String idempotencyKey,
                String errorCode,
                long createdAtMs
        ) {
            records.put(
                    key(tenantId, capability, operation, idempotencyKey),
                    new IdempotencyRecord(
                            tenantId,
                            idempotencyKey,
                            capability,
                            operation,
                            createdAtMs,
                            IdempotencyRecord.STATUS_FAILED,
                            null,
                            errorCode
                    )
            );
        }

        private static String key(String tenantId, String capability, String operation, String idempotencyKey) {
            return tenantId + "|" + capability + "|" + operation + "|" + idempotencyKey;
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
            if (correlationId == null || correlationId.isBlank()) {
                return List.of();
            }
            return byExecutionId.values().stream()
                    .filter(instance -> instance.status() == FlowInstanceStatus.WAITING_EVENT)
                    .filter(instance -> correlationId.equals(instance.correlationId()))
                    .toList();
        }

        @Override
        public List<FlowInstance> findWaitingByEvent(String eventName) {
            if (eventName == null || eventName.isBlank()) {
                return List.of();
            }
            return byExecutionId.values().stream()
                    .filter(instance -> instance.status() == FlowInstanceStatus.WAITING_EVENT)
                    .filter(instance -> eventName.equals(instance.waitingForEventName()))
                    .toList();
        }

        @Override
        public List<FlowInstance> findAllWaiting(int limit) {
            int effectiveLimit = limit <= 0 ? Integer.MAX_VALUE : limit;
            return byExecutionId.values().stream()
                    .filter(instance -> instance.status() == FlowInstanceStatus.WAITING_EVENT)
                    .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).thenComparing(FlowInstance::executionId))
                    .limit(effectiveLimit)
                    .toList();
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

    private static final class CorrelationOwnershipStoreStub implements CorrelationOwnershipStore {
        private final Map<String, String> ownership = new ConcurrentHashMap<>();

        @Override
        public Optional<String> findTenantByCorrelationId(String correlationId) {
            return Optional.ofNullable(ownership.get(correlationId));
        }

        @Override
        public void claimCorrelation(String correlationId, String tenantId) {
            ownership.compute(correlationId, (key, owner) -> {
                if (owner == null || owner.equals(tenantId)) {
                    return tenantId;
                }
                throw new CorrelationOwnershipViolationException(correlationId, owner, tenantId);
            });
        }
    }
}
