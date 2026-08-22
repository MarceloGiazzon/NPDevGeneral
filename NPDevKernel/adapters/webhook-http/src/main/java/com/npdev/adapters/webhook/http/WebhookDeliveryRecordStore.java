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
                + "delivered_at " + dialect.timestampColumnType()
                + ")";
        String createIndex = "CREATE INDEX ix_npdev_webhook_delivery_status ON " + TABLE + " (status)";
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute(dialect.guardedCreateTable(TABLE, createTable));
            statement.execute(dialect.guardedCreateIndex("ix_npdev_webhook_delivery_status", TABLE, createIndex));
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
}
