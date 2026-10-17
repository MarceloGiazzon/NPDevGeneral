package com.npdev.adapters.webhook.http;

import com.npdev.kernel.storage.sql.H2Dialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the delivery-record table's claim/attempt shape (R6.1's "reusing the schedule table's
 * claim/attempt shape") against a real H2 connection -- the local machine's test profile allows
 * H2/SQL Server and keeps Postgres/MySQL/Docker off (see {@code scripts/policy/local-test-profile.json}),
 * so this exercises the one engine the DDL is proven against here. The DDL is built entirely from
 * the existing {@link com.npdev.kernel.storage.sql.SqlDialect} abstraction (no new dialect method),
 * mirroring {@code ScheduledEventSql}'s insert/claim/markProcessed/markFailed shape one-for-one.
 */
class WebhookDeliveryRecordStoreTest {

    private Connection connection;
    private WebhookDeliveryRecordStore store;

    @BeforeEach
    void openConnection() throws SQLException {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        store = new WebhookDeliveryRecordStore(H2Dialect.INSTANCE);
        store.ensureSchema(connection);
    }

    @AfterEach
    void closeConnection() throws SQLException {
        connection.close();
    }

    @Test
    void ensureSchemaIsIdempotent() throws SQLException {
        // A second call must not fail -- the guarded DDL must be safe to re-run, the same idempotency
        // requirement every internal table in this repo (SqlDialect.guardedCreateTable) carries.
        store.ensureSchema(connection);
        store.ensureSchema(connection);
    }

    @Test
    void insertStartsAtPendingWithZeroAttempts() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.insertPending(connection, id, "hooks.example.com", "https://hooks.example.com/x", "{\"n\":1}", Instant.now());

