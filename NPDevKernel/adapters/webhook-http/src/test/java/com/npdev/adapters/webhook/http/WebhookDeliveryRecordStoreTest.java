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
