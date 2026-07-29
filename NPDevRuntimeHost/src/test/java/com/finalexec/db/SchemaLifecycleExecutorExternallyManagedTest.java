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
 * model. See {@code docs/archive/programme-history/REG7_REG8_EXTERNAL_DB_AND_MIGRATION_MARKING_PLAN.md} §5 Phase P1 / D5.
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

    @Test
    @DisplayName("SER-P5.2: a schema that passes column-shape but fails NULLABILITY refuses, itemized")
    void columnShapeOkButNullabilityMismatchRefuses() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            // Every column the model names exists, with the right type -- the pre-P5.2 check passed this.
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        // ... but the model REQUIRES name, and the live column is nullable.
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of("widgets", List.of("name")),
                Map.of());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.verifyExternallyManagedSchemaCompatible(dataSource, manifest));
        assertTrue(exception.getMessage().contains("widgets.name"), exception.getMessage());
        assertTrue(exception.getMessage().contains("nullability mismatch"), exception.getMessage());
        assertEquals("EXTERNAL_REFUSED", latestOutcome(dataSource));
    }

    @Test
    @DisplayName("SER-P5.2: a live NOT NULL column WITH a default is fine for an optional model field")
    void liveNotNullWithADefaultIsToleratedForAnOptionalField() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, "
                    + "name VARCHAR(50) NOT NULL DEFAULT 'unnamed')");
        }
        // The model treats name as optional; the live column is NOT NULL but has a default, so an insert
        // that omits it still succeeds -- this must NOT be flagged (a false refusal would brick the boot).
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")));

        executor.verifyExternallyManagedSchemaCompatible(dataSource, manifest);

        assertEquals("EXTERNAL_VERIFIED", latestOutcome(dataSource));
    }

    @Test
    @DisplayName("SER-P5.2: a unique invariant the model declares but the live schema lacks refuses")
    void missingUniqueConstraintRefuses() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, email VARCHAR(50))");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id", "email")),
                Map.of("widgets", Map.of("id", "BIGINT", "email", "VARCHAR(50)")),
                Map.of(),
                Map.of("widgets", List.of(
                        new SchemaLifecycleExecutor.UniqueConstraintDecl("uq_widgets_email", List.of("email"), false))));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.verifyExternallyManagedSchemaCompatible(dataSource, manifest));
        assertTrue(exception.getMessage().contains("missing unique constraint"), exception.getMessage());
        assertEquals("EXTERNAL_REFUSED", latestOutcome(dataSource));
    }

    @Test
    @DisplayName("SER-P5.2: a live UNIQUE constraint satisfying the model's declared invariant verifies")
    void satisfiedUniqueConstraintVerifies() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, email VARCHAR(50), "
                    + "CONSTRAINT uq_widgets_email UNIQUE (email))");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id", "email")),
                Map.of("widgets", Map.of("id", "BIGINT", "email", "VARCHAR(50)")),
                Map.of(),
                Map.of("widgets", List.of(
                        new SchemaLifecycleExecutor.UniqueConstraintDecl("uq_widgets_email", List.of("email"), false))));

        executor.verifyExternallyManagedSchemaCompatible(dataSource, manifest);

        assertEquals("EXTERNAL_VERIFIED", latestOutcome(dataSource));
    }

    @Test
    @DisplayName("SER-G8: a bond's foreign key the live schema does not enforce refuses")
    void missingForeignKeyRefuses() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE owners (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, owner_id BIGINT)"); // no FK
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id", "owner_id")),
                Map.of("widgets", Map.of("id", "BIGINT", "owner_id", "BIGINT")),
                Map.of(), Map.of(),
                Map.of("widgets", List.of(new SchemaLifecycleExecutor.ForeignKeyDecl(
                        List.of("owner_id"), "owners", List.of("id")))),
                Map.of());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.verifyExternallyManagedSchemaCompatible(dataSource, manifest));
        assertTrue(exception.getMessage().contains("missing foreign key"), exception.getMessage());
        assertTrue(exception.getMessage().contains("owners"), exception.getMessage());
        assertEquals("EXTERNAL_REFUSED", latestOutcome(dataSource));
    }

    @Test
    @DisplayName("SER-G8: a live foreign key satisfying the bond verifies, ignoring its engine-chosen name")
    void satisfiedForeignKeyVerifiesRegardlessOfConstraintName() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE owners (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, owner_id BIGINT, "
                    + "CONSTRAINT some_name_npdev_would_never_choose FOREIGN KEY (owner_id) REFERENCES owners (id))");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id", "owner_id")),
                Map.of("widgets", Map.of("id", "BIGINT", "owner_id", "BIGINT")),
                Map.of(), Map.of(),
                Map.of("widgets", List.of(new SchemaLifecycleExecutor.ForeignKeyDecl(
                        List.of("owner_id"), "owners", List.of("id")))),
                Map.of());

        executor.verifyExternallyManagedSchemaCompatible(dataSource, manifest);

        assertEquals("EXTERNAL_VERIFIED", latestOutcome(dataSource));
    }

    @Test
    @DisplayName("SER-G8: a declared index the live schema lacks refuses; extra live indexes are tolerated")
    void missingIndexRefusesButExtraLiveIndexesAreTolerated() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            // An EXTRA index the model never declares must never be reported (missing-only, by design).
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, tenant_id VARCHAR(120), note VARCHAR(50))");
            statement.execute("CREATE INDEX an_external_dbas_own_index ON widgets (note)");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id", "tenant_id", "note")),
                Map.of("widgets", Map.of("id", "BIGINT", "tenant_id", "VARCHAR(120)", "note", "VARCHAR(50)")),
                Map.of(), Map.of(), Map.of(),
                Map.of("widgets", List.of(new SchemaLifecycleExecutor.IndexDecl(List.of("tenant_id"), false))));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.verifyExternallyManagedSchemaCompatible(dataSource, manifest));
        assertTrue(exception.getMessage().contains("missing index on [tenant_id]"), exception.getMessage());
        assertFalse(exception.getMessage().contains("an_external_dbas_own_index"),
                "an extra live index must never be reported: " + exception.getMessage());
        assertEquals("EXTERNAL_REFUSED", latestOutcome(dataSource));
    }

    @Test
    @DisplayName("SER-G8: a primary key satisfies a declared index over the same columns")
    void primaryKeySatisfiesADeclaredIndex() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY)");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = externallyManagedManifest(
                Map.of("widgets", List.of("id")),
                Map.of("widgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(), Map.of(),
                Map.of("widgets", List.of(new SchemaLifecycleExecutor.IndexDecl(List.of("id"), false))));

        executor.verifyExternallyManagedSchemaCompatible(dataSource, manifest);

        assertEquals("EXTERNAL_VERIFIED", latestOutcome(dataSource));
    }

    private static SchemaLifecycleExecutor.SchemaManifest externallyManagedManifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, Map<String, String>> businessTableColumnTypes) {
        return externallyManagedManifest(businessTableColumns, businessTableColumnTypes, Map.of(), Map.of());
    }

    private static SchemaLifecycleExecutor.SchemaManifest externallyManagedManifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, List<String>> businessTableRequiredColumns,
            Map<String, List<SchemaLifecycleExecutor.UniqueConstraintDecl>> businessTableUniqueConstraints) {
        return externallyManagedManifest(businessTableColumns, businessTableColumnTypes,
                businessTableRequiredColumns, businessTableUniqueConstraints, Map.of(), Map.of());
    }

    /** SER-P5.2/G8 overload: lets a scenario declare required columns, unique constraints, foreign keys
     *  and indexes — every full-shape dimension the upgraded verification checks. */
    private static SchemaLifecycleExecutor.SchemaManifest externallyManagedManifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, List<String>> businessTableRequiredColumns,
            Map<String, List<SchemaLifecycleExecutor.UniqueConstraintDecl>> businessTableUniqueConstraints,
            Map<String, List<SchemaLifecycleExecutor.ForeignKeyDecl>> businessTableForeignKeys,
            Map<String, List<SchemaLifecycleExecutor.IndexDecl>> businessTableIndexes) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "Postgres", "jdbc", true, "sha256:external-test",
                List.of(), List.copyOf(businessTableColumns.keySet()),
                businessTableColumns, Map.of(), businessTableColumnTypes,
                Map.of(), Map.of(), false,
                "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "",
                businessTableRequiredColumns, Map.of(), Map.of(), businessTableUniqueConstraints,
                List.of(), "ExternallyManaged",
                businessTableForeignKeys, businessTableIndexes
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
