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
 * REG-61(b) (docs/NPDEV_OPEN_ITEMS_REGISTER.md, split from REG-59's WmsOffice live recovery,
 * 2026-07-28): a literal default writes the SAME value into every affected row, so it cannot satisfy
 * a UNIQUE constraint once more than one row needs it -- confirmed live on WmsOffice
 * ({@code identity_roles.name}, 5 rows; {@code identity_users.username}, 6 rows). Before this fix,
 * {@link BackfillPass#applyRequiredFieldBackfills} did not know about uniqueness at all: it would
 * "successfully" backfill every row to the same literal, only for {@code UniqueConstraintPass} to
 * fail with a confusing duplicate-key error later in the SAME boot -- a second, differently-worded
 * failure instead of one clear, named refusal up front.
 *
 * <p>RED-first scenario, per the register's own filing: a required unique field, an existing 2-row
 * table, and a literal default -- pre-fix this reaches a duplicate-key failure after
 * {@code UniqueConstraintPass}; post-fix it is a named refusal naming the row count and the recovery
 * recipe, and (Pass 1 is read-only) the column is never even added.
 */
class BackfillPassUniqueColumnRefusalTest {

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
    void uniqueColumnWithMoreThanOneAffectedRowRefusesByNameInsteadOfBackfilling() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE roles (id BIGINT PRIMARY KEY, version BIGINT)");
            statement.execute("INSERT INTO roles (id, version) VALUES (1, 1)");
            statement.execute("INSERT INTO roles (id, version) VALUES (2, 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithUniqueConstraint(
                Map.of("roles", List.of("id", "name", "version")),
                Map.of("roles", List.of("name")),
                Map.of("roles", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("roles", List.of("name")),
                Map.of("roles", Map.of("name", "\"MIGRATED\"")),
                Map.of("roles", List.of(new SchemaLifecycleExecutor.UniqueConstraintDecl("ux_roles_name", List.of("name"), false))));

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("roles.name"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("2 existing row"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("per-row-unique"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains("no default declared"),
                "this is a DIFFERENT refusal reason than 'no default at all' -- must not be conflated");

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "roles", "name"),
                    "Pass 1 is read-only: a refused column must never be added, not even nullable");
        }
    }

    @Test
    void uniqueColumnWithOnlyOneAffectedRowBackfillsNormally() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE roles (id BIGINT PRIMARY KEY, version BIGINT)");
            statement.execute("INSERT INTO roles (id, version) VALUES (1, 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithUniqueConstraint(
                Map.of("roles", List.of("id", "name", "version")),
                Map.of("roles", List.of("name")),
                Map.of("roles", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("roles", List.of("name")),
                Map.of("roles", Map.of("name", "\"MIGRATED\"")),
                Map.of("roles", List.of(new SchemaLifecycleExecutor.UniqueConstraintDecl("ux_roles_name", List.of("name"), false))));

        // A single affected row can never collide with itself -- must proceed exactly like the
        // non-unique case, not be swept up in the REG-61(b) refusal.
        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "roles", "name"));
            try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM roles WHERE id = 1")) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("MIGRATED", resultSet.getString(1));
                }
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

    private static SchemaLifecycleExecutor.SchemaManifest manifestWithUniqueConstraint(
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, List<String>> businessTableRequiredColumns,
            Map<String, Map<String, String>> businessTableColumnDefaultLiterals,
            Map<String, List<SchemaLifecycleExecutor.UniqueConstraintDecl>> businessTableUniqueConstraints) {
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
                Map.of(),
                businessTableUniqueConstraints
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
