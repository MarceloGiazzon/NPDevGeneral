package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * REG-61(a) (docs/NPDEV_OPEN_ITEMS_REGISTER.md, split from REG-59's WmsOffice live recovery,
 * 2026-07-28): {@code executeNarrowTypeDropAndRecreate}'s {@code ALTER TABLE ... ADD COLUMN} never
 * re-applied {@code NOT NULL}, even when the model declares the field required -- confirmed live on
 * six WmsOffice columns, forcing every one of them through {@code BackfillPass}'s next-boot
 * backfill-or-refuse dance even though the table had zero rows and needed no backfill at all.
 *
 * <p>Fix: preserve the model's required-ness directly when it is safe to -- a table with zero rows
 * can go straight to {@code NOT NULL}; a non-empty table cannot (adding a {@code NOT NULL} column
 * with no default fails outright against any existing row), so it stays nullable exactly as before,
 * leaving {@code BackfillPass}'s existing convergence path untouched for that case.
 */
class DestructiveRecreationPassRequiredColumnPreservationTest {

    @TempDir
    Path tempDir;

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        // File-based, matching UserDatabaseDefinitionLoader#jdbcUrl's real H2Local URL shape --
        // same rationale as DestructiveRecreationPassNarrowTypeUniqueColumnTest.
        String url = "jdbc:h2:file:" + tempDir.resolve("regdb").toString().replace('\\', '/')
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void emptyTableRequiredColumnIsRecreatedNotNull() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE areas (id BIGINT PRIMARY KEY, nome VARCHAR(255) NOT NULL, version BIGINT)");
        }

        try (Connection connection = dataSource.getConnection()) {
            DestructiveRecreationPass.executeNarrowTypeDropAndRecreate(connection, "areas", "nome", "VARCHAR(150)", true);
        }

        assertColumnNullable("areas", "nome", false);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class,
                    () -> statement.execute("INSERT INTO areas (id, nome, version) VALUES (1, NULL, 1)"),
                    "the recreated column must genuinely reject NULL, not just carry a cosmetic NOT NULL that was never enforced");
        }
    }

    @Test
    void nonEmptyTableRequiredColumnStaysNullable() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE areas (id BIGINT PRIMARY KEY, nome VARCHAR(255) NOT NULL, version BIGINT)");
            statement.execute("INSERT INTO areas (id, nome, version) VALUES (1, 'Area 1', 1)");
        }

        try (Connection connection = dataSource.getConnection()) {
            // A NOT NULL ADD COLUMN with no default would fail outright against this existing row --
            // the fix must detect the table is non-empty and add it nullable, exactly as pre-fix.
            DestructiveRecreationPass.executeNarrowTypeDropAndRecreate(connection, "areas", "nome", "VARCHAR(150)", true);
        }

        assertColumnNullable("areas", "nome", true);
    }

    @Test
    void nonRequiredColumnStaysNullableRegardlessOfRowCount() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE areas (id BIGINT PRIMARY KEY, nome VARCHAR(255), version BIGINT)");
        }

        try (Connection connection = dataSource.getConnection()) {
            DestructiveRecreationPass.executeNarrowTypeDropAndRecreate(connection, "areas", "nome", "VARCHAR(150)", false);
        }

        assertColumnNullable("areas", "nome", true);
    }

    private void assertColumnNullable(String table, String column, boolean expectedNullable) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String candidate : java.util.List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
                try (ResultSet columns = metadata.getColumns(null, null, candidate, null)) {
                    while (columns.next()) {
                        if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                            boolean actualNullable = "YES".equalsIgnoreCase(columns.getString("IS_NULLABLE"));
                            assertEquals(expectedNullable, actualNullable,
                                    "IS_NULLABLE for " + table + "." + column);
                            return;
                        }
                    }
                }
            }
            throw new AssertionError("column not found: " + table + "." + column);
        }
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; avoids pulling in an H2-specific
     * compile-time dependency. Same shape as DestructiveRecreationPassNarrowTypeUniqueColumnTest's
     * (and this package's many others') copy -- not extracted to a shared utility, matching the
     * established per-test-class convention here. */
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
        public java.util.logging.Logger getParentLogger() {
            return null;
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
