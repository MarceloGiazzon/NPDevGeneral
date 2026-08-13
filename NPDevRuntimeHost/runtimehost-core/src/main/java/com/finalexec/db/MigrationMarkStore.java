package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialects;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
            String fromFingerprint,
            String markedFingerprint,
            long markedAtUtc,
            String markedBy,
            String note
    ) {
    }

    static void ensureTable(Connection connection) throws SQLException {
        // REG-28: bind the mark to the from->to transition it authorizes. from_fingerprint is
        // declared directly in the CREATE TABLE for a brand new install (every test database, and any
        // real database that has never had this table before) -- no ALTER needed there at all. A
        // pre-fix row's from_fingerprint is NULL; findMatching's "WHERE from_fingerprint = ?" never
        // matches a NULL row under ordinary SQL null semantics, so a legacy "unbound" mark is simply
        // never honored again -- the safer of the two backward-compat options (a fresh beta has none
        // anyway).
        try (PreparedStatement statement = connection.prepareStatement(
                SqlDialects.active().guardedCreateTable(TABLE,
                        "CREATE TABLE " + TABLE
                        + " (id " + InternalDdlTypes.keyText() + " PRIMARY KEY, "
                        + "marked_fingerprint " + InternalDdlTypes.text() + " NOT NULL, "
                        + "marked_at_utc BIGINT NOT NULL, "
                        + "marked_by " + InternalDdlTypes.text() + ", "
                        + "note " + InternalDdlTypes.text() + ", "
                        + "from_fingerprint " + InternalDdlTypes.text() + ")")
        )) {
            statement.executeUpdate();
        }
        // Upgrade path for a table that genuinely predates this fix: a portable ADD COLUMN IF NOT
        // EXISTS (same discipline SchemaLifecycleExecutor's own additive-column migrations use -- no
        // engine dialect branch needed, H2 and Postgres both support it). Guarded by an explicit
        // "column already exists" check: this runs on EVERY boot (via findMatching), so firing the
        // ALTER unconditionally would mean issuing that DDL statement forever, not just once on the
        // one real upgrade boot that needs it -- caught live, it also shifted DDL-call indices in
        // SchemaLifecycleExecutorDestructiveCrashRecoveryTest's fault-injection harness, which counts
        // ALTER TABLE statements. For a brand new install the CREATE TABLE above already declared the
        // column, so this branch never fires there.
        if (!hasFromFingerprintColumn(connection)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    SqlDialects.active().guardedAddColumn(TABLE, "from_fingerprint",
                            "ALTER TABLE " + TABLE + " ADD COLUMN from_fingerprint "
                            + InternalDdlTypes.text())
            )) {
                statement.executeUpdate();
            }
        }
        // REG-30: a duplicate mark for the same transition can no longer be inserted twice. A unique
        // index (not a named ADD CONSTRAINT) so it is trivially idempotent across repeated
        // ensureTable calls; NULLs are never considered equal by a unique index on either engine, so
        // this does not constrain pre-fix unbound rows against each other or against a real mark.
        try (PreparedStatement statement = connection.prepareStatement(
                SqlDialects.active().guardedCreateIndex("ux_" + TABLE + "_transition", TABLE,
                        "CREATE UNIQUE INDEX ux_" + TABLE + "_transition ON " + TABLE
                        + " (from_fingerprint, marked_fingerprint)")
        )) {
            statement.executeUpdate();
        }
    }

    private static boolean hasFromFingerprintColumn(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : List.of(TABLE.toLowerCase(Locale.ROOT), TABLE.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    if ("from_fingerprint".equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Inserts a new mark and returns it (with its generated id/timestamp). {@code fromFingerprint}
     * is the live stored fingerprint the operator observed the database at; the mark only matches a
     * future boot whose OWN stored fingerprint still equals it (REG-28). */
    public static Mark insert(DataSource dataSource, String fromFingerprint, String markedFingerprint, String markedBy, String note) {
        String id = UUID.randomUUID().toString();
        long markedAtUtc = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + TABLE + " (id, from_fingerprint, marked_fingerprint, marked_at_utc, marked_by, note) "
                            + "VALUES (?, ?, ?, ?, ?, ?)"
            )) {
                statement.setString(1, id);
                if (fromFingerprint == null || fromFingerprint.isBlank()) {
                    statement.setNull(2, Types.VARCHAR);
                } else {
                    statement.setString(2, fromFingerprint);
                }
                statement.setString(3, markedFingerprint);
                statement.setLong(4, markedAtUtc);
                if (markedBy == null || markedBy.isBlank()) {
                    statement.setNull(5, Types.VARCHAR);
                } else {
                    statement.setString(5, markedBy);
                }
                if (note == null || note.isBlank()) {
                    statement.setNull(6, Types.VARCHAR);
                } else {
                    statement.setString(6, note);
                }
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed inserting migration mark", exception);
        }
        return new Mark(id, fromFingerprint, markedFingerprint, markedAtUtc, markedBy, note);
    }

    /** All marks, most-recently-submitted first. Never throws -- an unreachable/missing table (a
     * fresh app that never had a mark written) yields an empty list. */
    public static List<Mark> listAll(DataSource dataSource) {
        List<Mark> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, from_fingerprint, marked_fingerprint, marked_at_utc, marked_by, note FROM " + TABLE
                            + " ORDER BY marked_at_utc DESC");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    out.add(new Mark(
                            resultSet.getString(1),
                            resultSet.getString(2),
                            resultSet.getString(3),
                            resultSet.getLong(4),
                            resultSet.getString(5),
                            resultSet.getString(6)));
                }
            }
        } catch (SQLException exception) {
            return List.of();
        }
        return out;
    }

    /** The executor's read path: the most recent mark recorded for exactly this
     * {@code (fromFingerprint -> toFingerprint)} transition, if one exists. {@code fromFingerprint}
     * must be the boot's OWN live stored fingerprint (REG-28) -- a mark recorded against some other
     * from can never fast-forward a boot it was not written for, even if its target matches. Never
     * throws -- a missing/unreachable table means "no mark", not a boot failure. */
    public static Optional<Mark> findMatching(DataSource dataSource, String fromFingerprint, String toFingerprint) {
        if (dataSource == null
                || fromFingerprint == null || fromFingerprint.isBlank()
                || toFingerprint == null || toFingerprint.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, from_fingerprint, marked_fingerprint, marked_at_utc, marked_by, note FROM " + TABLE
                            + " WHERE from_fingerprint = ? AND marked_fingerprint = ? ORDER BY marked_at_utc DESC")) {
                statement.setString(1, fromFingerprint);
                statement.setString(2, toFingerprint);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Mark(
                            resultSet.getString(1),
                            resultSet.getString(2),
                            resultSet.getString(3),
                            resultSet.getLong(4),
                            resultSet.getString(5),
                            resultSet.getString(6)));
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
