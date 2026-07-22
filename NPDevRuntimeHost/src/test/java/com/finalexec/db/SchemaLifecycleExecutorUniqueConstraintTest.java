package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
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
 * LNCH-1 Phase 5 (5.1). Integration coverage for the unique-constraint pre-check-and-apply step
 * ({@code SchemaLifecycleExecutor#applyUniqueConstraints}), against a real H2 database.
 *
 * <p>Confirms the second VERIFY item recon flagged: pre-Phase-5, toggling {@code unique: true} on
 * an already-existing field produced zero enforcement at the live-table level -- {@code classify()}
 * never introspects constraints, and the fresh-CREATE DDL that emits them never re-runs against an
 * existing table. This test proves the FIXED behavior directly: the SAME scenario (an existing
 * table, a newly-declared unique constraint, no accompanying column diff) now gets pre-checked and
 * either applied (clean data) or refused with the violating tuples named (dirty data).
 */
class SchemaLifecycleExecutorUniqueConstraintTest {

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
    void preExistingBehaviorConfirmedGap_classifyAloneNeverNoticesAMissingUniqueConstraint() throws SQLException {
        // No accompanying column diff: classify() sees an identical column set on both sides, so a
        // model change that ONLY toggles unique:true produces zero column-level diff -- this is the
        // recon finding, reproduced directly against a real H2 table.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(100), tenant_id VARCHAR(120), version BIGINT)");
            statement.execute("INSERT INTO users (id, email, tenant_id, version) VALUES (1, 'a@x.com', 'default', 1)");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("users", List.of("id", "email", "tenant_id", "version")), List.of());

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, manifest),
                "confirmed: classify() alone is column-name-based only and cannot see a newly-declared, unapplied unique constraint");
    }

    @Test
    void dirtyDataRefusesWithViolatingTuplesNamedAndNoPartialConstraint() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(100), tenant_id VARCHAR(120), version BIGINT)");
            statement.execute("INSERT INTO users (id, email, tenant_id, version) VALUES (1, 'dup@x.com', 'acme', 1)");
            statement.execute("INSERT INTO users (id, email, tenant_id, version) VALUES (2, 'dup@x.com', 'acme', 1)");
            statement.execute("INSERT INTO users (id, email, tenant_id, version) VALUES (3, 'unique@x.com', 'acme', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("users", List.of("id", "email", "tenant_id", "version")),
                List.of(uniqueDecl("ux_users_email", List.of("email"), true)));

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("dup@x.com"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("users"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            assertFalse(constraintExists(connection, "users", "ux_users_email"),
                    "a refused unique constraint must never be partially applied");
            // The DB must still accept the duplicate, proving no constraint at all was applied.
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO users (id, email, tenant_id, version) VALUES (4, 'dup@x.com', 'acme', 1)")) {
                insert.executeUpdate();
            }
        }
    }

    @Test
    void cleanDataAppliesTheConstraintAndSubsequentDuplicatesAreRejectedByTheDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(100), tenant_id VARCHAR(120), version BIGINT)");
            statement.execute("INSERT INTO users (id, email, tenant_id, version) VALUES (1, 'alice@x.com', 'acme', 1)");
            statement.execute("INSERT INTO users (id, email, tenant_id, version) VALUES (2, 'bob@x.com', 'acme', 1)");
            // Same email, DIFFERENT tenant -- must NOT be treated as a violation (tenant-scoped).
            statement.execute("INSERT INTO users (id, email, tenant_id, version) VALUES (3, 'alice@x.com', 'other-tenant', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("users", List.of("id", "email", "tenant_id", "version")),
                List.of(uniqueDecl("ux_users_email", List.of("email"), true)));

        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            assertTrue(constraintExists(connection, "users", "ux_users_email"), "a clean pass must apply the constraint");

            // Live enforcement check via an actual duplicate-insert-now-rejected probe (same tenant).
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO users (id, email, tenant_id, version) VALUES (5, 'alice@x.com', 'acme', 1)")) {
                    insert.executeUpdate();
                }
            }, "the newly-applied constraint must actually reject a same-tenant duplicate email");

            // Cross-tenant duplicate must still be allowed (tenant-scoped uniqueness).
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO users (id, email, tenant_id, version) VALUES (6, 'bob@x.com', 'other-tenant', 1)")) {
                insert.executeUpdate();
            }
        }

        // Idempotence: re-running against an already-constrained table must not error (no duplicate-name ADD CONSTRAINT).
        executor.afterMigrate(dataSource, manifest);
    }

    @Test
    void anchorUniqueIsCheckedAndAppliedGlobally_notTenantScoped() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE products (id BIGINT PRIMARY KEY, sku VARCHAR(100), tenant_id VARCHAR(120), version BIGINT)");
            statement.execute("INSERT INTO products (id, sku, tenant_id, version) VALUES (1, 'SKU-1', 'acme', 1)");
            statement.execute("INSERT INTO products (id, sku, tenant_id, version) VALUES (2, 'SKU-1', 'other-tenant', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("products", List.of("id", "sku", "tenant_id", "version")),
                List.of(uniqueDecl("uq_products_sku", List.of("sku"), false)));

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("SKU-1"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("[global]"), refusal.getMessage());
    }

    private static boolean constraintExists(Connection connection, String table, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE UPPER(CONSTRAINT_NAME) = UPPER(?) AND UPPER(TABLE_NAME) = UPPER(?)")) {
            statement.setString(1, name);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
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

    private static SchemaLifecycleExecutor.UniqueConstraintDecl uniqueDecl(String name, List<String> columns, boolean tenantScoped) {
        return new SchemaLifecycleExecutor.UniqueConstraintDecl(name, columns, tenantScoped);
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            List<SchemaLifecycleExecutor.UniqueConstraintDecl> uniqueConstraints) {
        String table = businessTableColumns.keySet().iterator().next();
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:new",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                true,
                "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED",
                "",
                Map.of(),
                Map.of(),
                Map.of(),
                uniqueConstraints.isEmpty() ? Map.of() : Map.of(table, uniqueConstraints)
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
