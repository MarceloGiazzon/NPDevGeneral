package com.finalexec.db;

import org.flywaydb.core.Flyway;
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
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-7.1: {@code schemaLifecycle.ownership=ExternallyManaged} -- NPDev must never issue schema DDL
 * against a database it does not own, only verify at boot that the live schema can serve the current
 * model. See {@code docs/REG7_REG8_EXTERNAL_DB_AND_MIGRATION_MARKING_PLAN.md} §5 Phase P1 / D5.
 *
 * <p>Exercises both {@link SchemaLifecycleExecutor#verifyExternallyManagedSchemaCompatible} directly
 * (fine-grained refusal-message coverage) and the full {@link SchemaLifecycleExecutor#migrate(Flyway,
 * SchemaLifecycleExecutor.SchemaManifest)} entry point (proving the gate placement itself: no DDL, no
 * {@code npdev_schema_metadata} write, no Flyway bookkeeping table).
 */
class SchemaLifecycleExecutorExternallyManagedTest {

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
    @DisplayName("compatible live schema verifies cleanly and records EXTERNAL_VERIFIED, no DDL issued")
    void compatibleLiveSchemaVerifiesAndRecordsHistory() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")));

        executor.verifyExternallyManagedSchemaCompatible(dataSource, manifest);

        assertEquals("EXTERNAL_VERIFIED", latestOutcome(dataSource));
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasTable(metadata, "npdev_schema_metadata"),
                    "ExternallyManaged must never write the NPDev-owned schema fingerprint pointer");
        }
    }

    @Test
    @DisplayName("missing column refuses, naming exactly the missing table.column, and records EXTERNAL_REFUSED")
    void missingColumnRefusesWithItemizedMessage() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id", "name", "sku")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "sku", "VARCHAR(20)")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.verifyExternallyManagedSchemaCompatible(dataSource, manifest));
        assertTrue(exception.getMessage().contains("widgets.sku"), "must name the missing column: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("ExternallyManaged"), "must explain why NPDev refuses to just add it");
        assertEquals("EXTERNAL_REFUSED", latestOutcome(dataSource));
    }

    @Test
    @DisplayName("missing whole table refuses, naming the table")
    void missingTableRefuses() {
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.verifyExternallyManagedSchemaCompatible(dataSource, manifest));
        assertTrue(exception.getMessage().contains("widgets (table missing)"), exception.getMessage());
    }

    @Test
    @DisplayName("incompatible column type refuses, naming both the model's and the live type")
    void incompatibleColumnTypeRefuses() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, quantity VARCHAR(20))");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id", "quantity")),
                Map.of("widgets", Map.of("id", "BIGINT", "quantity", "BIGINT")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.verifyExternallyManagedSchemaCompatible(dataSource, manifest));
        assertTrue(exception.getMessage().contains("widgets.quantity"), exception.getMessage());
        assertTrue(exception.getMessage().contains("type mismatch"), exception.getMessage());
    }

    @Test
    @DisplayName("full migrate() entry point: ExternallyManaged never calls flyway.migrate() -- no Flyway bookkeeping table")
    void fullMigrateEntryPointIssuesNoFlywayDdlOnCompatibleSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")));
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations(new String[0]).load();

        executor.migrate(flyway, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasTable(metadata, "flyway_schema_history"),
                    "ExternallyManaged must never invoke flyway.migrate() -- not even for Flyway's own bookkeeping");
            assertFalse(hasTable(metadata, "npdev_schema_metadata"),
                    "ExternallyManaged must never write a fingerprint pointer -- there is nothing for it to converge");
        }

        // Idempotence: re-verifying on every boot (there is no "fingerprint matches, skip" fast path here).
        executor.migrate(flyway, manifest);
        assertEquals("EXTERNAL_VERIFIED", latestOutcome(dataSource));
    }

    @Test
    @DisplayName("full migrate() entry point: an incompatible externally-managed schema refuses the boot")
    void fullMigrateEntryPointRefusesOnIncompatibleSchema() {
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")));
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations(new String[0]).load();

        assertThrows(IllegalStateException.class, () -> executor.migrate(flyway, manifest));
    }

    private static SchemaLifecycleExecutor.SchemaManifest externallyManagedManifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, Map<String, String>> businessTableColumnTypes) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "Postgres", "jdbc", true, "sha256:external-test",
                List.of(), List.copyOf(businessTableColumns.keySet()),
                businessTableColumns, Map.of(), businessTableColumnTypes,
                Map.of(), Map.of(), false,
                "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "",
                Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), "ExternallyManaged"
        );
    }

    private static boolean hasTable(DatabaseMetaData metadata, String table) throws SQLException {
        try (ResultSet resultSet = metadata.getTables(null, null, table.toUpperCase(java.util.Locale.ROOT), new String[] {"TABLE"})) {
            if (resultSet.next()) {
                return true;
            }
        }
        try (ResultSet resultSet = metadata.getTables(null, null, table.toLowerCase(java.util.Locale.ROOT), new String[] {"TABLE"})) {
            return resultSet.next();
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
