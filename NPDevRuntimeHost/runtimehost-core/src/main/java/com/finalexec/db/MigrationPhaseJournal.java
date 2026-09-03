package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialects;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * A1 (REAL_LIFT_PLAN_2026-09-03, B11 "real lift"): the explicit per-phase resumability record for
 * migration execution that must survive a process crash mid-migration -- distinct from {@link
 * SchemaHistoryStore}'s audit rows (STARTED/APPLIED/FAILED, for human/operator visibility after the
 * fact) in that THIS table is consulted BEFORE a phase runs, to decide whether to run it at all.
 *
 * <p>A migration attempt is identified by {@code migrationId} -- stable across every boot that is
 * converging the SAME {@code from -> to} schema transition (see {@link #migrationId}), because the
 * stored fingerprint {@code from} reads the same value on every boot until the transition actually
 * completes. Each phase within it is keyed by {@code (phaseGroup, phaseOrdinal)} -- {@code phaseGroup}
 * is the hook id a phase came from, {@code phaseOrdinal} its statement index within that hook's
 * convert SQL (see {@link ConversionHookPhaseSplitter}).
 *
 * <p><b>Read/write only -- no transaction management.</b> This class never calls {@code commit()} or
 * changes {@code autoCommit}; the caller ({@link ConversionHookPhaseRunner}) owns that, because
 * whether a phase's journal row must commit ALONE (a DDL phase, which the engine itself commits the
 * instant it runs on H2/MySQL, so nothing here could make that conditional anyway) or TOGETHER with
 * the phase's own statement (a DML phase, for which atomicity is the entire safety argument) is a
 * decision about the PHASE, not about the journal.
 */
final class MigrationPhaseJournal {

    private static final String TABLE = "npdev_migration_phase_journal";

    private MigrationPhaseJournal() {
    }

    /** Identifies one phase across the whole migration: {@code migrationId} scopes it to one
     *  {@code from -> to} schema transition, {@code phaseGroup} to one hook, {@code phaseOrdinal} to
     *  one statement within that hook's split. */
    record PhaseKey(String migrationId, String phaseGroup, int phaseOrdinal) {
    }

    /** Stable across every boot attempting the SAME transition -- {@code from} is the fingerprint
     *  {@code SchemaLifecycleExecutor#readStoredFingerprintPublic} reads live, which does not change
     *  until {@code afterMigrate} writes the new one at the very end (i.e. after every phase of every
     *  hook has already converged), so a resumed boot recomputes the identical id. */
    static String migrationId(String fromFingerprint, String toFingerprint) {
        String seed = (fromFingerprint == null ? "" : fromFingerprint) + "->" + (toFingerprint == null ? "" : toFingerprint);
        return sha256Hex(seed);
    }

    static void ensureTable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SqlDialects.forConnection(connection).guardedCreateTable(TABLE,
                        "CREATE TABLE " + TABLE + " ("
                                + "migration_id " + InternalDdlTypes.text() + " NOT NULL, "
                                + "phase_group " + InternalDdlTypes.text() + " NOT NULL, "
                                + "phase_ordinal INT NOT NULL, "
                                + "phase_kind " + InternalDdlTypes.text() + ", "
                                + "statement_hash " + InternalDdlTypes.text() + ", "
                                + "started_at_utc BIGINT NOT NULL, "
                                + "completed_at_utc BIGINT, "
                                + "PRIMARY KEY (migration_id, phase_group, phase_ordinal))"))) {
            statement.executeUpdate();
        }
    }

    /**
     * True only when a COMPLETED row exists for this exact phase AND {@code statementHash} matches
     * what completed it.
     *
     * <p>The hash comparison matters: a different hash means the hook's convert SQL changed between
     * boots (the author edited it) -- silently skipping a phase whose statement text changed would
     * mean the NEW statement's effect never applies. Re-running is the honest default; a resumed
     * migration must resume the migration that was actually declared, not whatever an old journal row
     * happens to remember.
     */
    static boolean isCompleted(Connection connection, PhaseKey key, String statementHash) throws SQLException {
        ensureTable(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT completed_at_utc, statement_hash FROM " + TABLE
                        + " WHERE migration_id = ? AND phase_group = ? AND phase_ordinal = ?")) {
            statement.setString(1, key.migrationId());
            statement.setString(2, key.phaseGroup());
            statement.setInt(3, key.phaseOrdinal());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                long completedAt = resultSet.getLong(1);
                if (resultSet.wasNull()) {
                    return false;
                }
                String recordedHash = resultSet.getString(2);
                return completedAt > 0 && Objects.equals(recordedHash, statementHash);
            }
        }
    }

    /**
     * True when the journal has EVER recorded a phase for this {@code (migrationId, phaseGroup)} --
     * regardless of whether that phase itself is completed. This is what lets a hook be RE-SELECTED
     * after a crash even when the schema-diff item its {@code claims} entry names has since
     * reclassified: adding a column changes the live schema's shape, and {@code SchemaDiffEngine}
     * keys a present-but-not-yet-tightened column as {@code TIGHTEN_NOT_NULL:table:column} rather than
     * the original {@code ADD_REQUIRED_COLUMN:table:column} a hook's {@code claims} entry names -- a
     * hook selected purely by claim-intersection would silently stop being considered the instant its
     * OWN first phase committed, which is exactly the case a resumable migration must not hit. Any
     * hook this returns true for should be re-split and re-run: an already-fully-completed hook's
     * phases all skip (cheap), and one genuinely mid-flight resumes.
     */
    static boolean hasAnyActivity(Connection connection, String migrationId, String phaseGroup) throws SQLException {
        ensureTable(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE migration_id = ? AND phase_group = ?")) {
            statement.setString(1, migrationId);
            statement.setString(2, phaseGroup);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getLong(1) > 0;
            }
        }
    }

    /**
     * Write-before-execute: deletes any prior row for this exact phase (a retry after a crash starts
     * this phase's record fresh rather than accumulating one row per attempt) and inserts a new one
     * with {@code completedAtUtc = NULL}. Callers on a DDL phase call this on an auto-commit
     * connection, so it commits the instant this statement runs -- deliberate: the DDL right after it
     * is about to auto-commit on H2/MySQL regardless, so there is no atomicity to preserve by
     * deferring this write. Callers on a DML phase call this WITHOUT auto-commit, as one of several
     * statements the caller commits together with the phase's own SQL and {@link #recordCompleted}.
     */
    static void recordStarted(Connection connection, PhaseKey key, String phaseKind, String statementHash)
            throws SQLException {
        ensureTable(connection);
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + TABLE + " WHERE migration_id = ? AND phase_group = ? AND phase_ordinal = ?")) {
            delete.setString(1, key.migrationId());
            delete.setString(2, key.phaseGroup());
            delete.setInt(3, key.phaseOrdinal());
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (migration_id, phase_group, phase_ordinal, phase_kind, "
                        + "statement_hash, started_at_utc, completed_at_utc) VALUES (?, ?, ?, ?, ?, ?, NULL)")) {
            insert.setString(1, key.migrationId());
            insert.setString(2, key.phaseGroup());
            insert.setInt(3, key.phaseOrdinal());
            insert.setString(4, phaseKind);
            insert.setString(5, statementHash);
            insert.setLong(6, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }

    /** Write-after-execute: flips {@code completedAtUtc} on the row {@link #recordStarted} just
     *  wrote, on the SAME connection the phase itself ran on -- see the class javadoc for why commit
     *  timing is the caller's decision, not this method's. */
    static void recordCompleted(Connection connection, PhaseKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + TABLE + " SET completed_at_utc = ? WHERE migration_id = ? AND phase_group = ? "
                        + "AND phase_ordinal = ?")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, key.migrationId());
            statement.setString(3, key.phaseGroup());
            statement.setInt(4, key.phaseOrdinal());
            statement.executeUpdate();
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
