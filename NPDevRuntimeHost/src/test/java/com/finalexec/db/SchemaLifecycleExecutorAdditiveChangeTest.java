package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for {@link SchemaLifecycleExecutor#isSafeAdditiveChange}. Both bugs fixed
 * during Phase 6 (system-schema column pollution; the H2 "users" table name colliding with
 * {@code information_schema.users}) would have been caught immediately by a real H2 database here.
 */
class SchemaLifecycleExecutorAdditiveChangeTest {

    private final SchemaLifecycleExecutor executor = new SchemaLifecycleExecutor();
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id UUID, name VARCHAR(255), email VARCHAR(255), version BIGINT)");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void newNullableColumnOnExistingTableIsSafe() {
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("users", List.of("id", "name", "email", "nickname", "version")),
                Map.of("users", List.of("name", "email", "nickname"))
        );

        assertTrue(executor.isSafeAdditiveChange(dataSource, manifest),
                "adding a non-bond column that R__ can apply must be classified as safe-additive");
    }

    @Test
    void columnRemovedFromModelIsNotSafe() {
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("users", List.of("id", "name", "version")),
                Map.of("users", List.of("name"))
        );

        assertFalse(executor.isSafeAdditiveChange(dataSource, manifest),
                "a column present in the live DB but absent from the model is a removal, which is structural");
    }

    @Test
    void newColumnNotAdditiveEligibleIsNotSafe() {
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("users", List.of("id", "name", "email", "roleId", "version")),
                Map.of("users", List.of("name", "email"))
        );

        assertFalse(executor.isSafeAdditiveChange(dataSource, manifest),
                "a new column that R__ cannot add (e.g. a bond/FK column) must not be classified as safe-additive");
    }

    @Test
    void brandNewTableIsSafe() {
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("users", List.of("id", "name", "email", "version"), "orders", List.of("id", "total", "version")),
                Map.of("users", List.of("name", "email"), "orders", List.of("total"))
        );

        assertTrue(executor.isSafeAdditiveChange(dataSource, manifest),
                "a manifest table that does not exist yet is left to V1's CREATE TABLE IF NOT EXISTS, not treated as unsafe");
    }

    @Test
    void unchangedColumnsAreSafe() {
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("users", List.of("id", "name", "email", "version")),
                Map.of("users", List.of("name", "email"))
        );

        assertTrue(executor.isSafeAdditiveChange(dataSource, manifest));
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns, Map<String, List<String>> businessTableAdditiveColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:test",
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
                Map.of(),
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
