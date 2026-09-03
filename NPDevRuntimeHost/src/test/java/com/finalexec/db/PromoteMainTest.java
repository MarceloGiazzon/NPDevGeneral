package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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
 * 3.3 (B10 one-command H2-&gt;Postgres promotion, package 3.3): {@link PromoteMain}'s own coverage,
 * split by what needs a real generated app and what does not. Argument-parsing and
 * connection-failure branches (below) follow {@code SchemaVerifyMainTest}'s own documented
 * discipline for this {@code *Main} family -- {@code run}'s FULL arc (schema realization via
 * {@link SchemaLifecycleExecutor#loadManifest}) needs a real generated app's V1__ migration on the
 * classpath, absent in this template repo's own test run, so that half is proven by running it for
 * real, not faked here. But {@link PromoteMain#runDryRun} and
 * {@link PromoteMain#runAfterSchemaRealized} -- the preview/apply/verify arc, extracted precisely so
 * a schema that is ALREADY realized (simulated here with two hand-built H2 tables) can be tested
 * directly, with no manifest-loading or Flyway dependency at all -- ARE fully covered below.
 * {@link PromotionVerifierTest} and {@link SchemaManifestWithEngineTest} cover the rest of this
 * package's genuinely new, fully engine-agnostic logic.
 */
class PromoteMainTest {

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
    void unrecognizedArgumentRefusesWithCouldNotDetermine() {
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        int exitCode = PromoteMain.run(
                new String[] {"--bogus"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errBuffer, true, StandardCharsets.UTF_8));

        assertEquals(PromoteMain.EXIT_COULD_NOT_DETERMINE, exitCode);
        assertTrue(errBuffer.toString(StandardCharsets.UTF_8).contains("unrecognized or incomplete argument"));
    }

    @Test
    void missingUrlRefusesWithUsage() {
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        int exitCode = PromoteMain.run(
                new String[] {"--to", "jdbc:h2:mem:somewhere"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errBuffer, true, StandardCharsets.UTF_8));

        assertEquals(PromoteMain.EXIT_COULD_NOT_DETERMINE, exitCode);
        assertTrue(errBuffer.toString(StandardCharsets.UTF_8).contains("usage:"));
    }

    @Test
    void missingToRefusesWithUsage() {
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        int exitCode = PromoteMain.run(
                new String[] {"--url", "jdbc:h2:mem:somewhere"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errBuffer, true, StandardCharsets.UTF_8));

        assertEquals(PromoteMain.EXIT_COULD_NOT_DETERMINE, exitCode);
        assertTrue(errBuffer.toString(StandardCharsets.UTF_8).contains("usage:"));
    }

    @Test
    void unreachableSourceIsHandledWithoutCrashing() {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        int exitCode = PromoteMain.run(
                new String[] {
                        "--url", "jdbc:npdev-unsupported-test-scheme://nope",
                        "--to", "jdbc:h2:mem:promote-main-test-target;DB_CLOSE_DELAY=-1",
                },
                new PrintStream(outBuffer, true, StandardCharsets.UTF_8),
                new PrintStream(errBuffer, true, StandardCharsets.UTF_8));

        // Same branch-on-what-actually-happened shape as SchemaVerifyMainTest#invalidTargetIsHandledWithoutCrashing:
        // an InMemory-storage template app short-circuits on manifest.physicalDatabase()=false before
        // ever touching the URL.
        if (exitCode == PromoteMain.EXIT_OK) {
            String out = outBuffer.toString(StandardCharsets.UTF_8);
            assertTrue(out.contains("no physical database"), () -> "unexpected stdout: " + out);
        } else {
            assertEquals(PromoteMain.EXIT_COULD_NOT_DETERMINE, exitCode);
            String err = errBuffer.toString(StandardCharsets.UTF_8);
            assertTrue(err.contains("could not connect to source"), () -> "unexpected stderr: " + err);
        }
    }

    @Test
    void unreachableTargetIsHandledWithoutCrashing() {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        int exitCode = PromoteMain.run(
                new String[] {
                        "--url", "jdbc:h2:mem:promote-main-test-source;DB_CLOSE_DELAY=-1",
                        "--to", "jdbc:npdev-unsupported-test-scheme://nope",
                },
                new PrintStream(outBuffer, true, StandardCharsets.UTF_8),
                new PrintStream(errBuffer, true, StandardCharsets.UTF_8));

        if (exitCode == PromoteMain.EXIT_OK) {
            String out = outBuffer.toString(StandardCharsets.UTF_8);
            assertTrue(out.contains("no physical database"), () -> "unexpected stdout: " + out);
        } else {
            assertEquals(PromoteMain.EXIT_COULD_NOT_DETERMINE, exitCode);
            String err = errBuffer.toString(StandardCharsets.UTF_8);
            assertTrue(err.contains("could not connect to target"), () -> "unexpected stderr: " + err);
        }
    }

    @Test
    void dryRunPreviewsWithoutWritingAndPrintsSourceTargetCounts() throws SQLException {
        source = freshH2();
        target = freshH2();
        createWidgets(source);
        insertWidget(source, 1L, "alpha");
        // target has NO widgets table at all -- dry-run must not create one.

        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        int exitCode = PromoteMain.runDryRun(
                source, target, widgetsManifest(), new PrintStream(outBuffer, true, StandardCharsets.UTF_8));

        assertEquals(PromoteMain.EXIT_OK, exitCode);
        String out = outBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("--dry-run"), out);
        assertTrue(out.contains("source=1"), () -> "expected the source row count in: " + out);
        assertFalse(tableExists(target, "widgets"), "dry-run must never create the target table");
    }

    @Test
    void afterSchemaRealizedCopiesAndReportsPromotionVerified() throws SQLException {
        source = freshH2();
        target = freshH2();
        createWidgets(source);
        createWidgets(target);
        insertWidget(source, 1L, "alpha");
        insertWidget(source, 2L, "beta");

        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        int exitCode = PromoteMain.runAfterSchemaRealized(
                source, target, widgetsManifest(), new PrintStream(outBuffer, true, StandardCharsets.UTF_8));

        assertEquals(PromoteMain.EXIT_OK, exitCode);
        String out = outBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("PROMOTION VERIFIED"), out);
        assertEquals(2L, countRows(target, "widgets"));
    }

    @Test
    void afterSchemaRealizedReportsNeedsAttentionWhenTargetTableIsMissing() throws SQLException {
        source = freshH2();
        target = freshH2();
        createWidgets(source);
        insertWidget(source, 1L, "alpha");
        // target has no widgets table -- apply must fail this table, not throw.

        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        int exitCode = PromoteMain.runAfterSchemaRealized(
                source, target, widgetsManifest(), new PrintStream(outBuffer, true, StandardCharsets.UTF_8));

        assertEquals(PromoteMain.EXIT_NEEDS_ATTENTION, exitCode);
        String out = outBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("NOT MATCHED"), out);
        assertTrue(out.contains("PROMOTION NOT VERIFIED"), out);
    }

    private static SchemaLifecycleExecutor.SchemaManifest widgetsManifest() {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:promote-main-test", List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(100)")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());
    }

    private DataSource freshH2() {
        return new UrlDataSource("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

    private void createWidgets(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(100))");
        }
    }

    private void insertWidget(DataSource dataSource, long id, String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("INSERT INTO widgets (id, name) VALUES (?, ?)")) {
            statement.setLong(1, id);
            statement.setString(2, name);
            statement.executeUpdate();
        }
    }

    private boolean tableExists(DataSource dataSource, String table) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return !SchemaLifecycleExecutor.readActualColumns(connection.getMetaData(), table).isEmpty();
        }
    }

    private long countRows(DataSource dataSource, String table) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
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
