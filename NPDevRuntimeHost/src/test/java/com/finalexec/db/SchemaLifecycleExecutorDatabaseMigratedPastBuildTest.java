package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-8 ("schema-ahead detector blind to a pure column drop", D4): Trigger C. The register's own
 * practical example (§1.8): build N+1 drops {@code users.nickname} (acknowledged, applied); the
 * operator rolls back to build N, which still expects {@code nickname}. Before this fix, the old
 * detector (Trigger A/B, {@link SchemaLifecycleExecutorTableRenameBlindSpotTest}'s sibling coverage)
 * only runs on a fingerprint-MATCH boot and has nothing to see here (the rollback boot is a
 * fingerprint MISMATCH, and the dropped column's absence is indistinguishable, from live shape alone,
 * from "never existed" -- {@code nickname} is additive-eligible and no unexplained extra column
 * exists, so {@code classify()} resolved SAFE_ADDITIVE and Flyway's R__ migration silently re-added it
 * empty). Trigger C closes this by consulting {@code npdev_schema_history} instead of live shape.
 */
class SchemaLifecycleExecutorDatabaseMigratedPastBuildTest {

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
    @DisplayName("register's practical example: pure column drop + rollback refuses instead of silently re-adding the column empty")
    void pureColumnDropRollbackRefusesInsteadOfSilentlyReAddingTheColumnEmpty() throws SQLException {
        // Live shape reflects build N+1's already-applied drop: nickname is gone, nothing extra/
        // unexplained left behind -- the exact shape that used to fool Trigger A/B AND classify().
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO users (id, name) VALUES (1, 'Alpha')");
        }
        // History: build N was reached at T1; a LATER, real migration moved the database to N+1 at T2.
        seedHistoryRow(dataSource, "sha256:N", "APPLIED", 1_000L);
        seedHistoryRow(dataSource, "sha256:N+1", "APPLIED", 2_000L);
        // The database's CURRENT stored pointer is N+1's (nothing has touched it since) -- the old
        // jar's own redeploy has not changed anything yet.
        seedStoredFingerprint(dataSource, "sha256:N+1");

        // The OLD jar (build N) still expects nickname.
        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = manifest(
                "sha256:N", Map.of("users", List.of("id", "name", "nickname")),
                Map.of("users", List.of("nickname")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, manifestBuildN));
        assertTrue(exception.getMessage().contains("migrated PAST this build"), exception.getMessage());
        assertTrue(exception.getMessage().contains("sha256:N+1"), exception.getMessage());
        assertTrue(exception.getMessage().contains("mark"), "must point at the mark-done escape hatch: " + exception.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "users", "nickname"),
                    "the refusal must fire BEFORE classify()/the R__ migration ever runs -- nickname must NOT be silently re-added");
        }
        assertEquals("REFUSED", latestNonStepOutcome(dataSource));
    }

    @Test
    @DisplayName("a legitimate forward upgrade to a never-before-seen fingerprint never trips Trigger C")
    void legitimateForwardUpgradeDoesNotTripTriggerC() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        // A real prior history exists (this database has migrated before) -- but never once to THIS
        // build's own (brand new) target fingerprint, so Trigger C has nothing to compare against.
        seedHistoryRow(dataSource, "sha256:previous", "APPLIED", 1_000L);
        seedStoredFingerprint(dataSource, "sha256:previous");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:brand-new", Map.of("users", List.of("id", "name", "notes")),
                Map.of("users", List.of("notes")));

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);

        assertTrue(result.safeAdditive(), "an ordinary forward upgrade must proceed normally, not be refused");
    }

    @Test
    @DisplayName("REG-7.2 interaction (D4): a MANUALLY_MARKED_DONE mark for this fingerprint short-circuits Trigger C entirely")
    void manuallyMarkedDoneFingerprintShortCircuitsTriggerC() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        // The identical "database moved past this build" history shape as the headline scenario...
        seedHistoryRow(dataSource, "sha256:N", "APPLIED", 1_000L);
        seedHistoryRow(dataSource, "sha256:N+1", "APPLIED", 2_000L);
        seedStoredFingerprint(dataSource, "sha256:N+1");
        // ...but the operator has explicitly authorized this older build to take back over.
        MigrationMarkStore.insert(dataSource, "sha256:N", "super-user-1", "deliberately reverting to N");

        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = manifest(
                "sha256:N", Map.of("users", List.of("id", "name", "nickname")),
                Map.of("users", List.of("nickname")));

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifestBuildN);

        assertFalse(result.performed());
        assertFalse(result.safeAdditive());
        assertEquals("sha256:N", readStoredFingerprint(dataSource), "the mark must fast-forward, not refuse");
        assertEquals("MANUALLY_MARKED_DONE", latestNonStepOutcome(dataSource));
    }

    private static void seedHistoryRow(DataSource dataSource, String toFingerprint, String outcome, long appliedAtUtc) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS npdev_schema_history "
                        + "(id TEXT PRIMARY KEY, applied_at_utc BIGINT NOT NULL, from_fingerprint TEXT, "
                        + "to_fingerprint TEXT, classification TEXT, items_json TEXT, ack_token_used TEXT, outcome TEXT NOT NULL)");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO npdev_schema_history (id, applied_at_utc, from_fingerprint, to_fingerprint, "
                            + "classification, items_json, ack_token_used, outcome) VALUES (?, ?, NULL, ?, NULL, NULL, NULL, ?)")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setLong(2, appliedAtUtc);
                statement.setString(3, toFingerprint);
                statement.setString(4, outcome);
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
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO npdev_schema_metadata (metadata_key, metadata_value, updated_at_ms) VALUES (?, ?, ?)")) {
                statement.setString(1, "schemaFingerprint");
                statement.setString(2, fingerprint);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    private static String readStoredFingerprint(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT metadata_value FROM npdev_schema_metadata WHERE metadata_key = 'schemaFingerprint'");
             ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "expected a stored fingerprint row");
            return resultSet.getString(1);
        }
    }

    private static String latestNonStepOutcome(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT outcome FROM npdev_schema_history ORDER BY applied_at_utc DESC")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "expected at least one npdev_schema_history row");
                return resultSet.getString(1);
            }
        }
    }

    private static boolean hasColumn(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            String toFingerprint,
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.copyOf(businessTableColumns.keySet()),
                businessTableColumns, businessTableAdditiveColumns,
                Map.of("users", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "nickname", "VARCHAR(50)", "notes", "VARCHAR(255)")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; avoids pulling in an H2-specific compile-time dependency. */
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
