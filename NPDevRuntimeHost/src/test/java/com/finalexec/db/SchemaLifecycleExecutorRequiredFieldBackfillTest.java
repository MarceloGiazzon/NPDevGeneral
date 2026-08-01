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
 * LNCH-1 Phase 5 (5.2). Integration coverage for the required-field backfill step
 * ({@code SchemaLifecycleExecutor#applyRequiredFieldBackfills}), against a real H2 database, same
 * style as {@link SchemaLifecycleExecutorInPlaceRenameTest}.
 *
 * <p>Confirms the VERIFY item recon flagged: pre-Phase-5, {@code appendAdditiveColumns} already
 * added a new required column as nullable-forever with no backfill and no enforcement -- this test
 * proves the FIXED behavior (backfill + NOT NULL enforced) and the two refusal shapes (expression
 * default only; no default at all) that replace the old silent-nullable-forever outcome.
 */
class SchemaLifecycleExecutorRequiredFieldBackfillTest {

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
    void requiredColumnWithLiteralDefaultIsBackfilledAndTightenedToNotNull() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'alpha', 1)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (2, 'beta', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "status", "version")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("status", "\"PENDING\"")),
                Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "a required field with a literal default must still resolve via the safe-additive path");
        assertFalse(result.performed(), "no destructive recreation must be performed");

        // R2 (F1): the backfill/tighten now runs in afterMigrate (the single enforcement call site),
        // not beforeMigrate.
        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "status"), "the required column must have been added");
            assertTrue(isNotNull(metadata, "widgets", "status"), "the column must be enforced NOT NULL after backfill");

            assertEquals("PENDING", readStatus(connection, 1), "row 1 must be backfilled to the literal default");
            assertEquals("PENDING", readStatus(connection, 2), "row 2 must be backfilled to the literal default");

            // A NEW row supplying its own value must not be overridden by the default.
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO widgets (id, name, status, version) VALUES (3, 'gamma', 'APPROVED', 1)")) {
                insert.executeUpdate();
            }
            assertEquals("APPROVED", readStatus(connection, 3));

            // NOT NULL is really enforced, not just "happened to be backfilled" -- a null insert must fail.
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO widgets (id, name, status, version) VALUES (4, 'delta', NULL, 1)")) {
                    insert.executeUpdate();
                }
            }, "a NULL insert into the now-NOT-NULL column must be rejected by the database");
        }
    }

    @Test
    void requiredColumnWithNoDefaultRefusesAndLeavesTheColumnUnadded() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'alpha', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "status", "version")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", List.of("status")),
                Map.of(),
                Map.of());

        // R2 (F1): the refusal now fires from the single afterMigrate enforcement call site. Driving
        // afterMigrate directly (its 2-arg overload reads the seeded stale fingerprint and derives a
        // mismatch) isolates the refusal to a single REFUSED history row.
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("status"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("no default declared"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "widgets", "status"),
                    "a refused required-field addition must never add the column at all, not even nullable");
        }

        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("REFUSED", row.outcome());
    }

    @Test
    void requiredColumnWithOnlyAnExpressionDefaultRefusesWithADistinctMessage() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'alpha', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "approvedAt", "version")),
                Map.of("widgets", List.of("approvedAt")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "approvedAt", "TIMESTAMP", "version", "BIGINT")),
                Map.of("widgets", List.of("approvedAt")),
                Map.of(),
                Map.of("widgets", List.of("approvedAt")));

        // R2 (F1): expression-default-only refusal now fires from the afterMigrate enforcement site.
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("approvedAt"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("expression default is declared"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "widgets", "approvedAt"),
                    "an expression-default-only refusal must also leave the column entirely unadded");
        }
    }

    @Test
    void secondBootAfterASuccessfulBackfillIsAConvergedNoOp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'alpha', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "status", "version")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("status", "\"PENDING\"")),
                Map.of());

        executor.beforeMigrate(dataSource, manifest);
        // R2 (F1): afterMigrate performs the backfill/tighten AND stores the new fingerprint (the real
        // migrate() path does exactly this after flyway.migrate() succeeds).
        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(isNotNull(metadata, "widgets", "status"), "the first boot must have backfilled + tightened the column");
        }

        // Re-running the boot (idempotent-by-check: the column already exists and is already NOT NULL,
        // and the stored fingerprint now matches) must be a pure no-op that does not re-touch the data.
        SchemaLifecycleExecutor.DestructiveRecreation second = executor.beforeMigrate(dataSource, manifest);
        assertFalse(second.performed());
        assertFalse(second.safeAdditive(), "with a matching stored fingerprint, beforeMigrate must short-circuit to a pure no-op");
        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            assertEquals("PENDING", readStatus(connection, 1), "the converged re-run must not have altered existing data");
        }
    }

    // ---- Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): acknowledged expression-default backfill ----

    @Test
    void unacknowledgedExpressionDefaultWithRealExpressionTextStillRefusesUnchanged() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), quantity BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, quantity) VALUES (1, 'alpha', 10)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithExpressionText(
                Map.of("widgets", List.of("id", "name", "quantity", "auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "quantity", "BIGINT", "auditQuantity", "BIGINT")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("auditQuantity", "$quantity")));

        // DoD (Move 9 B1): unacknowledged, an expression default still refuses the boot exactly as it
        // did before this feature existed -- no ackToken submitted anywhere in this test.
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("auditQuantity"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("unless explicitly acknowledged"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            assertFalse(hasColumn(connection.getMetaData(), "widgets", "auditQuantity"),
                    "an unacknowledged expression backfill must never add the column at all");
        }
    }

    @Test
    void acknowledgedExpressionDefaultBackfillsEachRowToItsOwnComputedValue() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), quantity BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, quantity) VALUES (1, 'alpha', 10)");
            statement.execute("INSERT INTO widgets (id, name, quantity) VALUES (2, 'beta', 20)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithExpressionText(
                Map.of("widgets", List.of("id", "name", "quantity", "auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "quantity", "BIGINT", "auditQuantity", "BIGINT")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("auditQuantity", "$quantity")));

        // The operator's preview (the same REST surface SchemaImpactController exposes) and the
        // token they'd submit via the existing /acknowledge endpoint.
        List<ExpressionBackfillPreview.Item> preview = ExpressionBackfillPreview.preview(dataSource, manifest);
        assertEquals(1, preview.size());
        ExpressionBackfillPreview.Item item = preview.get(0);
        assertEquals(2, item.rowsAffected());
        assertTrue(item.distinctValues().containsAll(List.of("10", "20")), item.distinctValues().toString());
        assertFalse(item.hasFailures());
        String ackToken = ExpressionBackfillPreview.expectedToken(manifest.schemaFingerprint(), preview);

        PendingSchemaAcknowledgmentStore.insert(dataSource, manifest.schemaFingerprint(), ackToken, null, "test-operator");

        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "auditQuantity"));
            assertTrue(isNotNull(metadata, "widgets", "auditQuantity"));
            // Each row backfilled to its OWN quantity -- proving a per-row computed value, not one
            // literal shared by every row (the whole point of an expression default over a literal).
            assertEquals(10L, readLong(connection, "auditQuantity", 1));
            assertEquals(20L, readLong(connection, "auditQuantity", 2));
        }
    }

    @Test
    void acknowledgedExpressionDefaultWithAFailingRowRefusesAndAddsNoColumn() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO widgets (id, name) VALUES (1, 'alpha')");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // "$missingField" references a field that does not exist on any row -- every row must fail.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithExpressionText(
                Map.of("widgets", List.of("id", "name", "auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "auditQuantity", "BIGINT")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("auditQuantity", "$missingField")));

        List<ExpressionBackfillPreview.Item> preview = ExpressionBackfillPreview.preview(dataSource, manifest);
        assertEquals(1, preview.size());
        assertTrue(preview.get(0).hasFailures(), "a reference to a field absent from every row must be reported as a failure");
        String ackToken = ExpressionBackfillPreview.expectedToken(manifest.schemaFingerprint(), preview);
        PendingSchemaAcknowledgmentStore.insert(dataSource, manifest.schemaFingerprint(), ackToken, null, "test-operator");

        // DoD (Move 9 B1): "rows where the expression fails are reported and BLOCK application" --
        // even acknowledged, a failing row refuses the whole pass rather than partially applying.
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("auditQuantity"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("failed for row id"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            assertFalse(hasColumn(connection.getMetaData(), "widgets", "auditQuantity"),
                    "a failing acknowledged expression backfill must never add the column at all");
        }
    }

    private static long readLong(Connection connection, String column, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM widgets WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifestWithExpressionText(
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, List<String>> businessTableRequiredColumns,
            Map<String, List<String>> businessTableExpressionDefaultColumns,
            Map<String, Map<String, String>> businessTableColumnDefaultExpressions) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:new",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                businessTableAdditiveColumns,
                businessTableColumnTypes,
                Map.of(),
                Map.of(),
                true,
                "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED",
                "",
                businessTableRequiredColumns,
                Map.of(),
                businessTableExpressionDefaultColumns,
                Map.of(),
                List.of(),
                "NpdevManaged",
                Map.of(),
                Map.of(),
                businessTableColumnDefaultExpressions
        );
    }

    private static String readStatus(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT status FROM widgets WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
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

    private static boolean isNotNull(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return "NO".equalsIgnoreCase(resultSet.getString("IS_NULLABLE"));
                    }
                }
            }
        }
        return false;
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, List<String>> businessTableRequiredColumns,
            Map<String, Map<String, String>> businessTableColumnDefaultLiterals,
            Map<String, List<String>> businessTableExpressionDefaultColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:new",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                businessTableAdditiveColumns,
                businessTableColumnTypes,
                Map.of(),
                Map.of(),
                true,
                "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED",
                "",
                businessTableRequiredColumns,
                businessTableColumnDefaultLiterals,
                businessTableExpressionDefaultColumns,
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
