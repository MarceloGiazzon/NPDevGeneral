package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 (REAL_LIFT_PLAN_2026-09-03, B5 "real lift"): direct, isolated tests for {@link
 * SchemaCompatibilityVerdict#assess} -- no {@code npdev_schema_history} seeding, no Trigger C, no
 * {@code ImpactReportWriter} -- just the pure comparison this class exists to make, against a real H2
 * table. Written specifically to separate "is the VERDICT LOGIC reliable" from "is the surrounding
 * {@code SchemaLifecycleExecutorDatabaseMigratedPastBuildTest} integration harness reliable" (QUAL-55:
 * that integration test showed intermittent, unexplained failures across full-gate runs; this class
 * exists to prove -- or disprove -- that the verdict computation itself is the source). {@code
 * @RepeatedTest} on the incompatible case specifically, since a single pass proves nothing about
 * intermittent behavior.
 */
class SchemaCompatibilityVerdictTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @RepeatedTest(20)
    void anExtraNotNullColumnWithNoDefaultIsAlwaysIncompatible() throws SQLException {
        exec("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL, "
                + "bonus_column VARCHAR(50) NOT NULL)");
        exec("INSERT INTO users (id, name, bonus_column) VALUES (1, 'Alpha', 'required')");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                Map.of("users", List.of("id", "name")));

        SchemaCompatibilityVerdict.Verdict verdict = SchemaCompatibilityVerdict.assess(dataSource, manifest);

        assertFalse(verdict.compatible(), "a NOT NULL column with no default must always be INCOMPATIBLE: "
                + verdict.differences());
        assertEquals(1, verdict.incompatible().size(), verdict.differences().toString());
        assertEquals("bonus_column", verdict.incompatible().get(0).column());
    }

    @RepeatedTest(20)
    void anExtraNullableColumnIsAlwaysTolerable() throws SQLException {
        exec("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50), bonus_column VARCHAR(50))");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                Map.of("users", List.of("id", "name")));

        SchemaCompatibilityVerdict.Verdict verdict = SchemaCompatibilityVerdict.assess(dataSource, manifest);

        assertTrue(verdict.compatible(), verdict.differences().toString());
    }

    @Test
    void aMissingDesiredColumnIsAlwaysIncompatible() throws SQLException {
        // The classic rollback shape: this build wants "nickname", live does not have it.
        exec("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(50))");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                Map.of("users", List.of("id", "name", "nickname")));

        SchemaCompatibilityVerdict.Verdict verdict = SchemaCompatibilityVerdict.assess(dataSource, manifest);

        assertFalse(verdict.compatible());
        assertTrue(verdict.incompatible().stream().anyMatch(d -> "nickname".equals(d.column())), verdict.differences().toString());
    }

    private void exec(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifestFor(Map<String, List<String>> businessTableColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:verdict-test", List.of(), List.copyOf(businessTableColumns.keySet()),
                businessTableColumns, Map.of(),
                Map.of("users", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "nickname", "VARCHAR(50)")),
                Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "", Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static final class UrlDataSource implements DataSource {
        private final String url;

        private UrlDataSource(String url) {
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
