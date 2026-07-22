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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-7.2 ("mark migration as done", D2/D4): H2 integration coverage for
 * {@link SchemaLifecycleExecutor#beforeMigrate}'s NEW read path against {@link MigrationMarkStore} --
 * mirrors {@link SchemaLifecycleExecutorPendingAcknowledgmentTest}'s shape for the destructive-ack
 * pending store. A mark for THIS build's target fingerprint fast-forwards the stored fingerprint with
 * zero migration passes; a mark for a different fingerprint must never apply; a consumed mark is
 * gone; the fast-forward is recorded as a {@code MANUALLY_MARKED_DONE} history row.
 */
class SchemaLifecycleExecutorMigrationMarkTest {

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
    @DisplayName("a mark for this build's target fingerprint fast-forwards with NO migration passes run")
    void markForTargetFingerprintFastForwardsWithNoMigrationPasses() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            // A live table that does NOT match the manifest at all -- if the mark were ignored this
            // would classify DESTRUCTIVE (a genuine column drop). The mark must pre-empt that entirely.
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, legacy_flag) VALUES (1, TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        MigrationMarkStore.insert(dataSource, "sha256:new", "super-user-1", "verified by hand, already migrated");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestIdOnly("sha256:new");
        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);

        assertFalse(result.performed(), "a mark must never run the destructive path");
        assertFalse(result.safeAdditive(), "a mark short-circuits before classification even runs");
        assertEquals("sha256:new", readStoredFingerprint(dataSource), "the fingerprint must be fast-forwarded immediately");
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "legacy_flag"),
                    "a mark must issue NO DDL -- the live schema is completely untouched, trusted as-is");
        }
        assertEquals("MANUALLY_MARKED_DONE", latestOutcome(dataSource));
    }

    @Test
    @DisplayName("the mark is consumed on use -- a second boot at the same fingerprint finds no mark and is a plain no-op")
    void markIsConsumedOnUse() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        MigrationMarkStore.insert(dataSource, "sha256:new", "super-user-1", null);

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestIdOnly("sha256:new");
        executor.beforeMigrate(dataSource, manifest);

        assertTrue(MigrationMarkStore.listAll(dataSource).isEmpty(), "the mark must be deleted once it fast-forwards a real boot");

        // Second boot: fingerprint now matches (already fast-forwarded), no mark left -- pure no-op.
        SchemaLifecycleExecutor.DestructiveRecreation second = executor.beforeMigrate(dataSource, manifest);
        assertFalse(second.performed());
        assertFalse(second.safeAdditive());
    }

    @Test
    @DisplayName("a mark recorded for a DIFFERENT fingerprint must never apply -- normal classification proceeds")
    void markForADifferentFingerprintDoesNotApply() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        // Stale mark for some earlier plan's target -- must not authorize THIS boot's fingerprint.
        MigrationMarkStore.insert(dataSource, "sha256:some-other-target", "super-user-1", null);

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest("sha256:new",
                Map.of("widgets", List.of("id", "name", "notes")),
                Map.of("widgets", List.of("notes")));

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);

        assertTrue(result.safeAdditive(), "with no matching mark, ordinary classification must run (here: a plain additive column)");
        assertEquals(1, MigrationMarkStore.listAll(dataSource).size(), "the non-matching mark must be left untouched, not consumed");
        assertFalse(latestOutcome(dataSource).equals("MANUALLY_MARKED_DONE"));
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

    private static String latestOutcome(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT outcome FROM npdev_schema_history ORDER BY applied_at_utc DESC");
             ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "expected at least one npdev_schema_history row");
            return resultSet.getString(1);
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

    private static SchemaLifecycleExecutor.SchemaManifest manifestIdOnly(String toFingerprint) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            String toFingerprint,
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.copyOf(businessTableColumns.keySet()),
                businessTableColumns, businessTableAdditiveColumns,
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "notes", "VARCHAR(255)")),
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
