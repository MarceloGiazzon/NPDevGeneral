package com.npdev.adapters.flowinstance.postgres;

import com.npdev.kernel.exec.ExecutionSummary;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.test.postgres.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresFlowInstanceStoreTest {
    private static final String[] SCHEMA_SQL = new String[]{
            """
            CREATE TABLE IF NOT EXISTS npdev_flow_instance (
                execution_id TEXT PRIMARY KEY,
                flow_name TEXT NOT NULL,
                correlation_id TEXT NOT NULL,
                tenant_id TEXT,
                actor_id TEXT,
                status TEXT NOT NULL,
                current_step_index INTEGER NOT NULL,
                waiting_for_event_name TEXT,
                state_json TEXT NOT NULL,
                created_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP NOT NULL,
                resume_attempt_count INTEGER NOT NULL DEFAULT 0,
                last_resume_at TIMESTAMP,
                last_resume_error_code TEXT,
                next_eligible_resume_at TIMESTAMP,
                last_progress_at TIMESTAMP,
                last_error_kind TEXT,
                last_error_code TEXT,
                last_error_message TEXT,
                failed_at TIMESTAMP
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_npdev_flow_instance_correlation ON npdev_flow_instance(correlation_id)",
            "CREATE INDEX IF NOT EXISTS idx_npdev_flow_instance_waiting_event ON npdev_flow_instance(waiting_for_event_name)",
            "CREATE INDEX IF NOT EXISTS idx_npdev_flow_instance_status_updated ON npdev_flow_instance(status, updated_at, execution_id)",
            "CREATE INDEX IF NOT EXISTS idx_npdev_flow_instance_tenant ON npdev_flow_instance(tenant_id)",
            "CREATE INDEX IF NOT EXISTS idx_inst_tenant_next_eligible ON npdev_flow_instance(tenant_id, next_eligible_resume_at, updated_at)",
            "CREATE INDEX IF NOT EXISTS idx_inst_tenant_last_progress ON npdev_flow_instance(tenant_id, last_progress_at)"
    };

    private PostgresFlowInstanceStore store;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PostgresTestSupport.dataSource();
        PostgresTestSupport.execute(dataSource, SCHEMA_SQL);
        PostgresTestSupport.truncate(dataSource, "npdev_flow_instance");
        store = new PostgresFlowInstanceStore(dataSource);
    }

    @Test
    void saveUpdateAndFindByExecutionId() {
        FlowInstance started = FlowInstance.start(
                "exec-1",
                "CreateUser",
                "corr-1",
                "tenant-1",
                "actor-1",
                Map.of("input", Map.of("email", "a@b.com")),
                1000L
        );
        store.save(started);

        FlowInstance waiting = started.markWaiting(
                1,
                "UserApproved",
                Map.of("input", Map.of("email", "a@b.com"), "last", "WAITING"),
                2000L
        );
        store.update(waiting);

        FlowInstance loaded = store.findByExecutionId("exec-1").orElseThrow();
        assertEquals("CreateUser", loaded.flowName());
        assertEquals("corr-1", loaded.correlationId());
        assertEquals("tenant-1", loaded.tenantId());
        assertEquals("actor-1", loaded.actorId());
        assertEquals(FlowInstanceStatus.WAITING_EVENT, loaded.status());
        assertEquals("UserApproved", loaded.waitingForEventName());
        assertEquals(1, loaded.currentStepIndex());
        assertEquals(0, loaded.resumeAttemptCount());
    }

    @Test
    void waitingQueriesAndFindAllWaitingAreDeterministic() {
        FlowInstance waitingA = new FlowInstance(
                "exec-a",
                "FinalizeInvoice",
                "corr-1",
                "tenant-x",
                "actor-a",
                2,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("invoiceId", "inv-1"),
                "GovernmentResponse",
                1000L,
                1200L
        );
        FlowInstance waitingB = new FlowInstance(
                "exec-b",
                "FinalizeInvoice",
                "corr-1",
                "tenant-x",
                "actor-b",
                3,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("invoiceId", "inv-2"),
                "GovernmentResponse",
                1000L,
                1300L
        );
        FlowInstance waitingC = new FlowInstance(
                "exec-c",
                "FinalizeInvoice",
                "corr-2",
                "tenant-y",
                "actor-c",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("invoiceId", "inv-3"),
                "OtherEvent",
                1000L,
                1400L
        );
        FlowInstance completed = new FlowInstance(
                "exec-d",
                "FinalizeInvoice",
                "corr-1",
                "tenant-x",
                "actor-d",
                4,
                FlowInstanceStatus.COMPLETED,
                Map.of("invoiceId", "inv-4"),
                null,
                1000L,
                1500L
        );

        store.save(waitingB);
        store.save(waitingA);
        store.save(waitingC);
        store.save(completed);

        List<FlowInstance> byCorrelation = store.findWaitingByCorrelation("corr-1");
        List<FlowInstance> byEvent = store.findWaitingByEvent("GovernmentResponse");
        List<FlowInstance> allWaitingLimited = store.findAllWaiting(2);

        assertEquals(List.of("exec-a", "exec-b"), byCorrelation.stream().map(FlowInstance::executionId).toList());
        assertEquals(List.of("exec-a", "exec-b"), byEvent.stream().map(FlowInstance::executionId).toList());
        assertEquals(List.of("exec-a", "exec-b"), allWaitingLimited.stream().map(FlowInstance::executionId).toList());
        assertTrue(store.findByExecutionId("exec-d").orElseThrow().status() == FlowInstanceStatus.COMPLETED);
    }

    @Test
    void findWaitingEligibleToResumeReturnsOnlyEligibleRows() {
        FlowInstance eligible = new FlowInstance(
                "exec-eligible",
                "AwaitApproval",
                "corr-eligible",
                "tenant-a",
                "actor-a",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of(),
                "Approved",
                1000L,
                2000L,
                2,
                1500L,
                "missing_event",
                2500L,
                1200L
        );
        FlowInstance future = new FlowInstance(
                "exec-future",
                "AwaitApproval",
                "corr-future",
                "tenant-a",
                "actor-a",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of(),
                "Approved",
                1000L,
                2500L,
                1,
                1500L,
                "missing_event",
                9_999L,
                1200L
        );
        FlowInstance otherTenant = new FlowInstance(
                "exec-tenant-b",
                "AwaitApproval",
                "corr-b",
                "tenant-b",
                "actor-b",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of(),
                "Approved",
                1000L,
                2000L,
                0,
                null,
                null,
                2000L,
                1200L
        );

        store.save(future);
        store.save(otherTenant);
        store.save(eligible);

        List<FlowInstance> rows = store.findWaitingEligibleToResume("tenant-a", 3000L, 10);
        assertEquals(List.of("exec-eligible"), rows.stream().map(FlowInstance::executionId).toList());
    }

    @Test
    void findStaleWaitingRespectsThresholdAndOrdering() {
        FlowInstance staleA = new FlowInstance(
                "exec-stale-a",
                "AwaitApproval",
                "corr-s1",
                "tenant-a",
                "actor-a",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of(),
                "Approved",
                1000L,
                6000L,
                0,
                null,
                null,
                5000L,
                2_000L
        );
        FlowInstance staleB = new FlowInstance(
                "exec-stale-b",
                "AwaitApproval",
                "corr-s2",
                "tenant-a",
                "actor-a",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of(),
                "Approved",
                1000L,
                7000L,
                0,
                null,
                null,
                5000L,
                3_000L
        );
        FlowInstance fresh = new FlowInstance(
                "exec-fresh",
                "AwaitApproval",
                "corr-s3",
                "tenant-a",
                "actor-a",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of(),
                "Approved",
                1000L,
                8000L,
                0,
                null,
                null,
                5000L,
                10_000L
        );

        store.save(staleB);
        store.save(fresh);
        store.save(staleA);

        List<FlowInstance> rows = store.findStaleWaiting("tenant-a", 5000L, 10, 0);
        assertEquals(List.of("exec-stale-a", "exec-stale-b"), rows.stream().map(FlowInstance::executionId).toList());
    }

    @Test
    void tenantScopedReadQueriesReturnOnlyTenantRows() {
        FlowInstance tenantAWaiting = new FlowInstance(
                "exec-a1",
                "CreateUser",
                "corr-shared",
                "tenant-a",
                "actor-a",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("x", 1),
                "UserApproved",
                1000L,
                4000L
        );
        FlowInstance tenantARunning = new FlowInstance(
                "exec-a2",
                "CreateUser",
                "corr-shared",
                "tenant-a",
                "actor-a",
                2,
                FlowInstanceStatus.RUNNING,
                Map.of("x", 2),
                null,
                1000L,
                5000L
        );
        FlowInstance tenantB = new FlowInstance(
                "exec-b1",
                "CreateUser",
                "corr-shared",
                "tenant-b",
                "actor-b",
                3,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("x", 3),
                "UserApproved",
                1000L,
                6000L
        );
        FlowInstance tenantAFailed = new FlowInstance(
                "exec-a3",
                "CreateUser",
                "corr-failed",
                "tenant-a",
                "actor-a",
                4,
                FlowInstanceStatus.FAILED_PERMANENT,
                Map.of(),
                null,
                1000L,
                7000L
        );
        FlowInstance tenantAStuck = new FlowInstance(
                "exec-a4",
                "AwaitApproval",
                "corr-stuck",
                "tenant-a",
                "actor-a",
                5,
                FlowInstanceStatus.STUCK,
                Map.of(),
                "UserApproved",
                1000L,
                8000L
        );
        store.save(tenantAWaiting);
        store.save(tenantARunning);
        store.save(tenantB);
        store.save(tenantAFailed);
        store.save(tenantAStuck);

        List<FlowInstance> recent = store.findRecent("tenant-a", 50, 0);
        List<FlowInstance> waiting = store.findWaiting("tenant-a", 50, 0);
        List<FlowInstance> byCorrelation = store.findByCorrelationId("tenant-a", "corr-shared");

        assertEquals(List.of("exec-a4", "exec-a3", "exec-a2", "exec-a1"), recent.stream().map(FlowInstance::executionId).toList());
        assertEquals(List.of("exec-a1"), waiting.stream().map(FlowInstance::executionId).toList());
        assertEquals(List.of("exec-a2", "exec-a1"), byCorrelation.stream().map(FlowInstance::executionId).toList());
    }

    @Test
    void summaryQueriesDoNotRequireStateJsonDeserialization() {
        FlowInstance tenantAWaiting = new FlowInstance(
                "exec-a1",
                "CreateUser",
                "corr-shared",
                "tenant-a",
                "actor-a",
                1,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("huge", "x".repeat(20000)),
                "UserApproved",
                1000L,
                4000L,
                2,
                2000L,
                "missing_event",
                5000L,
                1500L
        );
        FlowInstance tenantARunning = new FlowInstance(
                "exec-a2",
                "CreateUser",
                "corr-shared",
                "tenant-a",
                "actor-a",
                2,
                FlowInstanceStatus.RUNNING,
                Map.of("huge", "y".repeat(20000)),
                null,
                1000L,
                5000L
        );
        FlowInstance tenantB = new FlowInstance(
                "exec-b1",
                "CreateUser",
                "corr-shared",
                "tenant-b",
                "actor-b",
                3,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of("huge", "z".repeat(20000)),
                "UserApproved",
                1000L,
                6000L
        );
        store.save(tenantAWaiting);
        store.save(tenantARunning);
        store.save(tenantB);

        List<ExecutionSummary> recent = store.listSummaries("tenant-a", "recent", 50, 0);
        List<ExecutionSummary> waiting = store.listSummaries("tenant-a", "waiting", 50, 0);
        List<ExecutionSummary> byCorrelation = store.listByCorrelation("tenant-a", "corr-shared", 50, 0);
        List<ExecutionSummary> failures = store.listFailureSummaries("tenant-a", 50, 0);
        List<ExecutionSummary> stuck = store.listStuckSummaries("tenant-a", 50, 0);

        assertEquals(List.of("exec-a2", "exec-a1"), recent.stream().map(ExecutionSummary::executionId).toList());
        assertEquals(List.of("exec-a1"), waiting.stream().map(ExecutionSummary::executionId).toList());
        assertEquals(List.of("exec-a2", "exec-a1"), byCorrelation.stream().map(ExecutionSummary::executionId).toList());
        assertEquals(2, waiting.get(0).resumeAttemptCount());
        assertEquals("missing_event", waiting.get(0).lastResumeErrorCode());
        assertEquals(List.of(), failures.stream().map(ExecutionSummary::executionId).toList());
        assertEquals(List.of(), stuck.stream().map(ExecutionSummary::executionId).toList());
    }

}
