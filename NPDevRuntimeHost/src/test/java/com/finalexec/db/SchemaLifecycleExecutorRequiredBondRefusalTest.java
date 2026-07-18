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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 5 (5.3). Integration coverage for the bond additive-eligibility split at the
 * RuntimeHost/executor level: a NEW NULLABLE bond field on an existing populated table now flows
 * through the ordinary SAFE_ADDITIVE path (the manifest already lists it as additive-eligible, per
 * the generator-side {@code isAdditiveEligible} change -- see
 * {@code SchemaRealizationEmitterAdditiveColumnsTest} for that half); a NEW REQUIRED bond field is
 * intercepted by {@code SchemaLifecycleExecutor#refuseIfRequiredBondColumnMissing} with a dedicated
 * message BEFORE it would otherwise fall into {@link SchemaDeltaReport}'s generic {@code Unknown}
 * item kind.
 */
class SchemaLifecycleExecutorRequiredBondRefusalTest {

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
    void newNullableBondColumnOnAPopulatedTableIsSafeAdditive() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO orders (id, name, version) VALUES (1, 'first order', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // A nullable bond column ("customer_id") is present in businessTableColumns (the full
        // expected set) AND businessTableAdditiveColumns (additive-eligible) -- exactly what the
        // generator now emits for a NULLABLE bond field (LNCH-1 P5 5.3).
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("orders", List.of("id", "name", "customer_id", "version")),
                Map.of("orders", List.of("customer_id")),
                Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "a new nullable bond column must resolve via the ordinary safe-additive path, not destructive");
        assertFalse(result.performed());
    }

    @Test
    void newRequiredBondColumnOnAPopulatedTableRefusesWithADedicatedMessage_notGenericUnknown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO orders (id, name, version) VALUES (1, 'first order', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // A REQUIRED bond column ("owner_id") is present in businessTableColumns but ABSENT from
        // businessTableAdditiveColumns AND present in businessTableRequiredColumns -- exactly what
        // the generator emits for a required bond field (unchanged exclusion from additive
        // eligibility, now with the required-columns manifest key attached).
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("orders", List.of("id", "name", "owner_id", "version")),
                Map.of("orders", List.of()),
                Map.of("orders", List.of("owner_id")));

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("orders.owner_id"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("bond/reference"), refusal.getMessage());
        // The refusal must be the DEDICATED bond message, never the generic destructive-report path
        // (which this scenario, pre-fix, would otherwise reach as an "UNKNOWN" item).
        assertFalse(refusal.getMessage().contains("UNKNOWN"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "orders", "owner_id"), "the required bond column must never be added");
        }

        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("REFUSED", row.outcome());
    }

    @Test
    void newRequiredBondColumnOnABrandNewTableIsNotRefused() throws SQLException {
        // No table exists live at all -- V1's CREATE TABLE IF NOT EXISTS handles it; the bond-refusal
        // check must not fire for a table that simply does not exist yet.
        seedStoredFingerprint(dataSource, "sha256:old");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("orders", List.of("id", "name", "owner_id", "version")),
                Map.of("orders", List.of()),
                Map.of("orders", List.of("owner_id")));

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "a brand-new table (including its required bond) is V1's job, not a refusal");
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

    private record HistoryRow(String outcome) {
    }

    private static HistoryRow latestHistoryRow(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT outcome FROM npdev_schema_history ORDER BY applied_at_utc DESC")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "expected at least one npdev_schema_history row");
                return new HistoryRow(resultSet.getString(1));
            }
        }
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, List<String>> businessTableRequiredColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:new",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                businessTableAdditiveColumns,
                Map.of(),
                Map.of(),
                Map.of(),
                true,
                "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED",
                "",
                businessTableRequiredColumns,
                Map.of(),
                Map.of(),
                Map.of()
        );
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
