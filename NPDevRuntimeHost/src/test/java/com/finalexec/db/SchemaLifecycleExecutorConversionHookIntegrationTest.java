package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SER-P7.5/P7.3 (schema-engine rebuild, Phase 7): the full {@code beforeMigrate} end-to-end proof for
 * rule 6 -- a conversion hook resolving the ONLY destructive item in a boot means the boot succeeds
 * with NO acknowledgment token at all (not just that {@link ConversionHookRunner} itself behaves
 * correctly in isolation, which {@link ConversionHookRunnerH2Test} already covers) -- and the
 * unaffected-regression proof that an item no hook claims is exactly as token-gated as it always was.
 */
class SchemaLifecycleExecutorConversionHookIntegrationTest {

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
    void hookResolvedDestructiveDropNeedsNoAcknowledgmentTokenAndBootSucceeds() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE p76_widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO p76_widgets (id, legacy_flag) VALUES (1, TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // p76-drop-legacy (src/test/resources/db/conversion-hooks/p76-drop-legacy) claims exactly
        // "DROP_COLUMN:p76_widgets:legacy_flag:BOOLEAN" and its convert.sql drops the column itself.
        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:new", List.of(), List.of("p76_widgets"),
                Map.of("p76_widgets", List.of("id")),
                Map.of("p76_widgets", List.of("id")),
                Map.of("p76_widgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly",
                "", "", // no blanket flag, NO acknowledgment token provided
                Map.of(), Map.of(), Map.of(), Map.of());

        // No exception -- the hook resolved the only destructive item, so no token is required.
        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);

        assertTrue(result.safeAdditive(), "fully hook-resolved is reported the same way a SAFE_ADDITIVE boot is");
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "p76_widgets", "legacy_flag"),
                    "the hook's own convert.sql must have actually dropped the column");
        }
    }

    @Test
    void anUnclaimedDestructiveItemIsStillTokenGatedExactlyAsBefore() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE p76_untouched (id BIGINT PRIMARY KEY, mystery_column BOOLEAN)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // No fixture hook on the classpath claims "DROP_COLUMN:p76_untouched:mystery_column:...".
        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:new", List.of(), List.of("p76_untouched"),
                Map.of("p76_untouched", List.of("id")),
                Map.of("p76_untouched", List.of("id")),
                Map.of("p76_untouched", Map.of("id", "BIGINT")),
                Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly",
                "", "",
                Map.of(), Map.of(), Map.of(), Map.of());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, manifest));
        assertTrue(exception.getMessage().contains("DROP_COLUMN:p76_untouched:mystery_column"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("Expected acknowledgment token:"), exception.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "p76_untouched", "mystery_column"),
                    "a refusal must leave the database completely untouched");
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

    private static boolean hasColumn(DatabaseMetaData metadata, String table, String column) throws SQLException {
        try (var resultSet = metadata.getColumns(null, null, table.toUpperCase(java.util.Locale.ROOT), null)) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Single, long-lived connection so H2's {@code DB_CLOSE_DELAY=-1} in-memory DB (and its
     *  auto-bootstrapped metadata) survives across the many short-lived connections the executor and
     *  {@link ConversionHookRunner} each open. Mirrors {@code SchemaLifecycleExecutorDestructiveItemizationTest}'s
     *  identically-named fixture. */
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
