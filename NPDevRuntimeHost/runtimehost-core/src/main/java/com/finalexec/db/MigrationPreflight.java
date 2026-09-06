package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialects;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * STOR-28 (B4 lift): a lock-FREE read of "does this boot have any schema work to do at all" -- plain
 * {@code SELECT}s, no {@link MigrationMutex}, no {@code MigrationClaimStore} row written. The
 * residue this closes: a rolling restart where every instance runs the SAME build against a database
 * already migrated to that exact build has nothing for the migration lock to serialize, yet {@code
 * SchemaLifecycleExecutor} took it unconditionally -- see {@link #nothingToDo}'s two callers in that
 * class (the gate before acquiring, and the re-check after a timeout, before refusing).
 *
 * <p>{@link #nothingToDo} is true only when BOTH hold:
 * <ol>
 *   <li>the most recent {@code APPLIED}/{@code MANUALLY_MARKED_DONE} row in {@code
 *       npdev_schema_history} already targets THIS build's fingerprint ({@link
 *       SchemaHistoryStore#atOrPastFingerprint}) -- a prior boot already converged the
 *       schema-diff/backfill/rename machinery for this exact model;</li>
 *   <li>{@code flyway_schema_history} records no failed migration -- Flyway itself has nothing
 *       outstanding it would need {@code flyway.migrate()} to retry.</li>
 * </ol>
 *
 * <p>Deliberately conservative: any read failure, or either bookkeeping table simply not existing yet
 * (a genuinely first-ever boot), reads as "there IS work to do" -- false negatives here cost one
 * avoidable lock acquisition (the pre-existing, always-correct behavior); a false positive would skip
 * a lock a real migration needed, which this errs against in every branch.
 */
final class MigrationPreflight {

    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    private MigrationPreflight() {
    }

    static boolean nothingToDo(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        if (dataSource == null || manifest == null) {
            return false;
        }
        return SchemaHistoryStore.atOrPastFingerprint(dataSource, manifest.schemaFingerprint())
                && noFailedFlywayMigration(dataSource);
    }

    private static boolean noFailedFlywayMigration(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, FLYWAY_HISTORY_TABLE)) {
                // No Flyway bookkeeping table at all means Flyway has never run against this database
                // -- a genuinely first-ever boot, which always has real work to do.
                return false;
            }
            // Bound parameter, not a literal FALSE/0 in the SQL text: a boolean literal's spelling is
            // itself dialect-bound (SQL Server has no FALSE keyword; Flyway's own success column is
            // BIT there), and JDBC's setBoolean already translates correctly per engine -- no new
            // SqlDialect method needed for a value binding, only for SQL TEXT (STOR-1).
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + FLYWAY_HISTORY_TABLE + " WHERE success = ?")) {
                statement.setBoolean(1, false);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() && resultSet.getLong(1) == 0L;
                }
            }
        } catch (SQLException exception) {
            return false;
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SqlDialects.active().tableExistsInCurrentSchemaSql(tableName))) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }
}
