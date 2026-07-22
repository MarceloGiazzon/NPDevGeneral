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
 * LNCH-1 P2 (2.5) integration coverage for {@link SchemaLifecycleExecutor#attemptInPlaceTableRenames},
 * against a real H2 in-memory database (same style as {@link SchemaLifecycleExecutorInPlaceRenameTest},
 * the Phase 1 field-level equivalent). Proves: the table is actually renamed via live JDBC
 * introspection, every row's data survives unchanged, a second invocation is a clean idempotent
 * no-op, an unrelated live table with no declared rename is left untouched, and -- the §2.4
 * ordering requirement -- a table rename composed with a field rename on the SAME concept in the
 * same boot applies both correctly when run in the mandated order (table rename first, then field
 * rename against the new table name).
 */
class SchemaLifecycleExecutorTableRenameTest {

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
    void tableRenameIsAppliedInPlaceAndDataSurvives() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO gadgets (id, name, version) VALUES (1, 'alpha', 1)");
            statement.execute("INSERT INTO gadgets (id, name, version) VALUES (2, 'beta', 1)");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "version")),
                Map.of(),
                Map.of("widgets", "gadgets"));

        executor.attemptInPlaceTableRenames(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasTable(metadata, "widgets"), "renamed table must be visible under its new name");
            assertFalse(hasTable(metadata, "gadgets"), "old table name must no longer exist");

            assertRowName(connection, 1L, "alpha");
            assertRowName(connection, 2L, "beta");
        }

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, manifest),
                "after a fully-applied table rename, live tables/columns exactly match the manifest");

        // Idempotence: re-invoking against the already-renamed table must be a clean no-op.
        executor.attemptInPlaceTableRenames(dataSource, manifest);
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasTable(metadata, "widgets"), "second invocation must leave the renamed table in place");
            assertFalse(hasTable(metadata, "gadgets"), "second invocation must not resurrect the old table name");
        }
    }

    @Test
    void unrelatedLiveTableWithNoDeclaredRenameIsLeftUntouched() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("CREATE TABLE unrelated_leftover (id BIGINT PRIMARY KEY)");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "version")),
                Map.of(),
                Map.of("widgets", "gadgets"));

        executor.attemptInPlaceTableRenames(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasTable(metadata, "widgets"), "the declared rename must still apply");
            assertTrue(hasTable(metadata, "unrelated_leftover"),
                    "a live table with no declared rename and no manifest entry must be left completely alone");
        }
    }

    @Test
    void tableRenameComposedWithAFieldRenameOnTheSameConceptAppliesBothInOrder() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, old_name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO gadgets (id, old_name, version) VALUES (1, 'alpha', 1)");
        }

        // The concept was renamed Gadget -> Widget (table gadgets -> widgets) AND its field was
        // renamed old_name -> new_name in the same model change. §2.4 mandates table renames run
        // first so the field-rename step sees the correct (new) table name.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "new_name", "version")),
                Map.of("widgets", Map.of("new_name", "old_name")),
                Map.of("widgets", "gadgets"));

        executor.attemptInPlaceTableRenames(dataSource, manifest);
        executor.attemptInPlaceRenames(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasTable(metadata, "widgets"), "table rename must have applied");
            assertFalse(hasTable(metadata, "gadgets"), "old table name must be gone");
            assertTrue(hasColumn(metadata, "widgets", "new_name"), "field rename must have applied against the NEW table name");
            assertFalse(hasColumn(metadata, "widgets", "old_name"), "old column name must be gone");

            try (PreparedStatement statement = connection.prepareStatement("SELECT new_name FROM widgets WHERE id = ?")) {
                statement.setLong(1, 1L);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("alpha", resultSet.getString(1), "data must survive both renames composed together");
                }
            }
        }

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, manifest),
                "once both renames are fully applied, residual classification must be SAFE_ADDITIVE");
    }

    private void assertRowName(Connection connection, long id, String expectedName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM widgets WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(expectedName, resultSet.getString(1));
            }
        }
    }

    private static boolean hasTable(DatabaseMetaData metadata, String table) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getTables(null, null, candidate, new String[] {"TABLE"})) {
                if (resultSet.next()) {
                    return true;
                }
            }
        }
        return false;
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
            Map<String, Map<String, String>> businessTableRenamedColumns,
            Map<String, String> businessTableRenames) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:test",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                Map.of(),
                Map.of(),
                businessTableRenamedColumns,
                businessTableRenames,
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
