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
 * Integration coverage for {@link SchemaLifecycleExecutor#relaxNoLongerRequiredColumns}, against a
 * real H2 in-memory database (same style as {@link SchemaLifecycleExecutorInPlaceRenameTest}).
 *
 * <p>Closes a real, previously-silent gap found while auditing LNCH-1's remaining open items
 * (2026-07-19): a field going from {@code required} to optional changes the schema fingerprint
 * ({@code UserDatabaseDefinitionLoader#fingerprintInputs} includes {@code required=} per field), so
 * {@link SchemaLifecycleExecutor#beforeMigrate} genuinely re-evaluates the table -- but
 * {@link SchemaLifecycleExecutor#classify} only compares column names and SQL types, never
 * nullability, so it saw an unchanged table and returned {@code SAFE_ADDITIVE}. The live
 * {@code NOT NULL} constraint was therefore never relaxed: the boot "succeeded" while the database
 * remained permanently unable to accept the null values the model now declares optional. Proven
 * here: the constraint is actually dropped via live JDBC introspection (not just classification),
 * existing non-null data survives untouched, a still-required column and a column with no
 * declared-columns entry are both left alone, a second invocation is a clean idempotent no-op, and a
 * column that is ALSO being renamed in the same boot is relaxed under its new name once the rename
 * has run (proving composability with the unconditional-before-classify rename step, not just
 * standalone).
 */
class SchemaLifecycleExecutorNullabilityRelaxationTest {

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
    void columnNoLongerRequiredIsRelaxedAndDataSurvives() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, nickname VARCHAR(50) NOT NULL, version BIGINT)");
            statement.execute("INSERT INTO widgets (id, nickname, version) VALUES (1, 'Alpha', 1)");
        }

        // nickname was required in the old model; the new model declares it optional (no longer in
        // businessTableRequiredColumns) -- same name, same type, only nullability differs.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "nickname", "version")),
                Map.of("widgets", Map.of("id", "BIGINT", "nickname", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", List.of("id", "version")));

        executor.relaxNoLongerRequiredColumns(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(isNotNull(metadata, "widgets", "nickname"), "the column must no longer be NOT NULL");
            assertEquals("Alpha", readColumn(connection, "widgets", "nickname", 1L), "existing data must survive untouched");

            // The now-optional column genuinely accepts NULL -- prove it, not just introspect the flag.
            try (PreparedStatement update = connection.prepareStatement("UPDATE widgets SET nickname = NULL WHERE id = 1")) {
                update.executeUpdate();
            }
            assertEquals(null, readColumn(connection, "widgets", "nickname", 1L), "the relaxed column must genuinely accept NULL");
        }
    }

    @Test
    void stillRequiredColumnIsLeftUntouched() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, nickname VARCHAR(50) NOT NULL)");
            statement.execute("INSERT INTO widgets (id, nickname) VALUES (1, 'Alpha')");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "nickname")),
                Map.of("widgets", Map.of("id", "BIGINT", "nickname", "VARCHAR(50)")),
                Map.of("widgets", List.of("id", "nickname")));

        executor.relaxNoLongerRequiredColumns(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(isNotNull(metadata, "widgets", "nickname"), "a still-required column must stay NOT NULL");
        }
    }

    @Test
    void secondInvocationIsAnIdempotentNoOp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, nickname VARCHAR(50) NOT NULL)");
            statement.execute("INSERT INTO widgets (id, nickname) VALUES (1, 'Alpha')");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "nickname")),
                Map.of("widgets", Map.of("id", "BIGINT", "nickname", "VARCHAR(50)")),
                Map.of("widgets", List.of("id")));

        executor.relaxNoLongerRequiredColumns(dataSource, manifest);
        // Must not throw a second time against an already-relaxed column.
        executor.relaxNoLongerRequiredColumns(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(isNotNull(metadata, "widgets", "nickname"));
        }
    }

    @Test
    void relaxationComposesWithARenameOnTheSameBoot() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, old_name VARCHAR(50) NOT NULL)");
            statement.execute("INSERT INTO widgets (id, old_name) VALUES (1, 'Alpha')");
        }

        // old_name -> new_name (rename) AND new_name is no longer required -- both in one boot.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "new_name")),
                Map.of("widgets", Map.of("id", "BIGINT", "new_name", "VARCHAR(50)")),
                Map.of("widgets", List.of("id")),
                Map.of("widgets", Map.of("new_name", "old_name")));

        // Mirrors beforeMigrate's own ordering: rename first, then relax -- proving composability,
        // not just that each step works alone.
        executor.attemptInPlaceRenames(dataSource, manifest);
        executor.relaxNoLongerRequiredColumns(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "widgets", "old_name"), "the rename must have applied");
            assertTrue(hasColumn(metadata, "widgets", "new_name"), "the renamed column must be present");
            assertFalse(isNotNull(metadata, "widgets", "new_name"), "the renamed column must also be relaxed under its NEW name");
            assertEquals("Alpha", readColumn(connection, "widgets", "new_name", 1L), "data must survive both steps");
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
        throw new IllegalStateException("Column not found: " + table + "." + column);
    }

    private static String readColumn(Connection connection, String table, String column, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "expected a row with id " + id);
                return resultSet.getString(1);
            }
        }
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, List<String>> businessTableRequiredColumns) {
        return manifest(businessTableColumns, businessTableColumnTypes, businessTableRequiredColumns, Map.of());
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, List<String>> businessTableRequiredColumns,
            Map<String, Map<String, String>> businessTableRenamedColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:test",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                Map.of(),
                businessTableColumnTypes,
                businessTableRenamedColumns,
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
            return Logger.getLogger("global");
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
