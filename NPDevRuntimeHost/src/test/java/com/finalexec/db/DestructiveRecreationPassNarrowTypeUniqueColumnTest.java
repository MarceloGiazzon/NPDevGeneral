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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REG-58 (found live, 2026-07-28, `docs/POST_PUBLIC_PLAN.md` P4.3 -- WmsOffice's real database,
 * post-REG-53, narrowing `identity_password_reset_tokens.token_hash` VARCHAR(255)->VARCHAR(64)).
 *
 * <p>{@link DestructiveRecreationPass#executeNarrowTypeDropAndRecreate} used to attempt a plain
 * {@code ALTER TABLE ... DROP COLUMN} with no regard for a unique index/constraint still
 * referencing that column. H2 (and Postgres identically) refuses: "Column may be referenced by
 * ...". This is not a rare shape -- any model field declared unique
 * (`SchemaRealizationEmitter`'s `ux_<table>_<column>` bootstrap index) that later gets a narrower
 * `maxLength` hits it. The 155/91 KB proof matrices never covered this combination (narrow-type +
 * unique column) before this finding.
 */
class DestructiveRecreationPassNarrowTypeUniqueColumnTest {

    @TempDir
    Path tempDir;

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        // File-based, not in-memory: matches UserDatabaseDefinitionLoader#jdbcUrl's exact H2Local
        // URL shape (MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE). This is not
        // cosmetic -- the REG-58 bug (DROP COLUMN blocked by a referencing unique index) only
        // reproduced against a file-backed MVStore database; an in-memory H2 database with the same
        // MODE=PostgreSQL flag did NOT reproduce it while this proof was being built.
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
    void narrowingAUniqueConstrainedColumnDoesNotThrow() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE identity_password_reset_tokens ("
                    + "id BIGINT PRIMARY KEY, token_hash VARCHAR(255), version BIGINT, "
                    + "tenant_id VARCHAR(120) NOT NULL DEFAULT 'default')");
            // The real V1 bootstrap (SchemaRealizationEmitter, see the generated
            // db/schema-realization/V1__npdev_schema_realization.sql) makes every declared-unique field's
            // index TENANT-SCOPED: "ux_..._token_hash ON identity_password_reset_tokens (tenant_id,
            // token_hash)" -- a COMPOSITE index, not a bare single-column one. That distinction is load-
            // bearing: H2 silently auto-drops a single-column index when its only column is dropped, but
            // refuses ("Column may be referenced by ...") for a composite index, since the remaining
            // column (tenant_id) still needs it. A single-column repro (tried first while building this
            // proof) never reproduced the live failure for exactly this reason.
            statement.execute("CREATE UNIQUE INDEX ux_identity_password_reset_tokens_token_hash "
                    + "ON identity_password_reset_tokens (tenant_id, token_hash)");
            statement.execute("INSERT INTO identity_password_reset_tokens (id, token_hash, version, tenant_id) "
                    + "VALUES (1, 'abc123', 1, 'default')");
        }

        try (Connection connection = dataSource.getConnection()) {
            assertDoesNotThrow(() -> DestructiveRecreationPass.executeNarrowTypeDropAndRecreate(
                    connection, "identity_password_reset_tokens", "token_hash", "VARCHAR(64)"),
                    "REG-58: a unique index on the narrowed column must not block the drop-and-recreate");
        }

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            Integer columnSize = findColumnSize(metadata, "identity_password_reset_tokens", "token_hash");
            assertEquals(true, columnSize != null, "recreated column must exist");
            assertEquals(64, columnSize, "recreated column must carry the narrowed length");
        }
    }

    // DATABASE_TO_LOWER=TRUE (matching the real app's URL) stores unquoted identifiers in lower
    // case, unlike plain H2 mode's default upper case -- try both, same as this package's other
    // metadata-checking tests (e.g. SchemaLifecycleExecutorInPlaceRenameTest#hasColumn).
    private static Integer findColumnSize(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : java.util.List.of(table.toLowerCase(java.util.Locale.ROOT), table.toUpperCase(java.util.Locale.ROOT))) {
            try (ResultSet columns = metadata.getColumns(null, null, candidate, null)) {
                while (columns.next()) {
                    if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                        return columns.getInt("COLUMN_SIZE");
                    }
                }
            }
        }
        return null;
    }

    @Test
    void narrowingAPlainColumnWithNoIndexStillWorks() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE areas (id BIGINT PRIMARY KEY, nome VARCHAR(255), version BIGINT)");
            statement.execute("INSERT INTO areas (id, nome, version) VALUES (1, 'Area 1', 1)");
        }

        try (Connection connection = dataSource.getConnection()) {
            assertDoesNotThrow(() -> DestructiveRecreationPass.executeNarrowTypeDropAndRecreate(
                    connection, "areas", "nome", "VARCHAR(150)"),
                    "the un-indexed case (already working before REG-58) must keep working");
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
