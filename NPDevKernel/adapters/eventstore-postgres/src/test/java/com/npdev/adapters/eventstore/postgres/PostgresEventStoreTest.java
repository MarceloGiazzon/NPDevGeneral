package com.npdev.adapters.eventstore.postgres;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.events.EventMetaSummary;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresEventStoreTest {
    private static final String[] SCHEMA_SQL = new String[]{
            """
            CREATE TABLE IF NOT EXISTS npdev_event_store (
                event_id TEXT PRIMARY KEY,
                event_name TEXT NOT NULL,
                correlation_id TEXT NOT NULL,
                causation_id TEXT NOT NULL,
                flow_name TEXT NOT NULL,
                step_index INTEGER NOT NULL,
                timestamp_ms BIGINT NOT NULL,
                payload_json TEXT NOT NULL,
                metadata_json TEXT NOT NULL,
                tenant_id TEXT,
                actor_id TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_npdev_event_store_event_name ON npdev_event_store(event_name)",
            "CREATE INDEX IF NOT EXISTS idx_npdev_event_store_correlation_id ON npdev_event_store(correlation_id)",
            "CREATE INDEX IF NOT EXISTS idx_npdev_event_store_event_correlation ON npdev_event_store(event_name, correlation_id, timestamp_ms, event_id)",
            "CREATE INDEX IF NOT EXISTS idx_npdev_event_store_tenant ON npdev_event_store(tenant_id)"
    };

    private DataSource dataSource;
    private PostgresEventStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource jdbcDataSource = new JdbcDataSource();
        jdbcDataSource.setURL("jdbc:h2:mem:eventstore_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbcDataSource.setUser("sa");
        jdbcDataSource.setPassword("sa");
        dataSource = jdbcDataSource;
        executeSchema(dataSource, SCHEMA_SQL);
        store = new PostgresEventStore(dataSource);
    }

    @Test
    void appendAndFindFirstUsesDeterministicOrdering() {
        EventEnvelope later = new EventEnvelope(
                "evt-2",
                "InvoiceIssued",
                2000L,
                Map.of("invoiceId", "inv-1"),
                "corr-1",
                "exec-1",
                "IssueInvoice",
                1
        );
        EventEnvelope earlierHigherId = new EventEnvelope(
                "evt-1",
                "InvoiceIssued",
                1000L,
                Map.of("invoiceId", "inv-1"),
                "corr-1",
                "exec-1",
                "IssueInvoice",
                0
        );
        EventEnvelope earliestLowerId = new EventEnvelope(
                "evt-0",
                "InvoiceIssued",
                1000L,
                Map.of("invoiceId", "inv-1"),
                "corr-1",
                "exec-1",
                "IssueInvoice",
                2
        );

        store.append(later);
        store.append(earlierHigherId);
        store.append(earliestLowerId);

        EventEnvelope first = store.findFirst("InvoiceIssued", "corr-1").orElseThrow();
        assertEquals("evt-0", first.eventId());
        assertEquals("InvoiceIssued", first.eventName());
    }

    @Test
    void readQueriesByCorrelationAndEventName() {
        EventEnvelope eventA = EventEnvelope.of(
                "InvoiceIssued",
                Map.of("invoiceId", "inv-a"),
                "corr-a",
                "exec-a",
                "IssueInvoice",
                0,
                Map.of("source", "flow"),
                "tenant-a",
                "actor-a"
        );
        EventEnvelope eventB = EventEnvelope.of(
                "InvoiceApproved",
                Map.of("invoiceId", "inv-a"),
                "corr-a",
                "exec-a",
                "IssueInvoice",
                1,
                Map.of()
        );
        EventEnvelope eventC = EventEnvelope.of(
                "InvoiceIssued",
                Map.of("invoiceId", "inv-b"),
                "corr-b",
                "exec-b",
                "IssueInvoice",
                0,
                Map.of()
        );

        store.append(eventA);
        store.append(eventB);
        store.append(eventC);

        List<EventEnvelope> byCorrelation = store.readByCorrelation("corr-a");
        List<EventEnvelope> byEventName = store.readByEventName("InvoiceIssued");

        assertEquals(2, byCorrelation.size());
        assertEquals(2, byEventName.size());
        assertTrue(byCorrelation.stream().allMatch(event -> "corr-a".equals(event.correlationId())));
        assertTrue(byEventName.stream().allMatch(event -> "InvoiceIssued".equals(event.eventName())));
        assertTrue(byCorrelation.stream().anyMatch(event -> event.payload().containsKey("_meta")));
        assertTrue(byCorrelation.stream().anyMatch(event -> "tenant-a".equals(event.tenantId())));
    }

    @Test
    void tenantScopedReadQueriesFilterRows() {
        EventEnvelope tenantA1 = new EventEnvelope(
                "evt-a1",
                "InvoiceIssued",
                1000L,
                Map.of("invoiceId", "inv-a1"),
                "corr-shared",
                "exec-a",
                "IssueInvoice",
                0,
                "tenant-a",
                "actor-a"
        );
        EventEnvelope tenantA2 = new EventEnvelope(
                "evt-a2",
                "InvoiceIssued",
                2000L,
                Map.of("invoiceId", "inv-a2"),
                "corr-shared",
                "exec-a",
                "IssueInvoice",
                1,
                "tenant-a",
                "actor-a"
        );
        EventEnvelope tenantB1 = new EventEnvelope(
                "evt-b1",
                "InvoiceIssued",
                3000L,
                Map.of("invoiceId", "inv-b1"),
                "corr-shared",
                "exec-b",
                "IssueInvoice",
                0,
                "tenant-b",
                "actor-b"
        );

        store.append(tenantA1);
        store.append(tenantA2);
        store.append(tenantB1);

        List<EventEnvelope> byCorrelation = store.findByCorrelationId("tenant-a", "corr-shared", 50, 0);
        List<EventEnvelope> byName = store.findByEventName("tenant-a", "InvoiceIssued", 50, 0);
        EventEnvelope event = store.findByEventId("tenant-a", tenantA1.eventId()).orElseThrow();

        assertEquals(List.of(tenantA1.eventId(), tenantA2.eventId()),
                byCorrelation.stream().map(EventEnvelope::eventId).toList());
        assertEquals(List.of(tenantA2.eventId(), tenantA1.eventId()),
                byName.stream().map(EventEnvelope::eventId).toList());
        assertEquals(tenantA1.eventId(), event.eventId());
        assertTrue(store.findByEventId("tenant-a", tenantB1.eventId()).isEmpty());
    }

    @Test
    void findFirstSupportsTenantScopedLookupForKernelAwaitSafety() {
        EventEnvelope tenantB = new EventEnvelope(
                "evt-b",
                "InvoiceApproved",
                1000L,
                Map.of("status", "TENANT_B"),
                "corr-collision",
                "cause-b",
                "external",
                0,
                "tenant-b",
                "actor-b"
        );
        EventEnvelope tenantA = new EventEnvelope(
                "evt-a",
                "InvoiceApproved",
                2000L,
                Map.of("status", "TENANT_A"),
                "corr-collision",
                "cause-a",
                "external",
                0,
                "tenant-a",
                "actor-a"
        );
        store.append(tenantB);
        store.append(tenantA);

        EventEnvelope firstForTenantA = store.findFirst("InvoiceApproved", "corr-collision", "tenant-a").orElseThrow();
        assertEquals("evt-a", firstForTenantA.eventId());
        assertEquals(List.of("evt-a"),
                store.readByCorrelation("corr-collision", "tenant-a").stream().map(EventEnvelope::eventId).toList());
        assertEquals(List.of("evt-a"),
                store.readByEventName("InvoiceApproved", "tenant-a").stream().map(EventEnvelope::eventId).toList());
    }

    @Test
    void listByCorrelationReturnsMetadataOnlyAndDeterministicOrdering() {
        EventEnvelope tenantA1 = new EventEnvelope(
                "evt-a1",
                "InvoiceIssued",
                1000L,
                Map.of("huge", "x".repeat(20000)),
                "corr-shared",
                "exec-a",
                "IssueInvoice",
                0,
                "tenant-a",
                "actor-a"
        );
        EventEnvelope tenantA2 = new EventEnvelope(
                "evt-a2",
                "InvoiceApproved",
                2000L,
                Map.of("huge", "y".repeat(20000)),
                "corr-shared",
                "exec-a",
                "IssueInvoice",
                1,
                "tenant-a",
                "actor-a"
        );
        EventEnvelope tenantB = new EventEnvelope(
                "evt-b1",
                "InvoiceIssued",
                3000L,
                Map.of("huge", "z".repeat(20000)),
                "corr-shared",
                "exec-b",
                "IssueInvoice",
                0,
                "tenant-b",
                "actor-b"
        );
        store.append(tenantA2);
        store.append(tenantA1);
        store.append(tenantB);

        List<EventMetaSummary> summaries = store.listByCorrelation("tenant-a", "corr-shared", 50, 0);

        assertEquals(List.of("evt-a1", "evt-a2"), summaries.stream().map(EventMetaSummary::eventId).toList());
        assertTrue(summaries.stream().allMatch(summary -> "tenant-a".equals(summary.tenantId())));
    }

    private static void executeSchema(DataSource dataSource, String[] statements) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            for (String raw : statements) {
                String sql = raw.trim();
                if (sql.isEmpty()) {
                    continue;
                }
                statement.execute(sql);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed preparing event store schema", exception);
        }
    }
}
