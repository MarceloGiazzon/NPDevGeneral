package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * STOR-32 (boundary B7, POSTURAL_LIFT_PLAN_2026-09-07.md package P4): the boot-residue journal --
 * every mutating schema-lifecycle step of the CURRENT boot, recorded write-before-execute, with its
 * IDEMPOTENCY as a declared property of the step (never a runtime guess). This is the answer to the
 * row's residue: today "inspect before retrying" is an instruction to a human to go and look;
 * {@link RefusalResidue} turns it into an answer the platform computes -- what committed, whether
 * each step is idempotent, and whether a retry is safe.
 *
 * <p><b>Key.</b> {@code (boot_id, step_ordinal)} -- {@code boot_id} is the PER-BOOT identifier the
 * executor generates once per {@code migrate()} invocation; {@code step_ordinal} is the enum
 * declaration order, so the same step always has the same ordinal and a replay of the same boot
 * overwrites (delete-then-insert) rather than accumulates. {@code migration_id} (the cross-boot
 * transition id, {@link MigrationPhaseJournal#migrationId}) rides along for context; only rows with
 * {@code outcome = 'COMMITTED'} count as committed, so a step that STARTED but whose pass threw
 * before {@link #committed} can never look committed.
 *
 * <p><b>Connection discipline</b> — identical to {@link MigrationPhaseJournal}'s: read/write only,
 * this class NEVER calls {@code commit()}/{@code rollback()} and never touches {@code autoCommit}.
 * The journal row for a DDL pass commits on the auto-commit connection the pass itself already runs
 * on (the engine would commit the DDL regardless); the journal never makes that decision.
 *
 * <p><b>Why the idempotent flag lives here, in the enum, and never comes from the database:</b> a
 * wrong flag produces a confidently wrong retry verdict, which is worse than no verdict — the
 * plan's X0 rule. Each flag below was set by reading the pass's code, not by copying a list:
 * every {@code true} carries that pass's own documented convergence argument in its javadoc. The
 * {@code idempotent} column is a COPY of the enum flag for readback; the enum is the source of
 * truth.
 */
final class BootResidueJournal {

    static final String TABLE = "npdev_boot_residue_journal";

    /** The mutating steps of the schema lifecycle, in execution order, with their declared
     *  idempotency. STOR-32: the values were verified against each pass's code (see the individual
     *  javadocs in the executor), NOT copied from the plan's starter list. */
    enum LifecycleStep {
        /** The migration claim/slot. Connection-scoped: a crash releases it; a re-boot re-takes it. */
        MIGRATION_CLAIM(true),
        /** Flyway's own migrate -- versioned/checksummed, re-running converges by design. */
        FLYWAY_MIGRATE(true),
        /** Table renames + column renames + type widening/relaxing. Every pass re-reads the LIVE
         *  DatabaseMetaData on each call, so a re-run against an already-converged schema finds
         *  nothing left to do ("idempotent by construction" -- each pass's own javadoc). */
        IN_PLACE_RENAME_WIDEN(true),
        /** ConversionHookRunner.run. Selection is claim-diff + phase-journal driven, but each hook's
         *  EXECUTION assumes operator-authored idempotent SQL -- unproven as a pass, so false. */
        CONVERSION_HOOKS(false),
        /** BackfillPass.applyRequiredFieldBackfills (afterMigrate) -- ADD COLUMN IF NOT EXISTS /
         *  UPDATE ... WHERE c IS NULL / SET NOT NULL-skipped-if-already, converges on re-run. */
        REQUIRED_FIELD_BACKFILL(true),
        /** UniqueConstraintPass.applyUniqueConstraints + platform column tightening -- both re-check
         *  the live shape and no-op when already applied. */
        UNIQUE_TIGHTENING(true),
        /** DestructiveRecreationPass (surgical drops / whole-schema wipe) -- data-destroying and
         *  token-gated; never re-runnable with the same effect on the same state. */
        DESTRUCTIVE_RECREATION(false),
        /** afterMigrate's ownership recording + column-identity + metadata/fingerprint/snapshot
         *  writes -- update-then-insert upserts, idempotent refreshes. */
        OWNERSHIP_RECORDING(true);

        /** Declared property of the step, set by reading the pass's code. */
        public final boolean idempotent;

        LifecycleStep(boolean idempotent) {
            this.idempotent = idempotent;
        }
    }

    /** One CONFIRMED (committed) step of a boot, as the verdict reads it back. */
    record CommittedStep(int ordinal, String stepName, boolean idempotent, String detail) {
    }

    private BootResidueJournal() {
    }

    static void ensureTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(SqlDialects.forConnection(connection).guardedCreateTable(TABLE,
                    "CREATE TABLE " + TABLE + " ("
                            + "boot_id " + InternalDdlTypes.text() + " NOT NULL, "
                            + "migration_id " + InternalDdlTypes.text() + ", "
                            + "step_ordinal INT NOT NULL, "
                            + "step_name " + InternalDdlTypes.text() + " NOT NULL, "
                            + "idempotent " + InternalDdlTypes.text() + " NOT NULL, "
                            + "started_at_utc BIGINT NOT NULL, "
                            + "committed_at_utc BIGINT, "
                            + "outcome " + InternalDdlTypes.text() + ", "
                            + "detail " + InternalDdlTypes.text() + ", "
                            + "PRIMARY KEY (boot_id, step_ordinal))"));
        }
    }

    /** Write-before-execute: records that {@code step} is about to run for this boot. A replay of
     *  the same boot (identical {@code boot_id} -- a test, or a retried invocation inside one JVM)
     *  overwrites the prior row; a REAL retry is a new boot with a new {@code boot_id}, so the two
     *  never collide. Never throws on a broken journal write: a residue that cannot be recorded must
     *  not block the steps it records (the same broken-write-never-propagates discipline
     *  {@code SchemaHistoryStore} uses). */
    static void started(DataSource dataSource, String bootId, String migrationId,
            LifecycleStep step, String detail) {
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + TABLE + " WHERE boot_id = ? AND step_ordinal = ?")) {
                delete.setString(1, bootId);
                delete.setInt(2, step.ordinal());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + TABLE + " (boot_id, migration_id, step_ordinal, step_name, "
                            + "idempotent, started_at_utc, committed_at_utc, outcome, detail) "
                            + "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)")) {
                insert.setString(1, bootId);
                insert.setString(2, migrationId);
                insert.setInt(3, step.ordinal());
                insert.setString(4, step.name());
                insert.setString(5, String.valueOf(step.idempotent));
                insert.setLong(6, System.currentTimeMillis());
                insert.setString(7, detail);
                insert.executeUpdate();
            }
        } catch (Exception exception) {
            System.out.println("NPDev schema lifecycle: failed writing npdev_boot_residue_journal started row (continuing "
                    + "-- a broken residue write must never block the step it records): " + exception.getMessage());
        }
    }

    /** Write-after-execute: confirms the step COMPLETED. A step whose pass threw keeps its row with
     *  {@code outcome} NULL, so {@link #committedSteps} never counts it. */
    static void committed(DataSource dataSource, String bootId, LifecycleStep step) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + TABLE + " SET committed_at_utc = ?, outcome = 'COMMITTED' "
                            + "WHERE boot_id = ? AND step_ordinal = ?")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, bootId);
                statement.setInt(3, step.ordinal());
                statement.executeUpdate();
            }
        } catch (Exception exception) {
            System.out.println("NPDev schema lifecycle: failed writing npdev_boot_residue_journal committed row (continuing "
                    + "-- a broken residue write must never block the step it records): " + exception.getMessage());
        }
    }

    /** The boot's CONFIRMED steps, in execution order -- only rows with {@code outcome =
     *  'COMMITTED'}, so a step that STARTED then crashed mid-pass never appears. EMPTY (never
     *  throws) when the journal table does not exist yet. */
    static List<CommittedStep> committedSteps(DataSource dataSource, String bootId) {
        List<CommittedStep> steps = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection)) {
                return steps;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT step_ordinal, step_name, idempotent, detail FROM " + TABLE
                            + " WHERE boot_id = ? AND outcome = 'COMMITTED' ORDER BY step_ordinal")) {
                statement.setString(1, bootId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        steps.add(new CommittedStep(
                                resultSet.getInt(1),
                                resultSet.getString(2),
                                Boolean.parseBoolean(resultSet.getString(3)),
                                resultSet.getString(4)
                        ));
                    }
                }
            }
        } catch (Exception exception) {
            System.out.println("NPDev schema lifecycle: failed reading npdev_boot_residue_journal (degrading to an "
                    + "empty residue -- a refusal must never fail because its residue could not be read): "
                    + exception.getMessage());
        }
        return steps;
    }

    /** The most recently ACTIVE boot that has at least one started-but-not-committed step -- i.e. the
     *  boot that refused (a refusal aborts inside a step; a fully successful boot commits every step,
     *  so it has no such row). Empty when no boot ever refused, or the journal table does not exist.
     *  The refused boot's own rows are never deleted, so this survives a later, successful boot. */
    static Optional<String> latestRefusedBootId(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection)) {
                return Optional.empty();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    SqlDialects.forConnection(connection).rowLimited(
                            "SELECT boot_id FROM " + TABLE + " WHERE outcome IS NULL "
                                    + "GROUP BY boot_id ORDER BY MAX(started_at_utc) DESC ", 1))) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return Optional.of(resultSet.getString(1));
                    }
                }
            }
        } catch (Exception exception) {
            System.out.println("NPDev schema lifecycle: could not read npdev_boot_residue_journal for the "
                    + "latest refused boot (degrading to 'none recorded'): " + exception.getMessage());
        }
        return Optional.empty();
    }

    /** The step that was IN FLIGHT when the boot refused -- the started-not-committed row (a refusal
     *  aborts inside exactly one step). Empty when the boot has no such row (defensive: covers a boot
     *  whose {@code committed()} write failed but which did not actually refuse). */
    static Optional<LifecycleStep> stepInFlight(DataSource dataSource, String bootId) {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection)) {
                return Optional.empty();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    SqlDialects.forConnection(connection).rowLimited(
                            "SELECT step_name FROM " + TABLE
                                    + " WHERE boot_id = ? AND outcome IS NULL ORDER BY step_ordinal DESC ", 1))) {
                statement.setString(1, bootId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return Optional.of(LifecycleStep.valueOf(resultSet.getString(1)));
                    }
                }
            }
        } catch (Exception exception) {
            System.out.println("NPDev schema lifecycle: could not read the in-flight step for boot '" + bootId
                    + "' (degrading to 'unknown'): " + exception.getMessage());
        }
        return Optional.empty();
    }

    private static boolean tableExists(Connection connection) throws SQLException {
        // Unquoted identifiers fold to UPPERCASE on H2/MySQL/SQL Server but keep their case on
        // Postgres -- a pattern match on TABLE would miss three of the four engines. Match the
        // returned names case-insensitively instead of guessing an engine's folding (oracles aside,
        // the journal must read back on every engine the lifecycle runs on).
        try (ResultSet tables = connection.getMetaData().getTables(null, null, null, null)) {
            while (tables.next()) {
                if (TABLE.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }
}