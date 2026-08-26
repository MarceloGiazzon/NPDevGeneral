package com.finalexec.db;

import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.PostgresDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 5, SUPPORT_FEATURES_PLAN_2026-08-26 (B11): the GENERAL "this engine commits DDL implicitly"
 * warning on the ordinary migration path ({@link SchemaLifecycleExecutor#beforeMigrateDecision}),
 * distinct from {@link ConversionHookRunnerH2Test}'s narrower per-hook {@code MIXES_DDL_PATTERN}
 * coverage -- this one fires for an ORDINARY structural migration with no conversion hooks involved
 * at all, once per migration run (not once per statement).
 */
class SchemaLifecycleExecutorNonTransactionalDdlWarningTest {

    private final SchemaLifecycleExecutor executor = new SchemaLifecycleExecutor();
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        SqlDialects.resetActiveForTesting();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("an ordinary migration on an implicit-commit engine (H2) warns exactly once, naming the engine")
    void warnsOnceOnH2BeforeStructuralPassesRun() throws SQLException {
        SqlDialects.setActive(H2Dialect.INSTANCE);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        seedStoredFingerprint(dataSource, "sha256:previous");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:brand-new", Map.of("users", List.of("id", "name", "notes")),
                Map.of("users", List.of("notes")));

        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        SchemaLifecycleExecutor.DestructiveRecreation result;
        try {
            System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
            result = executor.beforeMigrate(dataSource, manifest);
        } finally {
            System.setOut(originalOut);
        }
        assertTrue(result.safeAdditive(), "an ordinary forward upgrade must still proceed normally");

        String logged = capturedOut.toString(StandardCharsets.UTF_8);
        long occurrences = logged.lines().filter(line -> line.contains("commits DDL implicitly")).count();
        assertEquals(1L, occurrences, "must print once per migration run, not per statement: " + logged);
        assertTrue(logged.contains("npdev why B11"), logged);
        assertTrue(logged.contains("'" + H2Dialect.INSTANCE.name() + "'"),
                "must name the actual active engine: " + logged);
    }

    @Test
    @DisplayName("the same migration on a transactional-DDL engine (Postgres) never prints the warning")
    void noWarningOnATransactionalDdlEngine() throws SQLException {
        SqlDialects.setActive(PostgresDialect.INSTANCE);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        seedStoredFingerprint(dataSource, "sha256:previous");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:brand-new", Map.of("users", List.of("id", "name", "notes")),
                Map.of("users", List.of("notes")));

        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
            executor.beforeMigrate(dataSource, manifest);
        } finally {
            System.setOut(originalOut);
        }
        String logged = capturedOut.toString(StandardCharsets.UTF_8);
        assertFalse(logged.contains("commits DDL implicitly"), logged);
    }

    private static void seedStoredFingerprint(DataSource dataSource, String fingerprint) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS npdev_schema_metadata "
                        + "(metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO npdev_schema_metadata (metadata_key, metadata_value, updated_at_ms) VALUES ('schemaFingerprint', ?, ?)")) {
                statement.setString(1, fingerprint);
                statement.setLong(2, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            String toFingerprint,
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.copyOf(businessTableColumns.keySet()),
                businessTableColumns, businessTableAdditiveColumns,
                Map.of("users", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "notes", "VARCHAR(255)")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());
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
