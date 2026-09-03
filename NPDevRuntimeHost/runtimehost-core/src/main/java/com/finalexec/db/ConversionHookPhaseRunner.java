package com.finalexec.db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * A1 (REAL_LIFT_PLAN_2026-09-03, B11 "real lift"): runs a {@link ConversionHookPhaseSplitter}'s
 * phases against {@code dataSource}, consulting {@link MigrationPhaseJournal} before each one so a
 * boot that crashes mid-hook resumes on the NEXT boot at the first phase not yet completed, instead of
 * re-running the whole hook's SQL from the top -- which STOR-2/B11 already established is unsafe once
 * any DDL in it has implicitly committed on H2/MySQL.
 *
 * <h2>Two different safety arguments, one per phase kind</h2>
 *
 * <p><b>DDL phase:</b> runs on an auto-commit connection. {@link MigrationPhaseJournal#recordStarted}
 * commits, the statement itself auto-commits (H2/MySQL do this regardless of any transaction this
 * class might wrap around it), {@link MigrationPhaseJournal#recordCompleted} commits -- three separate
 * commits. Safe wherever a crash lands between them because the statement itself is idempotent by
 * construction ({@link ConversionHookPhaseSplitter} only lets a DDL phase through after rewriting it
 * to its dialect-guarded form, or confirming the shape is inherently re-runnable): re-executing an
 * already-applied guarded statement is a no-op, not an error.
 *
 * <p><b>DML phase:</b> runs on ONE explicit transaction, together with BOTH journal writes -- commit
 * once, at the end. A crash before that commit leaves NOTHING persisted (the whole transaction is
 * gone), so retrying from scratch is exactly correct; a crash after it leaves the statement's effect
 * AND the journal's completed row durable together, so the next boot's {@link
 * MigrationPhaseJournal#isCompleted} check correctly skips it. This is the one place hand-authored,
 * not-necessarily-idempotent DML gets a safety guarantee it does not have on its own -- atomicity, not
 * idempotence.
 */
final class ConversionHookPhaseRunner {

    private ConversionHookPhaseRunner() {
    }

    record PhaseOutcome(int phasesRun, int phasesSkipped) {
    }

    static PhaseOutcome run(DataSource dataSource, String migrationId, String phaseGroup,
            List<ConversionHookPhaseSplitter.Phase> phases) throws SQLException {
        int ran = 0;
        int skipped = 0;
        for (ConversionHookPhaseSplitter.Phase phase : phases) {
            MigrationPhaseJournal.PhaseKey key =
                    new MigrationPhaseJournal.PhaseKey(migrationId, phaseGroup, phase.ordinal());
            boolean alreadyDone;
            try (Connection probe = dataSource.getConnection()) {
                alreadyDone = MigrationPhaseJournal.isCompleted(probe, key, phase.statementHash());
            }
            if (alreadyDone) {
                skipped++;
                continue;
            }
            runOnePhase(dataSource, key, phase);
            ran++;
        }
        return new PhaseOutcome(ran, skipped);
    }

    private static void runOnePhase(DataSource dataSource, MigrationPhaseJournal.PhaseKey key,
            ConversionHookPhaseSplitter.Phase phase) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (phase.kind() == ConversionHookPhaseSplitter.PhaseKind.DDL) {
                MigrationPhaseJournal.recordStarted(connection, key, "DDL", phase.statementHash());
                try (Statement statement = connection.createStatement()) {
                    statement.execute(phase.executableSql());
                }
                MigrationPhaseJournal.recordCompleted(connection, key);
            } else {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    MigrationPhaseJournal.recordStarted(connection, key, "DML", phase.statementHash());
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(phase.executableSql());
                    }
                    MigrationPhaseJournal.recordCompleted(connection, key);
                    connection.commit();
                } catch (SQLException failure) {
                    safeRollback(connection);
                    throw failure;
                } finally {
                    connection.setAutoCommit(previousAutoCommit);
                }
            }
        }
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best-effort rollback; the caller already has the real failure to propagate
        }
    }
}
