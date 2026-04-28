package com.npdev.adapters.audit.inproc;

import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.ports.AuditQuery;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcAuditLogStoreTest {

    @Test
    void searchIsTenantScopedAndDeterministic() {
        InProcAuditLogStore store = new InProcAuditLogStore();
        store.append(record("a-1", 1000L, "tenant-a", "actor-a", "EXECUTE_FLOW", "FLOW", "CreateUser"));
        store.append(record("a-2", 2000L, "tenant-a", "actor-a", "READ_TRACE", "TRACE", "exec-1"));
        store.append(record("b-1", 3000L, "tenant-b", "actor-b", "EXECUTE_FLOW", "FLOW", "CreateUser"));
        store.append(record("a-3", 2000L, "tenant-a", "actor-a", "READ_TRACE", "TRACE", "exec-2"));

        var query = new AuditQuery("tenant-a", null, null, null, null, null, null, 50, 0);
        var rows = store.search(query);

        assertEquals(3, rows.size());
        assertEquals("a-3", rows.get(0).auditId());
        assertEquals("a-2", rows.get(1).auditId());
        assertEquals("a-1", rows.get(2).auditId());
        assertTrue(rows.stream().allMatch(record -> "tenant-a".equals(record.tenantId())));
    }

    @Test
    void boundedStoreEvictsOldestRecords() {
        InProcAuditLogStore store = new InProcAuditLogStore(2);
        store.append(record("a-1", 1000L, "tenant-a", "actor-a", "EXECUTE_FLOW", "FLOW", "F1"));
        store.append(record("a-2", 2000L, "tenant-a", "actor-a", "EXECUTE_FLOW", "FLOW", "F2"));
        store.append(record("a-3", 3000L, "tenant-a", "actor-a", "EXECUTE_FLOW", "FLOW", "F3"));

        var rows = store.search(new AuditQuery("tenant-a", null, null, null, null, null, null, 10, 0));
        assertEquals(2, rows.size());
        assertEquals("a-3", rows.get(0).auditId());
        assertEquals("a-2", rows.get(1).auditId());
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

