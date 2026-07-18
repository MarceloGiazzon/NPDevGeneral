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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 1 integration coverage for {@link SchemaLifecycleExecutor#attemptInPlaceRenames},
 * against a real H2 in-memory database (same style as
 * {@link SchemaLifecycleExecutorAdditiveChangeTest}). Proves: the column is actually renamed via
 * live JDBC introspection (not just classification), every row's data survives unchanged, a
 * second invocation is a clean idempotent no-op, a rename composed with a separate additive column
 * on the same table leaves the table SAFE_ADDITIVE afterward, and a table whose diff is NOT fully
 * explained by declared renames is left completely untouched (no partial rename).
 */
class SchemaLifecycleExecutorInPlaceRenameTest {

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
    void renameIsAppliedInPlaceAndDataSurvives() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, old_name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, old_name, version) VALUES (1, 'alpha', 1)");
            statement.execute("INSERT INTO widgets (id, old_name, version) VALUES (2, 'beta', 1)");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "new_name", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "new_name", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", Map.of("new_name", "old_name"))
        );

        executor.attemptInPlaceRenames(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "new_name"), "renamed column must be visible under its new name");
            assertFalse(hasColumn(metadata, "widgets", "old_name"), "old column name must no longer exist");

            try (PreparedStatement statement = connection.prepareStatement("SELECT new_name FROM widgets WHERE id = ?")) {
                statement.setLong(1, 1L);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("alpha", resultSet.getString(1), "row 1's data must survive the rename unchanged");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT new_name FROM widgets WHERE id = ?")) {
                statement.setLong(1, 2L);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("beta", resultSet.getString(1), "row 2's data must survive the rename unchanged");
                }
            }
        }

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, manifest),
                "after a fully-applied rename, live columns exactly match the manifest, so residual classification must be SAFE_ADDITIVE (nothing left to do)");

        // Idempotence: re-invoking against the already-renamed table must be a clean no-op, not an error.
        executor.attemptInPlaceRenames(dataSource, manifest);
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "new_name"), "second invocation must leave the renamed column in place");
        }
    }

    @Test
    void renameComposedWithASeparateAdditiveColumnLeavesTableSafeAdditive() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, old_name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, old_name, version) VALUES (1, 'alpha', 1)");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "new_name", "extra_col", "version")),
                Map.of("widgets", List.of("extra_col")),
                Map.of("widgets", Map.of("id", "BIGINT", "new_name", "VARCHAR(50)", "extra_col", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", Map.of("new_name", "old_name"))
        );

        executor.attemptInPlaceRenames(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "new_name"), "the rename half of the composition must still be applied");
            assertFalse(hasColumn(metadata, "widgets", "extra_col"), "the additive column is NOT this step's job -- the additive repeatable migration adds it later");
        }

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, manifest),
                "once the rename resolves, the only remaining diff (extra_col) is additive-eligible, so residual must be SAFE_ADDITIVE");
    }

    @Test
    void tableWhoseDiffIsNotFullyExplainedByDeclaredRenamesIsLeftUntouched() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, old_name VARCHAR(50), other_old VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, old_name, other_old, version) VALUES (1, 'alpha', 'gamma', 1)");
        }

        // other_old has no declared rename and is not additive-eligible -- an ordinary drop mixed
        // in with the rename. The whole table must be refused for the in-place path, not just
        // partially renamed.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "new_name", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "new_name", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", Map.of("new_name", "old_name"))
        );

        executor.attemptInPlaceRenames(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "old_name"),
                    "a table whose diff is not fully explained must be left completely untouched -- no partial rename");
            assertFalse(hasColumn(metadata, "widgets", "new_name"),
                    "the rename must not have been applied when the table as a whole is not eligible");
        }

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.DESTRUCTIVE,
                executor.classify(dataSource, manifest),
                "an unresolved column (other_old) must route the table to the destructive path as the safety net");
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
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, Map<String, String>> businessTableRenamedColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:test",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                businessTableAdditiveColumns,
                businessTableColumnTypes,
                businessTableRenamedColumns,
                Map.of(),
                true,
                "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED",
                ""
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
