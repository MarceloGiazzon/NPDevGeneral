package com.npdev.adapters.audit.postgres;

import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.ports.AuditQuery;
import com.npdev.test.postgres.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresAuditLogStoreTest {
    private static final String[] SCHEMA_SQL = new String[]{
            """
            CREATE TABLE IF NOT EXISTS npdev_audit_log (
                audit_id TEXT PRIMARY KEY,
                ts_ms BIGINT NOT NULL,
                tenant_id TEXT NOT NULL,
                actor_id TEXT NOT NULL,
                roles TEXT NOT NULL,
                action TEXT NOT NULL,
                resource_type TEXT NOT NULL,
                resource_id TEXT NOT NULL,
                outcome TEXT NOT NULL,
                reason_code TEXT NOT NULL,
                tags_json TEXT NOT NULL,
                meta_json TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_npdev_audit_tenant_ts ON npdev_audit_log(tenant_id, ts_ms DESC)",
            "CREATE INDEX IF NOT EXISTS idx_npdev_audit_tenant_action ON npdev_audit_log(tenant_id, action)",
            "CREATE INDEX IF NOT EXISTS idx_npdev_audit_tenant_actor ON npdev_audit_log(tenant_id, actor_id)",
            "CREATE INDEX IF NOT EXISTS idx_npdev_audit_tenant_resource ON npdev_audit_log(tenant_id, resource_type, resource_id)"
    };

    private PostgresAuditLogStore store;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PostgresTestSupport.dataSource();
        PostgresTestSupport.execute(dataSource, SCHEMA_SQL);
        PostgresTestSupport.truncate(dataSource, "npdev_audit_log");
        store = new PostgresAuditLogStore(dataSource);
    }

    @Test
    void appendAndSearchAreTenantScopedAndDeterministic() {
        store.append(record("a-1", 1000L, "tenant-a", "actor-a", "EXECUTE_FLOW", "FLOW", "CreateUser"));
        store.append(record("a-2", 2000L, "tenant-a", "actor-a", "READ_TRACE", "TRACE", "exec-1"));
        store.append(record("b-1", 3000L, "tenant-b", "actor-b", "EXECUTE_FLOW", "FLOW", "CreateUser"));
        store.append(record("a-3", 2000L, "tenant-a", "actor-a", "READ_TRACE", "TRACE", "exec-2"));

        var tenantA = store.search(new AuditQuery("tenant-a", null, null, null, null, null, null, 50, 0));
        assertEquals(List.of("a-3", "a-2", "a-1"), tenantA.stream().map(AuditRecord::auditId).toList());
        assertTrue(tenantA.stream().allMatch(record -> "tenant-a".equals(record.tenantId())));
    }

    @Test
    void searchSupportsActionActorAndTimeFilters() {
        store.append(record("a-1", 1000L, "tenant-a", "actor-a", "EXECUTE_FLOW", "FLOW", "CreateUser"));
        store.append(record("a-2", 2000L, "tenant-a", "actor-a", "READ_TRACE", "TRACE", "exec-1"));
        store.append(record("a-3", 3000L, "tenant-a", "actor-b", "READ_TRACE", "TRACE", "exec-2"));

        var filtered = store.search(new AuditQuery(
                "tenant-a",
                "actor-a",
                "READ_TRACE",
                null,
                null,
                1500L,
                2500L,
                50,
                0
        ));
        assertEquals(1, filtered.size());
        assertEquals("a-2", filtered.get(0).auditId());
    }

    private static AuditRecord record(
            String id,
            long ts,
            String tenant,
            String actor,
            String action,
            String resourceType,
            String resourceId
    ) {
        return new AuditRecord(
                id,
                ts,
                tenant,
                actor,
                Set.of("USER"),
                action,
                resourceType,
                resourceId,
                "ALLOW",
                "ok",
                Map.of("source", "test"),
                Map.of("status", "OK")
        );
    }

}
