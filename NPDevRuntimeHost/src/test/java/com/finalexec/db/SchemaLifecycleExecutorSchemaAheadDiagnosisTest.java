package com.finalexec.db;

import com.finalexec.boundary.BoundaryBootException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B5-A (boundary-lift 2026-09-02, package 2.3): the itemized diagnosis a B5 schema-ahead refusal now
 * embeds ({@link SchemaAheadAnalysis}, backed by {@link SchemaSnapshotStore}). Sibling to
 * {@link SchemaLifecycleExecutorDatabaseMigratedPastBuildTest} -- same Trigger C setup, extended to
 * drive build N+1's boot through the REAL {@link SchemaLifecycleExecutor#afterMigrate} (not a
 * hand-seeded history row) so a schema snapshot actually gets recorded, then asserts the refusal names
 * exactly what differs.
 */
class SchemaLifecycleExecutorSchemaAheadDiagnosisTest {

    private final SchemaLifecycleExecutor executor = new SchemaLifecycleExecutor();
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("a real snapshot recorded for the ahead fingerprint makes the refusal name exactly what differs")
    void refusalItemizesTheDiffAgainstARealSnapshot() throws SQLException {
        // Build N fresh-installs for real -- afterMigrate records N's own fingerprint, history row, AND
        // (B5-A) its schema snapshot.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50), nickname VARCHAR(50))");
        }
        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = manifest(
                "sha256:N", Map.of("users", List.of("id", "name", "nickname")),
                Map.of("users", List.of("nickname")));
        executor.afterMigrate(dataSource, manifestBuildN, null, false);

        // Build N+1 drops nickname (its own real DDL, simulated directly) and boots for real too --
        // afterMigrate records N+1's fingerprint, history row, AND its own (nickname-less) snapshot.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE users DROP COLUMN nickname");
        }
        SchemaLifecycleExecutor.SchemaManifest manifestBuildNPlus1 = manifest(
                "sha256:N+1", Map.of("users", List.of("id", "name")), Map.of());
        executor.afterMigrate(dataSource, manifestBuildNPlus1, "sha256:N", true);
        // afterMigrate only self-records a history row on the REG-27 fresh-install path
        // (storedAtBootStart == null); a real (non-fresh) upgrade's history row is written by
        // beforeMigrateDecision instead, which this test does not drive for N+1 -- so Trigger C needs
        // one hand-seeded here, exactly like SchemaLifecycleExecutorDatabaseMigratedPastBuildTest's own
        // rollback scenarios do, with a timestamp guaranteed later than N's own fresh-install row.
        seedHistoryRow(dataSource, "sha256:N+1", System.currentTimeMillis() + 3_600_000L);

        // Rolling back to build N must now refuse with the itemized diff against N+1's real snapshot.
        BoundaryBootException exception = assertThrows(BoundaryBootException.class,
                () -> executor.beforeMigrate(dataSource, manifestBuildN));
        String message = exception.getMessage();
        assertTrue(message.contains("migrated PAST this build"), message);
        assertTrue(message.contains("sha256:N+1"), message);
        // The itemized line: N's own desired schema wants `nickname`, which N+1's snapshot does not
        // have -- an additive-eligible, safely-addable column from the diff engine's own view, so this
        // build needs the newer build rather than being able to reconstruct it.
        assertTrue(message.contains("users.nickname"), message);
        assertTrue(message.contains("this needs the newer build"), message);
        assertFalse(message.contains("no schema snapshot was recorded"),
                "a real snapshot WAS recorded for sha256:N+1 -- the fallback text must not appear: " + message);
    }

    @Test
    @DisplayName("no snapshot recorded for the ahead fingerprint degrades to an explanatory line, never a crash")
    void refusalDegradesGracefullyWithNoRecordedSnapshot() throws SQLException {
        // Same Trigger C setup as SchemaLifecycleExecutorDatabaseMigratedPastBuildTest's headline
        // scenario: history rows are hand-seeded directly, never through afterMigrate -- so NEITHER
        // fingerprint ever gets a schema snapshot recorded (the database predates B5-A, in effect).
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        seedHistoryRow(dataSource, "sha256:N", 1_000L);
        seedHistoryRow(dataSource, "sha256:N+1", 2_000L);
        seedStoredFingerprint(dataSource, "sha256:N+1");

        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = manifest(
                "sha256:N", Map.of("users", List.of("id", "name", "nickname")),
                Map.of("users", List.of("nickname")));

        BoundaryBootException exception = assertThrows(BoundaryBootException.class,
                () -> executor.beforeMigrate(dataSource, manifestBuildN));
        String message = exception.getMessage();
        assertTrue(message.contains("migrated PAST this build"), message);
        assertTrue(message.contains("no schema snapshot was recorded for fingerprint sha256:N+1"), message);
    }

    private static void seedHistoryRow(DataSource dataSource, String toFingerprint, long appliedAtUtc) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS npdev_schema_history "
                        + "(id TEXT PRIMARY KEY, applied_at_utc BIGINT NOT NULL, from_fingerprint TEXT, "
                        + "to_fingerprint TEXT, classification TEXT, items_json TEXT, ack_token_used TEXT, outcome TEXT NOT NULL)");
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO npdev_schema_history (id, applied_at_utc, from_fingerprint, to_fingerprint, "
                            + "classification, items_json, ack_token_used, outcome) VALUES (?, ?, NULL, ?, NULL, NULL, NULL, 'APPLIED')")) {
                statement.setString(1, java.util.UUID.randomUUID().toString());
                statement.setLong(2, appliedAtUtc);
                statement.setString(3, toFingerprint);
                statement.executeUpdate();
            }
        }
    }

    private static void seedStoredFingerprint(DataSource dataSource, String fingerprint) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS npdev_schema_metadata "
                        + "(metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)");
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO npdev_schema_metadata (metadata_key, metadata_value, updated_at_ms) VALUES ('schemaFingerprint', ?, ?)")) {
                statement.setString(1, fingerprint);
                statement.setLong(2, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            String toFingerprint,
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.copyOf(businessTableColumns.keySet()),
                businessTableColumns, businessTableAdditiveColumns,
                Map.of("users", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "nickname", "VARCHAR(50)")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; avoids pulling in an H2-specific
     *  compile-time dependency -- same copy every sibling test file in this package carries. */
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
