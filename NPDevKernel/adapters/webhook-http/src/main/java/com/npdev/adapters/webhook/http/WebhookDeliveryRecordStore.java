package com.npdev.adapters.webhook.http;

import com.npdev.kernel.storage.sql.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * Optional delivery-record persistence for {@link HttpWebhookCapabilityAdapter}: one row per
 * attempted outbound webhook, in the same claim/attempt shape as {@code npdev_scheduled_event}
 * (status + attempt_count, {@code ScheduledEventSql}'s {@code claim()}/{@code markProcessed()}/
 * {@code markFailed()} idiom) -- R6.1's "delivery-record table reusing the schedule table's
 * claim/attempt shape."
 *
 * <p><b>Deliberately self-contained, not registered in {@code NpdevInternalTables}.</b> That
 * registry (and the generator's {@code SchemaRealizationEmitter}, which is what actually makes a
 * table appear in a generated app's schema) are shared surfaces outside this adapter's module, and
 * -- per this round's file-ownership split -- a concurrently running session owns the kernel
 * cron/scheduler path and was, at the same time, adding its own new internal table (a cron-fire
 * claim table) to that exact registry and its closed-set source-of-truth test
 * ({@code NpdevInternalTablesSourceOfTruthTest}). Two sessions editing the same two files in the
 * same live working tree (not separate branches) risk silently clobbering each other with no merge.
 * So this table manages its own schema, using nothing but the existing {@link SqlDialect}
 * abstraction (no new dialect method needed -- {@link SqlDialect#guardedCreateTable},
 * {@link SqlDialect#keyableTextColumnType()}, {@link SqlDialect#defaultableTextColumnType()} and
 * {@link SqlDialect#timestampColumnType()} already cover everything this shape needs). Wiring it
 * into the central registry so {@code npdev_webhook_delivery} appears in every generated app's
 * schema is follow-up work for whoever owns that surface next.
 *
 * <p>Wired into the adapter as an OPTIONAL constructor argument (like {@code HttpExternalAiCapabilityAdapter}
 * keeps an in-memory-only verdict map by default): a caller with no {@link Connection} handy gets
 * the exact same send behaviour, unaudited.
 */
public final class WebhookDeliveryRecordStore {

    public static final String TABLE = "npdev_webhook_delivery";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_FAILED = "FAILED";

    private final SqlDialect dialect;

    public WebhookDeliveryRecordStore(SqlDialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    /** Idempotent -- safe to call every time a connection is opened, like a repeatable migration. */
    public void ensureSchema(Connection connection) throws SQLException {
        String createTable = "CREATE TABLE " + TABLE + " ("
                + "id " + dialect.keyableTextColumnType() + " PRIMARY KEY, "
                + "destination_host " + dialect.keyableTextColumnType() + " NOT NULL, "
                + "url " + dialect.portableColumnType("TEXT") + " NOT NULL, "
                + "payload " + dialect.jsonColumnType() + " NOT NULL, "
                + "status " + dialect.defaultableTextColumnType() + " NOT NULL DEFAULT '" + STATUS_PENDING + "', "
                + "attempt_count INTEGER NOT NULL DEFAULT 0, "
                + "response_status INTEGER, "
                + "last_error " + dialect.portableColumnType("TEXT") + ", "
                + "created_at " + dialect.timestampColumnType() + " NOT NULL, "
                + "updated_at " + dialect.timestampColumnType() + " NOT NULL, "
                + "delivered_at " + dialect.timestampColumnType() + ", "
                + "event_name " + dialect.keyableTextColumnType() + ", "
                + "next_attempt_at " + dialect.timestampColumnType()
                + ")";
        String createIndex = "CREATE INDEX ix_npdev_webhook_delivery_status ON " + TABLE + " (status)";
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute(dialect.guardedCreateTable(TABLE, createTable));
            statement.execute(dialect.guardedCreateIndex("ix_npdev_webhook_delivery_status", TABLE, createIndex));
            // INT-4: a table created by an earlier build lacks the outbox columns.
            statement.execute(dialect.guardedAddColumn(TABLE, "event_name",
                    "ALTER TABLE " + TABLE + " ADD COLUMN event_name " + dialect.keyableTextColumnType()));
            statement.execute(dialect.guardedAddColumn(TABLE, "next_attempt_at",
                    "ALTER TABLE " + TABLE + " ADD COLUMN next_attempt_at " + dialect.timestampColumnType()));
            statement.execute(dialect.guardedCreateIndex("ix_npdev_webhook_delivery_due", TABLE,
                    "CREATE INDEX ix_npdev_webhook_delivery_due ON " + TABLE + " (status, next_attempt_at)"));
        }
    }

    /** Insert a new delivery attempt in {@link #STATUS_PENDING}, {@code attempt_count = 0}. */
    public void insertPending(Connection connection, String id, String destinationHost, String url, String payloadJson,
            Instant now) throws SQLException {
        String sql = "INSERT INTO " + TABLE + " ("
                + "id, destination_host, url, payload, status, attempt_count, created_at, updated_at"
                + ") VALUES (?, ?, ?, ?, ?, 0, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, destinationHost);
            statement.setString(3, url);
            statement.setString(4, payloadJson);
            statement.setString(5, STATUS_PENDING);
            statement.setTimestamp(6, Timestamp.from(now));
            statement.setTimestamp(7, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    /**
     * The claim step: an optimistic {@code UPDATE ... WHERE id = ? AND status = ?}, the same shape
     * as {@code ScheduledEventSql.claim()}. Returns whether this call won the claim (0 rows updated
     * means someone else already moved the row past {@code fromStatus}).
     */
    public boolean claim(Connection connection, String id, String fromStatus, String toStatus, Instant now)
            throws SQLException {
        String sql = "UPDATE " + TABLE + " SET status = ?, attempt_count = attempt_count + 1, updated_at = ? "
                + "WHERE id = ? AND status = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toStatus);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setString(3, id);
            statement.setString(4, fromStatus);
            return statement.executeUpdate() == 1;
        }
    }

    /** Mirrors {@code ScheduledEventSql.markProcessed()}: terminal success, attempt_count bumped. */
    public void markDelivered(Connection connection, String id, int responseStatus, Instant now) throws SQLException {
        String sql = "UPDATE " + TABLE + " SET status = ?, attempt_count = attempt_count + 1, "
                + "response_status = ?, delivered_at = ?, updated_at = ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, STATUS_DELIVERED);
            statement.setInt(2, responseStatus);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setString(5, id);
            statement.executeUpdate();
        }
    }

    /** Mirrors {@code ScheduledEventSql.markFailed()}: terminal failure, attempt_count bumped. */
    public void markFailed(Connection connection, String id, String errorMessage, Instant now) throws SQLException {
        String sql = "UPDATE " + TABLE + " SET status = ?, attempt_count = attempt_count + 1, "
                + "last_error = ?, updated_at = ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, STATUS_FAILED);
            statement.setString(2, errorMessage == null ? null : errorMessage.substring(0, Math.min(errorMessage.length(), 4000)));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setString(4, id);
            statement.executeUpdate();
        }
    }

    /** INT-4: claimed by a delivery worker; a row stuck here is returned to PENDING by {@link #releaseStale}. */
    public static final String STATUS_SENDING = "SENDING";

    /** INT-4: a queued delivery that is due now. The payload is the already-serialized body, signed at send time. */
    public record DueDelivery(String id, String url, String payloadJson, int attemptCount, String eventName) {
    }

    /**
     * INT-4: queue a delivery durably -- {@link #STATUS_PENDING}, {@code attempt_count = 0}, due at
     * {@code now}. A background worker (see {@link #findDue}) sends it; the caller never waits on the remote.
     */
    public void insertQueued(Connection connection, String id, String destinationHost, String url, String payloadJson,
            String eventName, Instant now) throws SQLException {
        String sql = "INSERT INTO " + TABLE + " ("
                + "id, destination_host, url, payload, status, attempt_count, event_name, next_attempt_at, created_at, updated_at"
                + ") VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, destinationHost);
            statement.setString(3, url);
            statement.setString(4, payloadJson);
            statement.setString(5, STATUS_PENDING);
            statement.setString(6, eventName);
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setTimestamp(8, Timestamp.from(now));
            statement.setTimestamp(9, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    /** INT-4: PENDING rows whose {@code next_attempt_at} has arrived, oldest first. */
    public java.util.List<DueDelivery> findDue(Connection connection, Instant now, int limit) throws SQLException {
        String sql = dialect.rowLimited("SELECT id, url, payload, attempt_count, event_name FROM " + TABLE
                + " WHERE status = ? AND next_attempt_at <= ? ORDER BY next_attempt_at ", limit);
        java.util.List<DueDelivery> due = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, STATUS_PENDING);
            statement.setTimestamp(2, Timestamp.from(now));
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    due.add(new DueDelivery(rows.getString(1), rows.getString(2), unwrapJsonText(rows.getString(3)),
                            rows.getInt(4), rows.getString(5)));
                }
            }
        }
        return due;
    }

    /** INT-4: PENDING -> SENDING (counts the attempt). Returns whether this caller won the claim. */
    public boolean claimForSend(Connection connection, String id, Instant now) throws SQLException {
        return claim(connection, id, STATUS_PENDING, STATUS_SENDING, now);
    }

    /** INT-4: returns rows a crashed worker left in SENDING since before {@code olderThan} to PENDING. */
    public int releaseStale(Connection connection, Instant olderThan, Instant now) throws SQLException {
        String sql = "UPDATE " + TABLE + " SET status = ?, next_attempt_at = ?, updated_at = ? "
                + "WHERE status = ? AND updated_at < ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, STATUS_PENDING);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setString(4, STATUS_SENDING);
            statement.setTimestamp(5, Timestamp.from(olderThan));
            return statement.executeUpdate();
        }
    }

    /** INT-4: terminal success of a claimed row (the attempt was already counted by {@link #claimForSend}). */
    public void finishDelivered(Connection connection, String id, int responseStatus, Instant now) throws SQLException {
        String sql = "UPDATE " + TABLE + " SET status = ?, response_status = ?, last_error = NULL, "
                + "next_attempt_at = NULL, delivered_at = ?, updated_at = ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, STATUS_DELIVERED);
            statement.setInt(2, responseStatus);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setString(5, id);
            statement.executeUpdate();
        }
    }

    /** INT-4: dead letter for a claimed row -- FAILED, no further attempt scheduled. */
    public void finishFailed(Connection connection, String id, int responseStatus, String errorMessage, Instant now)
            throws SQLException {
        String sql = "UPDATE " + TABLE + " SET status = ?, response_status = ?, last_error = ?, "
                + "next_attempt_at = NULL, updated_at = ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, STATUS_FAILED);
            if (responseStatus > 0) {
                statement.setInt(2, responseStatus);
            } else {
                statement.setNull(2, java.sql.Types.INTEGER);
            }
            statement.setString(3, truncate(errorMessage, 1000));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setString(5, id);
            statement.executeUpdate();
        }
    }

    /** INT-4: back to PENDING for another attempt at {@code nextAttemptAt}, keeping the error of the last one. */
    public void reschedule(Connection connection, String id, String errorMessage, Instant nextAttemptAt, Instant now)
            throws SQLException {
        String sql = "UPDATE " + TABLE + " SET status = ?, last_error = ?, next_attempt_at = ?, updated_at = ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, STATUS_PENDING);
            statement.setString(2, truncate(errorMessage, 1000));
            statement.setTimestamp(3, Timestamp.from(nextAttemptAt));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setString(5, id);
            statement.executeUpdate();
        }
    }

    /**
     * INT-4: put a FAILED or DELIVERED delivery back in the queue, due now. The id is NOT changed --
     * receivers de-duplicate on {@code X-Npdev-Webhook-Delivery}.
     */
    public boolean redeliver(Connection connection, String id, Instant now) throws SQLException {
        String sql = "UPDATE " + TABLE + " SET status = ?, next_attempt_at = ?, last_error = NULL, updated_at = ? "
                + "WHERE id = ? AND status IN (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, STATUS_PENDING);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setString(4, id);
            statement.setString(5, STATUS_FAILED);
            statement.setString(6, STATUS_DELIVERED);
            return statement.executeUpdate() == 1;
        }
    }

    /** INT-4: delivery log, newest first. NEVER includes the payload (it may hold personal data); see {@link #payload}. */
    public java.util.List<java.util.Map<String, Object>> list(Connection connection, String statusOrNull, int limit)
            throws SQLException {
        String base = "SELECT id, destination_host, url, event_name, status, attempt_count, response_status, last_error, "
                + "created_at, delivered_at, next_attempt_at FROM " + TABLE
                + (statusOrNull == null || statusOrNull.isBlank() ? "" : " WHERE status = ?")
                + " ORDER BY created_at DESC ";
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(dialect.rowLimited(base, limit))) {
            if (statusOrNull != null && !statusOrNull.isBlank()) {
                statement.setString(1, statusOrNull);
            }
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rows.getString(1));
                    row.put("destination_host", rows.getString(2));
                    row.put("url", rows.getString(3));
                    row.put("event_name", rows.getString(4));
                    row.put("status", rows.getString(5));
                    row.put("attempt_count", rows.getInt(6));
                    int responseStatus = rows.getInt(7);
                    row.put("response_status", rows.wasNull() ? null : responseStatus);
                    row.put("last_error", rows.getString(8));
                    row.put("created_at", asText(rows.getTimestamp(9)));
                    row.put("delivered_at", asText(rows.getTimestamp(10)));
                    row.put("next_attempt_at", asText(rows.getTimestamp(11)));
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** INT-4: the stored body of one delivery, for a detail view. */
    public java.util.Optional<String> payload(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT payload FROM " + TABLE + " WHERE id = ?")) {
            statement.setString(1, id);
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                return rows.next() ? java.util.Optional.ofNullable(unwrapJsonText(rows.getString(1))) : java.util.Optional.empty();
            }
        }
    }

    /**
     * The payload goes in through {@code setString} into the engine's JSON column; some engines (H2) keep it as
     * a JSON STRING scalar, so reading it back yields the body wrapped in quotes and escaped. Undo exactly that
     * one level so the worker sends the original bytes the signature was computed over.
     */
    private static String unwrapJsonText(String stored) {
        if (stored == null || stored.length() < 2 || stored.charAt(0) != '"' || stored.charAt(stored.length() - 1) != '"') {
            return stored;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(stored, String.class);
        } catch (java.io.IOException notAJsonString) {
            return stored;
        }
    }

    private static String asText(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }
}
