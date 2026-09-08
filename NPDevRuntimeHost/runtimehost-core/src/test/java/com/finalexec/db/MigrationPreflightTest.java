package com.finalexec.db;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOR-28 (B4 lift): {@link MigrationPreflight#nothingToDo} against a real H2 database -- both
 * conditions (the {@code npdev_schema_history} fingerprint check and the {@code
 * flyway_schema_history} failed-migration check) exercised directly, without a Flyway instance or a
 * full {@link SchemaLifecycleExecutor#migrate} call.
 */
class MigrationPreflightTest {

    @Test
    void aGenuinelyFreshDatabaseWithNeitherTableHasWorkToDo() throws SQLException {
        DataSource dataSource = freshDataSource();
        assertFalse(MigrationPreflight.nothingToDo(dataSource, manifest("sha256:x")));
    }

    @Test
    void aHistoryRowWithNoFlywayTableStillHasWorkToDo() throws SQLException {
        DataSource dataSource = freshDataSource();
        seedHistoryRow(dataSource, "sha256:x", "APPLIED");
        // npdev_schema_history says this build's fingerprint was reached, but flyway_schema_history
        // does not exist at all -- Flyway has never run against this database, a genuinely first-ever
        // boot regardless of what the OTHER table says.
        assertFalse(MigrationPreflight.nothingToDo(dataSource, manifest("sha256:x")));
    }

    @Test
    void matchingFingerprintAndNoFailedFlywayMigrationMeansNothingToDo() throws SQLException {
        DataSource dataSource = freshDataSource();
        seedHistoryRow(dataSource, "sha256:x", "APPLIED");
        seedFlywayHistory(dataSource, true);
        assertTrue(MigrationPreflight.nothingToDo(dataSource, manifest("sha256:x")));
    }

    @Test
    void aDifferentTargetFingerprintStillHasWorkToDo() throws SQLException {
        DataSource dataSource = freshDataSource();
        seedHistoryRow(dataSource, "sha256:old", "APPLIED");
        seedFlywayHistory(dataSource, true);
        assertFalse(MigrationPreflight.nothingToDo(dataSource, manifest("sha256:new")));
    }

    @Test
    void aFailedFlywayMigrationStillHasWorkToDoEvenWithAMatchingFingerprint() throws SQLException {
        DataSource dataSource = freshDataSource();
        seedHistoryRow(dataSource, "sha256:x", "APPLIED");
        seedFlywayHistory(dataSource, false);
        assertFalse(MigrationPreflight.nothingToDo(dataSource, manifest("sha256:x")));
    }

    @Test
    void aManuallyMarkedDoneOutcomeCountsTheSameAsApplied() throws SQLException {
        DataSource dataSource = freshDataSource();
        seedHistoryRow(dataSource, "sha256:x", "MANUALLY_MARKED_DONE");
        seedFlywayHistory(dataSource, true);
        assertTrue(MigrationPreflight.nothingToDo(dataSource, manifest("sha256:x")));
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(String toFingerprint) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());
    }

    // npdev-schema-history-seq (twin-pair token: this inline CREATE TABLE must stay in step with
    // SchemaHistoryStore.ensureHistoryTable -- see scripts/quality/twin-pair-registry.json).
    private static void seedHistoryRow(DataSource dataSource, String toFingerprint, String outcome) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS npdev_schema_history "
                        + "(id VARCHAR(64) PRIMARY KEY, applied_at_utc BIGINT NOT NULL, from_fingerprint VARCHAR(128), "
                        + "to_fingerprint VARCHAR(128), classification VARCHAR(64), items_json VARCHAR(4000), "
                        + "ack_token_used VARCHAR(256), outcome VARCHAR(32) NOT NULL, seq BIGINT)");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO npdev_schema_history (id, applied_at_utc, from_fingerprint, to_fingerprint, "
                            + "classification, items_json, ack_token_used, outcome) VALUES (?, ?, NULL, ?, NULL, NULL, NULL, ?)")) {
                statement.setString(1, java.util.UUID.randomUUID().toString());
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, toFingerprint);
                statement.setString(4, outcome);
                statement.executeUpdate();
            }
        }
    }

    /** A minimal stand-in for Flyway's own {@code flyway_schema_history} table shape -- only the
     * {@code success} column matters to {@link MigrationPreflight}. */
    private static void seedFlywayHistory(DataSource dataSource, boolean success) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS flyway_schema_history "
                        + "(installed_rank INT PRIMARY KEY, success BOOLEAN NOT NULL)");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO flyway_schema_history (installed_rank, success) VALUES (?, ?)")) {
                statement.setInt(1, 1);
                statement.setBoolean(2, success);
                statement.executeUpdate();
            }
        }
    }

    private static DataSource freshDataSource() {
        String url = "jdbc:h2:mem:" + MigrationPreflightTest.class.getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        return new SingleConnectionUrlDataSource(url);
    }

    /** Minimal {@link DataSource} over {@link DriverManager} -- no H2-specific compile dependency,
     *  mirroring the pattern every other H2 test in this package uses. */
    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(getClass().getName());
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
