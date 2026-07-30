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

    @Test
    void resumeAllWaitingExecutionsMarksInstanceStuckWhenAttemptCapIsReached() {
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

        FlowInstance nearCap = new FlowInstance(
                "exec-cap-1",
                "AwaitApproval",
                "corr-cap-1",
                "default",
                "anonymous",
                0,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("input", Map.of("correlationId", "corr-cap-1")),
                "InvoiceApproved",
                1000L,
                2000L,
                19,
                1500L,
                "missing_event",
                1500L,
                1000L
        );
        flowInstanceStore.save(nearCap);

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
