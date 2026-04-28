package com.npdev.adapters.flowinstance.inproc;

import com.npdev.kernel.exec.ExecutionSummary;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcFlowInstanceStoreTest {

    @Test
    void indexesWaitingInstancesByCorrelationAndEvent() {
        InProcFlowInstanceStore store = new InProcFlowInstanceStore();
        FlowInstance waitingA = new FlowInstance(
                "exec-a",
                "FinalizeInvoice",
                "corr-1",
                2,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("k", "v"),
                "GovernmentResponse",
                10L,
                12L
        );
        FlowInstance waitingB = new FlowInstance(
                "exec-b",
                "FinalizeInvoice",
                "corr-1",
                3,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("k", "v2"),
                "GovernmentResponse",
                11L,
                14L
        );

        store.save(waitingA);
        store.save(waitingB);

        List<FlowInstance> byCorrelation = store.findWaitingByCorrelation("corr-1");
        List<FlowInstance> byEvent = store.findWaitingByEvent("GovernmentResponse");

        assertEquals(List.of("exec-a", "exec-b"), byCorrelation.stream().map(FlowInstance::executionId).toList());
        assertEquals(List.of("exec-a", "exec-b"), byEvent.stream().map(FlowInstance::executionId).toList());
    }

    @Test
    void updateRemovesIndexesWhenInstanceStopsWaiting() {
        InProcFlowInstanceStore store = new InProcFlowInstanceStore();
        FlowInstance waiting = new FlowInstance(
                "exec-a",
                "FinalizeInvoice",
                "corr-2",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("k", "v"),
                "InvoiceApproved",
                20L,
                21L
        );
        store.save(waiting);

        FlowInstance running = waiting.markRunning(2, Map.of("k", "v"), 30L);
        store.update(running);

        assertTrue(store.findWaitingByCorrelation("corr-2").isEmpty());
        assertTrue(store.findWaitingByEvent("InvoiceApproved").isEmpty());
    }

    @Test
    void findByExecutionIdReturnsLatestSnapshot() {
        InProcFlowInstanceStore store = new InProcFlowInstanceStore();
        FlowInstance started = FlowInstance.start(
                "exec-z",
                "CreateUser",
                "corr-z",
                Map.of("input", Map.of("email", "a@b.com")),
                100L
        );
        store.save(started);

        FlowInstance waiting = started.markWaiting(1, "UserApproved", Map.of("input", Map.of("email", "a@b.com")), 120L);
        store.update(waiting);

        FlowInstance found = store.findByExecutionId("exec-z").orElseThrow();
        assertEquals("exec-z", found.executionId());
        assertEquals(FlowInstanceStatus.WAITING_EVENT, found.status());
        assertEquals("UserApproved", found.waitingForEventName());
    }

    @Test
    void findAllWaitingReturnsDeterministicLimitedSlice() {
        InProcFlowInstanceStore store = new InProcFlowInstanceStore();
        FlowInstance waitingB = new FlowInstance(
                "exec-b",
                "FinalizeInvoice",
                "corr-10",
                2,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("k", "v2"),
                "InvoiceApproved",
                100L,
                400L
        );
        FlowInstance waitingA = new FlowInstance(
                "exec-a",
                "FinalizeInvoice",
                "corr-10",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("k", "v1"),
                "InvoiceApproved",
                100L,
                300L
        );
        FlowInstance waitingC = new FlowInstance(
                "exec-c",
                "FinalizeInvoice",
                "corr-11",
                3,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("k", "v3"),
                "InvoiceApproved",
                100L,
                500L
        );

        store.save(waitingB);
        store.save(waitingA);
        store.save(waitingC);

        List<FlowInstance> waiting = store.findAllWaiting(2);
        assertEquals(List.of("exec-a", "exec-b"), waiting.stream().map(FlowInstance::executionId).toList());
    }

    @Test
    void tenantScopedReadQueriesReturnOnlyTenantRows() {
        InProcFlowInstanceStore store = new InProcFlowInstanceStore();
        FlowInstance tenantAWaiting = new FlowInstance(
                "exec-a1",
                "CreateUser",
                "corr-shared",
                "tenant-a",
                "actor-a",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("secret", "x"),
                "UserApproved",
                100L,
                300L
        );
        FlowInstance tenantACompleted = new FlowInstance(
                "exec-a2",
                "CreateUser",
                "corr-shared",
                "tenant-a",
                "actor-a",
                2,
                FlowInstanceStatus.COMPLETED,
                Map.of("secret", "y"),
                null,
                100L,
                400L
        );
        FlowInstance tenantBWaiting = new FlowInstance(
                "exec-b1",
                "CreateUser",
                "corr-shared",
                "tenant-b",
                "actor-b",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("secret", "z"),
                "UserApproved",
                100L,
                500L
        );
        store.save(tenantAWaiting);
        store.save(tenantACompleted);
        store.save(tenantBWaiting);

        assertEquals(List.of("exec-a2", "exec-a1"),
                store.findRecent("tenant-a", 50, 0).stream().map(FlowInstance::executionId).toList());
        assertEquals(List.of("exec-a1"),
                store.findWaiting("tenant-a", 50, 0).stream().map(FlowInstance::executionId).toList());
        assertEquals(List.of("exec-a2", "exec-a1"),
                store.findByCorrelationId("tenant-a", "corr-shared").stream().map(FlowInstance::executionId).toList());
    }

    @Test
    void findWaitingEligibleToResumeRespectsTenantAndEligibility() {
        InProcFlowInstanceStore store = new InProcFlowInstanceStore();
        FlowInstance eligible = new FlowInstance(
                "exec-eligible",
                "AwaitApproval",
                "corr-1",
                "tenant-a",
                "actor-a",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of(),
                "Approved",
                100L,
                200L,
                2,
                150L,
                "missing_event",
                250L,
                120L
        );
        FlowInstance future = new FlowInstance(
                "exec-future",
                "AwaitApproval",
                "corr-1",
                "tenant-a",
                "actor-a",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of(),
                "Approved",
                100L,
                200L,
                2,
                150L,
                "missing_event",
                9_000L,
                120L
        );
        FlowInstance otherTenant = new FlowInstance(
                "exec-b",
                "AwaitApproval",
                "corr-1",
                "tenant-b",
                "actor-b",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of(),
                "Approved",
                100L,
                200L,
                0,
                null,
                null,
                200L,
                120L
        );

        store.save(future);
        store.save(otherTenant);
        store.save(eligible);

        List<FlowInstance> rows = store.findWaitingEligibleToResume("tenant-a", 300L, 10);
        assertEquals(List.of("exec-eligible"), rows.stream().map(FlowInstance::executionId).toList());
    }

    @Test
    void findStaleWaitingAndSummaryExposeOperationalFields() {
        InProcFlowInstanceStore store = new InProcFlowInstanceStore();
        FlowInstance stale = new FlowInstance(
                "exec-stale",
                "AwaitApproval",
                "corr-1",
                "tenant-a",
                "actor-a",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of(),
                "Approved",
                100L,
                400L,
                3,
                300L,
                "missing_event",
                450L,
                200L
        );
        FlowInstance fresh = new FlowInstance(
                "exec-fresh",
                "AwaitApproval",
                "corr-2",
                "tenant-a",
                "actor-a",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of(),
                "Approved",
                100L,
                500L,
                0,
                null,
                null,
                500L,
                900L
        );
        store.save(stale);
        store.save(fresh);

        List<FlowInstance> staleRows = store.findStaleWaiting("tenant-a", 300L, 10, 0);
        assertEquals(List.of("exec-stale"), staleRows.stream().map(FlowInstance::executionId).toList());

        List<ExecutionSummary> summaries = store.listSummaries("tenant-a", "waiting", 10, 0);
        ExecutionSummary staleSummary = summaries.stream()
                .filter(summary -> "exec-stale".equals(summary.executionId()))
                .findFirst()
                .orElseThrow();
        assertEquals(3, staleSummary.resumeAttemptCount());
        assertEquals("missing_event", staleSummary.lastResumeErrorCode());
        assertEquals(450L, staleSummary.nextEligibleResumeAtEpochMs());
    }

    @Test
    void failureAndStuckSummariesAreScopedAndOrdered() {
        InProcFlowInstanceStore store = new InProcFlowInstanceStore();
        FlowInstance failed = new FlowInstance(
                "exec-failed",
                "CreateUser",
                "corr-1",
                "tenant-a",
                "actor-a",
                2,
                FlowInstanceStatus.FAILED_PERMANENT,
                Map.of(),
                null,
                100L,
                900L
        );
        FlowInstance stuck = new FlowInstance(
                "exec-stuck",
                "AwaitApproval",
                "corr-2",
                "tenant-a",
                "actor-a",
                3,
                FlowInstanceStatus.STUCK,
                Map.of(),
                "Approved",
                100L,
                1000L
        );
        FlowInstance otherTenant = new FlowInstance(
                "exec-other",
                "CreateUser",
                "corr-3",
                "tenant-b",
                "actor-b",
                1,
                FlowInstanceStatus.FAILED_PERMANENT,
                Map.of(),
                null,
                100L,
                1100L
        );
        store.save(failed);
        store.save(stuck);
        store.save(otherTenant);

        List<ExecutionSummary> failures = store.listFailureSummaries("tenant-a", 10, 0);
        List<ExecutionSummary> stuckRows = store.listStuckSummaries("tenant-a", 10, 0);

        assertEquals(List.of("exec-failed"), failures.stream().map(ExecutionSummary::executionId).toList());
        assertEquals(List.of("exec-stuck"), stuckRows.stream().map(ExecutionSummary::executionId).toList());
    }
}
