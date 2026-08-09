package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialects;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * LNCH-1 Phase 6 (task 6.2a). Backs the "pre-authorize a destructive upgrade on the CURRENTLY
 * RUNNING (old) app" flow the project owner ratified for the ControlPanel acknowledgment UX (see
 * plan.md §2.6 answer 2's RATIFIED amendment): before deploying a new jar whose boot would refuse
 * on an unacknowledged destructive change, an operator reviews the plan on the app that is
 * currently up and POSTs the fingerprint + token to {@link com.finalexec.controlpanel.SchemaAcknowledgmentController},
 * which writes a row here. When the NEW app boots, {@link SchemaLifecycleExecutor} additionally
 * consults this table (in addition to the static manifest field) so a pending row alone can
 * authorize the surgical/whole-schema destructive path.
 *
 * <p>Self-bootstrapped exactly like {@code npdev_schema_history}/{@code npdev_schema_metadata} --
 * a plain {@code CREATE TABLE IF NOT EXISTS} this class issues itself, NOT routed through the
 * generator's {@code internalTables} catalog (same precedent {@link SchemaLifecycleExecutor}'s
 * class javadoc for {@code HISTORY_TABLE} documents).
 *
 * <p><b>Consume-on-use:</b> a row is deleted once it has successfully authorized a completed
 * destructive migration ({@link #consume}, called by {@link SchemaLifecycleExecutor} only after
 * the DDL for that pass has fully applied -- never before, so a crash mid-destructive still finds
 * the same row available to authorize a retry on the next boot, mirroring the executor's existing
 * write-before-execute/update-after history-row discipline). This is the more defensible audit
 * posture even though a same-token collision is astronomically unlikely under SHA-256: a consumed
 * acknowledgment should not silently sit around able to authorize an unrelated future change that
 * happens to compute the same (fingerprint, token) pair.
 */
public final class PendingSchemaAcknowledgmentStore {

    static final String TABLE = "npdev_pending_schema_acknowledgment";

    private PendingSchemaAcknowledgmentStore() {
    }

    public record PendingAcknowledgment(
            String id,
            String toFingerprint,
            String ackToken,
            String itemsJson,
            long submittedAtUtc,
            String submittedBy
    ) {
    }

    static void ensureTable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SqlDialects.active().guardedCreateTable(TABLE,
                        "CREATE TABLE " + TABLE
                        + " (id " + InternalDdlTypes.keyText() + " PRIMARY KEY, "
                        + "to_fingerprint " + InternalDdlTypes.text() + " NOT NULL, "
                        + "ack_token " + InternalDdlTypes.text() + " NOT NULL, "
                        + "items_json " + InternalDdlTypes.text() + ", submitted_at_utc BIGINT, "
                        + "submitted_by " + InternalDdlTypes.text() + ")")
        )) {
            statement.executeUpdate();
        }
    }

    /** Inserts a new pending acknowledgment row and returns it (with its generated id/timestamp). */
    public static PendingAcknowledgment insert(
            DataSource dataSource, String toFingerprint, String ackToken, String itemsJson, String submittedBy
    ) {
        String id = UUID.randomUUID().toString();
        long submittedAtUtc = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + TABLE
                            + " (id, to_fingerprint, ack_token, items_json, submitted_at_utc, submitted_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?)"
            )) {
                statement.setString(1, id);
                statement.setString(2, toFingerprint);
                statement.setString(3, ackToken);
                if (itemsJson == null || itemsJson.isBlank()) {
                    statement.setNull(4, Types.VARCHAR);
                } else {
                    statement.setString(4, itemsJson);
                }
                statement.setLong(5, submittedAtUtc);
                if (submittedBy == null || submittedBy.isBlank()) {
                    statement.setNull(6, Types.VARCHAR);
                } else {
                    statement.setString(6, submittedBy);
                }
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed inserting pending schema acknowledgment", exception);
        }
        return new PendingAcknowledgment(id, toFingerprint, ackToken, itemsJson, submittedAtUtc, submittedBy);
    }

    /** All pending rows, most-recently-submitted first. Never throws -- an unreachable/missing
     * table (e.g. a fresh app that never had a pending acknowledgment written) yields an empty list. */
    public static List<PendingAcknowledgment> listAll(DataSource dataSource) {
        List<PendingAcknowledgment> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, to_fingerprint, ack_token, items_json, submitted_at_utc, submitted_by "
                            + "FROM " + TABLE + " ORDER BY submitted_at_utc DESC");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    out.add(new PendingAcknowledgment(
                            resultSet.getString(1),
                            resultSet.getString(2),
                            resultSet.getString(3),
                            resultSet.getString(4),
                            resultSet.getLong(5),
                            resultSet.getString(6)));
                }
            }
        } catch (SQLException exception) {
            return List.of();
        }
        return out;
    }

    /** The executor's read path: a pending row that authorizes exactly this (toFingerprint,
     * expectedToken) pair, if one exists. Never throws -- a missing/unreachable table means "no
     * pending acknowledgment", not a boot failure. */
    public static Optional<PendingAcknowledgment> findMatching(DataSource dataSource, String toFingerprint, String expectedToken) {
        if (dataSource == null || toFingerprint == null || toFingerprint.isBlank()
                || expectedToken == null || expectedToken.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, to_fingerprint, ack_token, items_json, submitted_at_utc, submitted_by "
                            + "FROM " + TABLE + " WHERE to_fingerprint = ? AND ack_token = ? "
                            + "ORDER BY submitted_at_utc DESC")) {
                statement.setString(1, toFingerprint);
                statement.setString(2, expectedToken);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new PendingAcknowledgment(
                            resultSet.getString(1),
                            resultSet.getString(2),
                            resultSet.getString(3),
                            resultSet.getString(4),
                            resultSet.getLong(5),
                            resultSet.getString(6)));
                }
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    /** Consume-on-use (see class javadoc): deletes a specific row by id. A failure here is logged
     * but never propagated -- the destructive DDL it authorized has already succeeded by the time
     * this is called; a cleanup-write failure must not be mistaken for (or mask) that outcome. */
    static void consume(DataSource dataSource, String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM " + TABLE + " WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        } catch (SQLException exception) {
            System.out.println("NPDev schema lifecycle: failed consuming pending schema acknowledgment row "
                    + id + " (the destructive change it authorized already applied successfully -- only this "
                    + "cleanup write failed): " + exception.getMessage());
        }
    }
}
