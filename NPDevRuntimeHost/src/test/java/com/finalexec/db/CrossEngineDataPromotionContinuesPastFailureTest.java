package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3.3 (B10 one-command H2-&gt;Postgres promotion, package 3.3), done-when #4: "a failed table does
 * not abort the rest (already true -- keep a test pinning it, since QUAL-38 shows this regressed
 * once already under a well-intentioned change)". {@code CrossEngineDataPromotionTest} already pins
 * the single-table shape of this (a missing target table reports a clear per-table failure rather
 * than throwing) but is {@code @Tag("integration")}, Testcontainers-Postgres-gated, and only ever
 * declares ONE business table -- it cannot prove the loop actually CONTINUES to a later table, only
 * that one table's own failure is reported correctly. This class closes that gap, entirely with H2
 * on both sides (the mechanism is engine-agnostic by construction, so this proves the loop behavior
 * QUAL-38 regressed without needing Docker) -- two tables, one whose target is missing, one whose
 * target exists: both must appear in the result, the missing one failed and the present one copied.
 */
class CrossEngineDataPromotionContinuesPastFailureTest {

    private DataSource source;
    private DataSource target;

    @AfterEach
    void tearDown() throws SQLException {
        drop(source);
        drop(target);
    }

    private void drop(DataSource dataSource) throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    @DisplayName("QUAL-38 pin: a table whose target is missing does not stop LATER tables from being copied")
    void aMissingTargetTableDoesNotAbortSubsequentTables() throws SQLException {
        source = freshH2();
        target = freshH2();

        // "widgets" (declared FIRST, so it fails FIRST) has no target table at all.
        // "gadgets" (declared SECOND) exists on both sides and must still be copied.
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO widgets (id, name) VALUES (1, 'alpha')");
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO gadgets (id, name) VALUES (10, 'gizmo')");
            statement.execute("INSERT INTO gadgets (id, name) VALUES (11, 'widget-but-actually-a-gadget')");
        }
        try (Connection connection = target.getConnection(); Statement statement = connection.createStatement()) {
            // Deliberately NOT creating "widgets" here.
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:continues-past-failure",
                List.of(), List.of("widgets", "gadgets"),
                Map.of("widgets", List.of("id", "name"), "gadgets", List.of("id", "name")),
                Map.of("widgets", List.of(), "gadgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR"),
                        "gadgets", Map.of("id", "BIGINT", "name", "VARCHAR")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());

        CrossEngineDataPromotion.PromotionResult result = CrossEngineDataPromotion.apply(source, target, manifest);

        assertFalse(result.allMatched(), "widgets' failure must be visible in the overall verdict");
        assertEquals(2, result.tables().size(), "BOTH tables must be reported -- the loop must not have "
                + "stopped after widgets failed");

        CrossEngineDataPromotion.TableCopyResult widgetsResult = result.tables().stream()
                .filter(t -> t.table().equals("widgets")).findFirst().orElseThrow();
        assertFalse(widgetsResult.matched());
        assertTrue(widgetsResult.error() != null && widgetsResult.error().contains("does not exist"),
                () -> "unexpected widgets error: " + widgetsResult.error());

        CrossEngineDataPromotion.TableCopyResult gadgetsResult = result.tables().stream()
                .filter(t -> t.table().equals("gadgets")).findFirst().orElseThrow();
        assertTrue(gadgetsResult.matched(), () -> "gadgets must have copied cleanly despite widgets' earlier "
                + "failure: " + gadgetsResult);
        assertEquals(2L, gadgetsResult.rowsCopied());
        assertEquals(2L, gadgetsResult.targetRowCountAfter());
    }

    private DataSource freshH2() {
        return new UrlDataSource("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

    /** Minimal {@link DataSource} over {@link DriverManager} -- matches every sibling test's own copy. */
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