        Row row = readRow(id);
        assertEquals(WebhookDeliveryRecordStore.STATUS_PENDING, row.status);
        assertEquals(0, row.attemptCount);
        assertEquals("hooks.example.com", row.destinationHost);
    }

    @Test
    void claimIsAnOptimisticUpdateThatOnlyOneCallerCanWin() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.insertPending(connection, id, "hooks.example.com", "https://hooks.example.com/x", "{}", Instant.now());

        boolean firstClaim = store.claim(connection, id, WebhookDeliveryRecordStore.STATUS_PENDING, "SENDING", Instant.now());
        boolean secondClaim = store.claim(connection, id, WebhookDeliveryRecordStore.STATUS_PENDING, "SENDING", Instant.now());

        assertTrue(firstClaim, "the first claim from PENDING must win");
        assertFalse(secondClaim, "a second claim against the same fromStatus must lose -- the row already moved");

        Row row = readRow(id);
        assertEquals("SENDING", row.status);
        assertEquals(1, row.attemptCount, "claim() bumps attempt_count exactly once, on the winning call only");
    }

    @Test
    void markDeliveredSetsTerminalStatusAndResponseStatus() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.insertPending(connection, id, "hooks.example.com", "https://hooks.example.com/x", "{}", Instant.now());

        store.markDelivered(connection, id, 200, Instant.now());

        Row row = readRow(id);
        assertEquals(WebhookDeliveryRecordStore.STATUS_DELIVERED, row.status);
        assertEquals(200, row.responseStatus);
        assertEquals(1, row.attemptCount);
    }

    @Test
    void markFailedSetsTerminalStatusAndLastError() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.insertPending(connection, id, "hooks.example.com", "https://hooks.example.com/x", "{}", Instant.now());

        store.markFailed(connection, id, "connection refused", Instant.now());

        Row row = readRow(id);
        assertEquals(WebhookDeliveryRecordStore.STATUS_FAILED, row.status);
        assertEquals("connection refused", row.lastError);
        assertEquals(1, row.attemptCount);
    }

    @Test
    void queuedRowIsDueOnlyOnceItsNextAttemptArrives() throws SQLException {
        Instant now = Instant.parse("2026-10-16T12:00:00Z");
        String id = UUID.randomUUID().toString();
        store.insertQueued(connection, id, "t1", "hooks.example.com", "https://hooks.example.com/x", "{\"n\":1}", "WidgetCreated", now);

        java.util.List<WebhookDeliveryRecordStore.DueDelivery> due = store.findDue(connection, now, 10);
        assertEquals(1, due.size());
        assertEquals(id, due.get(0).id());
        assertEquals("WidgetCreated", due.get(0).eventName());
        assertEquals("{\"n\":1}", due.get(0).payloadJson());
        assertTrue(store.findDue(connection, now.minusSeconds(1), 10).isEmpty());
    }

    @Test
    void claimForSendCountsTheAttemptAndRescheduleMovesTheNextAttempt() throws SQLException {
        Instant now = Instant.parse("2026-10-16T12:00:00Z");
        String id = UUID.randomUUID().toString();
        store.insertQueued(connection, id, "t1", "hooks.example.com", "https://hooks.example.com/x", "{}", null, now);

        assertTrue(store.claimForSend(connection, id, now));
        assertFalse(store.claimForSend(connection, id, now), "a second worker must lose the claim");
        assertTrue(store.findDue(connection, now.plusSeconds(3600), 10).isEmpty(), "SENDING rows are not due");

        store.reschedule(connection, id, "HTTP 503", now.plusSeconds(30), now);
        assertTrue(store.findDue(connection, now.plusSeconds(29), 10).isEmpty());
        assertEquals(1, store.findDue(connection, now.plusSeconds(30), 10).size());
        Row row = readRow(id);
        assertEquals(WebhookDeliveryRecordStore.STATUS_PENDING, row.status);
        assertEquals(1, row.attemptCount);
        assertEquals("HTTP 503", row.lastError);
    }

    @Test
    void releaseStaleReturnsACrashedWorkersRowToPending() throws SQLException {
        Instant now = Instant.parse("2026-10-16T12:00:00Z");
        String id = UUID.randomUUID().toString();
        store.insertQueued(connection, id, "t1", "hooks.example.com", "https://hooks.example.com/x", "{}", null, now);
        assertTrue(store.claimForSend(connection, id, now));

        assertEquals(0, store.releaseStale(connection, now.minusSeconds(600), now.plusSeconds(1)));
        assertEquals(1, store.releaseStale(connection, now.plusSeconds(600), now.plusSeconds(601)));
        assertEquals(1, store.findDue(connection, now.plusSeconds(601), 10).size());
    }

    @Test
    void redeliverRequeuesAFailedRowWithTheSameIdAndRefusesPendingOnes() throws SQLException {
        Instant now = Instant.parse("2026-10-16T12:00:00Z");
        String id = UUID.randomUUID().toString();
        store.insertQueued(connection, id, "t1", "hooks.example.com", "https://hooks.example.com/x", "{}", "E", now);
        assertFalse(store.redeliver(connection, "t1", id, now), "a PENDING row is already queued");

        assertTrue(store.claimForSend(connection, id, now));
        store.finishFailed(connection, id, 400, "HTTP 400", now);
        assertEquals(WebhookDeliveryRecordStore.STATUS_FAILED, readRow(id).status);
        assertTrue(store.findDue(connection, now.plusSeconds(99999), 10).isEmpty(), "dead letters are never due");

        assertTrue(store.redeliver(connection, "t1", id, now.plusSeconds(5)));
        java.util.List<WebhookDeliveryRecordStore.DueDelivery> due = store.findDue(connection, now.plusSeconds(5), 10);
        assertEquals(1, due.size());
        assertEquals(id, due.get(0).id(), "redelivery keeps the delivery id so receivers can de-duplicate");

        assertTrue(store.claimForSend(connection, id, now.plusSeconds(6)));
        store.finishDelivered(connection, id, 200, now.plusSeconds(7));
        assertTrue(store.redeliver(connection, "t1", id, now.plusSeconds(8)), "a DELIVERED row can be replayed on demand");
    }

    @Test
    void listNeverExposesThePayloadAndFiltersByStatus() throws SQLException {
        Instant now = Instant.parse("2026-10-16T12:00:00Z");
        String id = UUID.randomUUID().toString();
        store.insertQueued(connection, id, "t1", "hooks.example.com", "https://hooks.example.com/x", "{\"email\":\"a@b.c\"}", "E", now);

        java.util.List<java.util.Map<String, Object>> all = store.list(connection, "t1", null, 10);
        assertEquals(1, all.size());
        assertFalse(all.get(0).containsKey("payload"));
        assertFalse(all.toString().contains("a@b.c"));
        assertEquals("E", all.get(0).get("event_name"));
        assertEquals(1, store.list(connection, "t1", WebhookDeliveryRecordStore.STATUS_PENDING, 10).size());
        assertTrue(store.list(connection, "t1", WebhookDeliveryRecordStore.STATUS_FAILED, 10).isEmpty());
        assertEquals("{\"email\":\"a@b.c\"}", store.payload(connection, "t1", id).orElseThrow());
        assertTrue(store.payload(connection, "t1", "missing").isEmpty());
    }

    @Test
    void tenantFacingReadsAndReplaysNeverCrossTenants() throws SQLException {
        Instant now = Instant.parse("2026-10-16T12:00:00Z");
        String id = UUID.randomUUID().toString();
        store.insertQueued(connection, id, "t1", "hooks.example.com", "https://hooks.example.com/x", "{\"n\":1}", "E", now);
        assertTrue(store.claimForSend(connection, id, now));
        store.finishFailed(connection, id, 400, "HTTP 400", now);

        assertTrue(store.list(connection, "t2", null, 10).isEmpty(), "another tenant lists nothing");
        assertTrue(store.payload(connection, "t2", id).isEmpty(), "another tenant cannot read the payload");
        assertFalse(store.redeliver(connection, "t2", id, now), "another tenant cannot replay it");
        assertEquals(WebhookDeliveryRecordStore.STATUS_FAILED, readRow(id).status);

        assertEquals(1, store.list(connection, "t1", null, 10).size());
        assertTrue(store.redeliver(connection, "t1", id, now));
        assertEquals("t1", store.findDue(connection, now, 10).get(0).tenantId(),
                "the platform-wide worker scan sees every tenant and reports whose it is");
    }

    private Row readRow(String id) throws SQLException {
        String sql = "SELECT status, attempt_count, destination_host, response_status, last_error FROM "
                + WebhookDeliveryRecordStore.TABLE + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next(), "expected a row for id " + id);
                Row row = new Row();
                row.status = rs.getString("status");
                row.attemptCount = rs.getInt("attempt_count");
                row.destinationHost = rs.getString("destination_host");
                row.responseStatus = rs.getObject("response_status") == null ? null : rs.getInt("response_status");
                row.lastError = rs.getString("last_error");
                return row;
            }
        }
    }

    private static final class Row {
        String status;
        int attemptCount;
        String destinationHost;
        Integer responseStatus;
        String lastError;
    }
}
