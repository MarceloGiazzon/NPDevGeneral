package com.finalexec.db;

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
 * REG-7.2 ("mark migration as done", D2/D4): the GeneXus-style operator affordance -- "the schema is
 * already at fingerprint X; record that and don't try to migrate to it." An operator submits a mark
 * via {@link com.finalexec.controlpanel.SchemaAcknowledgmentController#markDone}, on the CURRENTLY
 * RUNNING app (same pre-authorization shape {@link PendingSchemaAcknowledgmentStore} uses -- a
 * refusing/fast-forwarding boot has no server to accept the mark on itself). The NEXT boot at that
 * target fingerprint consults this store in {@link SchemaLifecycleExecutor#beforeMigrate} and
 * fast-forwards the stored fingerprint pointer with NO migration passes run.
 *
 * <p>A DEDICATED store, not a reuse of {@code npdev_schema_metadata}'s key-value shape (D2 decision):
 * marking done is an audited operational act, so each row carries who/when/why, which the metadata
 * table's plain key-value pairs cannot.
 *
 * <p>Self-bootstrapped exactly like {@link PendingSchemaAcknowledgmentStore} -- a plain
 * {@code CREATE TABLE IF NOT EXISTS} this class issues itself, never routed through the generator's
 * {@code internalTables} catalog. Consume-on-use: a row is deleted once it has fast-forwarded a real
 * boot ({@link #consume}), for the same "no silently-reusable audit record" reason
 * {@link PendingSchemaAcknowledgmentStore#consume} documents.
 */
public final class MigrationMarkStore {

    static final String TABLE = "npdev_schema_migration_mark";

    private MigrationMarkStore() {
    }

    public record Mark(
            String id,
            String markedFingerprint,
            long markedAtUtc,
            String markedBy,
            String note
    ) {
    }

    static void ensureTable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS " + TABLE
                        + " (id TEXT PRIMARY KEY, marked_fingerprint TEXT NOT NULL, marked_at_utc BIGINT NOT NULL, "
                        + "marked_by TEXT, note TEXT)"
        )) {
            statement.executeUpdate();
        }
    }

    /** Inserts a new mark and returns it (with its generated id/timestamp). */
    public static Mark insert(DataSource dataSource, String markedFingerprint, String markedBy, String note) {
        String id = UUID.randomUUID().toString();
        long markedAtUtc = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + TABLE + " (id, marked_fingerprint, marked_at_utc, marked_by, note) "
                            + "VALUES (?, ?, ?, ?, ?)"
            )) {
                statement.setString(1, id);
                statement.setString(2, markedFingerprint);
                statement.setLong(3, markedAtUtc);
                if (markedBy == null || markedBy.isBlank()) {
                    statement.setNull(4, Types.VARCHAR);
                } else {
                    statement.setString(4, markedBy);
                }
                if (note == null || note.isBlank()) {
                    statement.setNull(5, Types.VARCHAR);
                } else {
                    statement.setString(5, note);
                }
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed inserting migration mark", exception);
        }
        return new Mark(id, markedFingerprint, markedAtUtc, markedBy, note);
    }

    /** All marks, most-recently-submitted first. Never throws -- an unreachable/missing table (a
     * fresh app that never had a mark written) yields an empty list. */
    public static List<Mark> listAll(DataSource dataSource) {
        List<Mark> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, marked_fingerprint, marked_at_utc, marked_by, note FROM " + TABLE
                            + " ORDER BY marked_at_utc DESC");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    out.add(new Mark(
                            resultSet.getString(1),
                            resultSet.getString(2),
                            resultSet.getLong(3),
                            resultSet.getString(4),
                            resultSet.getString(5)));
                }
            }
        } catch (SQLException exception) {
            return List.of();
        }
        return out;
    }

    /** The executor's read path: the most recent mark recorded for this exact target fingerprint, if
     * one exists. Never throws -- a missing/unreachable table means "no mark", not a boot failure. */
    public static Optional<Mark> findMatching(DataSource dataSource, String markedFingerprint) {
        if (dataSource == null || markedFingerprint == null || markedFingerprint.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, marked_fingerprint, marked_at_utc, marked_by, note FROM " + TABLE
                            + " WHERE marked_fingerprint = ? ORDER BY marked_at_utc DESC")) {
                statement.setString(1, markedFingerprint);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Mark(
                            resultSet.getString(1),
                            resultSet.getString(2),
                            resultSet.getLong(3),
                            resultSet.getString(4),
                            resultSet.getString(5)));
                }
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    /** Consume-on-use (see class javadoc): deletes a specific row by id. A failure here is logged but
     * never propagated -- the fingerprint fast-forward it authorized already happened by the time
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
            System.out.println("NPDev schema lifecycle: failed consuming migration mark row " + id
                    + " (the fingerprint fast-forward it authorized already applied successfully -- only this "
                    + "cleanup write failed): " + exception.getMessage());
        }
    }
}
