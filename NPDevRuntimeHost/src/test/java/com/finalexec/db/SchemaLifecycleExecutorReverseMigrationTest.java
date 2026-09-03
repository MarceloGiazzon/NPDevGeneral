package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B5-B (boundary-lift 2026-09-02, package 4.1): end-to-end proof that {@link ReverseMigrationPlanner}
 * actually reverts a live database and clears the B5 "ahead" refusal for the next normal boot. Same
 * Trigger C setup as {@link SchemaLifecycleExecutorSchemaAheadDiagnosisTest} (hand-seeded history rows
 * are an equivalent, established stand-in for a real newer-build boot -- that sibling test's own
 * "graceful fallback" scenario already relies on this), extended to run REAL DDL simulating what a
 * newer build did, then drive the reverse path and re-check the REAL {@link SchemaLifecycleExecutor}.
 */
class SchemaLifecycleExecutorReverseMigrationTest {

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
    @DisplayName("a pure-superset ahead diff reverts for real, and the next normal boot at build N proceeds clean")
    void reverseMigrationRevertsDdlAndClearsTheAheadRefusal() throws SQLException {
        // Build N fresh-installs for real -- afterMigrate records N's own fingerprint + history row.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = manifest("sha256:N",
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")));
        executor.afterMigrate(dataSource, manifestBuildN, null, false);

        // A newer build (N+1) migrated this database further: added a column and a whole table. Real
        // DDL, simulating exactly what that build's own boot would have run -- ReverseMigrationPlanner
        // never trusts a recorded snapshot, only the live schema, so there is nothing to fabricate here
        // beyond the DDL itself and a Trigger C history row (same equivalence the sibling
        // SchemaAheadDiagnosisTest's own "no snapshot recorded" scenario already establishes).
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE widgets ADD COLUMN notes VARCHAR(255)");
            statement.execute("CREATE TABLE audit_log (id BIGINT PRIMARY KEY, event VARCHAR(100))");
        }
        // Anchored to build N's OWN recorded timestamp, not a fresh System.currentTimeMillis() call --
        // deliberately not the sibling SchemaAheadDiagnosisTest's own +1 hour offset, which works there
        // only because that test never writes another real-time-stamped row afterward. THIS test's own
        // execute() call DOES (a real, later System.currentTimeMillis() write once the reverse migration
        // applies), and real wall-clock time only moves forward -- so anchoring 1ms past N's own already-
        // recorded value guarantees N+1 > N here, AND guarantees any later real write (which happens
        // only after several more real DB round-trips: multiple CurrentSchemaReader introspections, a
        // snapshot write, real DDL) exceeds it too, with no dependency on how much real time any of that
        // actually takes.
        seedHistoryRow(dataSource, "sha256:N+1", latestAppliedTimestamp(dataSource, "sha256:N") + 1L);

        // The live database is now ahead of build N -- plan() must find exactly the two destructive
        // items a pure superset produces, and execute() must refuse a wrong token before touching DDL.
        ReverseMigrationPlanner.Plan plan = ReverseMigrationPlanner.plan(dataSource, manifestBuildN);
        ReverseMigrationPlanner.Ready ready = assertInstanceOf(ReverseMigrationPlanner.Ready.class, plan);
        assertEquals(2, ready.items().size(), "expected exactly DROP_COLUMN widgets.notes + DROP_TABLE audit_log: " + ready.items());
        assertEquals("sha256:N+1", ready.aheadFingerprint());

        ReverseMigrationPlanner.ExecutionResult mismatch =
                ReverseMigrationPlanner.execute(dataSource, manifestBuildN, "not-the-real-token");
        assertEquals(ReverseMigrationPlanner.Outcome.TOKEN_MISMATCH, mismatch.outcome());
        assertTrue(columnExists(dataSource, "WIDGETS", "NOTES"), "a mismatched token must never run DDL");
        assertTrue(tableExists(dataSource, "AUDIT_LOG"), "a mismatched token must never run DDL");

        ReverseMigrationPlanner.ExecutionResult applied =
                ReverseMigrationPlanner.execute(dataSource, manifestBuildN, ready.ackToken());
        assertEquals(ReverseMigrationPlanner.Outcome.APPLIED, applied.outcome());
        assertFalse(columnExists(dataSource, "WIDGETS", "NOTES"), "reverse migration must actually drop the column");
        assertFalse(tableExists(dataSource, "AUDIT_LOG"), "reverse migration must actually drop the table");

        // The B5-ahead gate must now clear -- the whole point of the history row execute() writes.
        assertTrue(SchemaHistoryStore.databaseMigratedPastThisBuild(dataSource, manifestBuildN).isEmpty(),
                "the next normal boot at build N must no longer see itself as behind");

        // And the real executor's own beforeMigrate, run directly (same scope as the sibling diagnosis
        // test -- not the full Flyway-driven orchestrator), must proceed with no refusal and nothing
        // destructive left to do: the live schema already matches N exactly.
        SchemaLifecycleExecutor.DestructiveRecreation recreation = executor.beforeMigrate(dataSource, manifestBuildN);
        assertFalse(recreation.performed(), "nothing destructive should be left to do -- the live schema already matches build N");
        assertTrue(recreation.droppedTables().isEmpty());
    }

    @Test
    @DisplayName("a rename-shaped ahead diff refuses as ambiguous, and no DDL runs")
    void renameShapedDiffRefusesAsAmbiguous() throws SQLException {
        // The live database (as if a newer build renamed "bar" to "baz") has "baz", not "bar" --
        // exactly the shape a plain drop would silently lose data a rename would have preserved.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), baz VARCHAR(50))");
        }
        SchemaLifecycleExecutor.SchemaManifest manifestBuildN = manifest("sha256:N",
                Map.of("widgets", List.of("id", "name", "bar")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "bar", "VARCHAR(50)")));
        executor.afterMigrate(dataSource, manifestBuildN, null, false);
        // Anchored to build N's own recorded timestamp -- see the sibling test above for why (a BLOCKED
        // plan never writes a history row here, so this test does not strictly need the property, but
        // anchoring keeps both tests' Trigger C setup identical rather than one being a special case).
        seedHistoryRow(dataSource, "sha256:N+1", latestAppliedTimestamp(dataSource, "sha256:N") + 1L);

        ReverseMigrationPlanner.Plan plan = ReverseMigrationPlanner.plan(dataSource, manifestBuildN);
        ReverseMigrationPlanner.Blocked blocked = assertInstanceOf(ReverseMigrationPlanner.Blocked.class, plan);
        assertTrue(blocked.reason().contains("widgets.bar"), blocked.reason());

        ReverseMigrationPlanner.ExecutionResult result =
                ReverseMigrationPlanner.execute(dataSource, manifestBuildN, "any-token-at-all");
        assertEquals(ReverseMigrationPlanner.Outcome.BLOCKED, result.outcome());
        assertTrue(columnExists(dataSource, "WIDGETS", "BAZ"), "a blocked plan must never run DDL");
    }

    private static boolean columnExists(DataSource dataSource, String table, String column) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet resultSet = metadata.getColumns(null, null, table, column)) {
                return resultSet.next();
            }
        }
    }

    private static boolean tableExists(DataSource dataSource, String table) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet resultSet = metadata.getTables(null, null, table, null)) {
                return resultSet.next();
            }
        }
    }

    /** The real {@code applied_at_utc} {@link SchemaLifecycleExecutor#afterMigrate} recorded for
     *  {@code toFingerprint}'s own history row -- used to anchor a seeded Trigger C row deterministically
     *  after it, rather than guessing an offset from a fresh {@code System.currentTimeMillis()} call. */
    private static long latestAppliedTimestamp(DataSource dataSource, String toFingerprint) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT applied_at_utc FROM npdev_schema_history WHERE to_fingerprint = ? "
                                + "ORDER BY applied_at_utc DESC")) {
            statement.setString(1, toFingerprint);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("no npdev_schema_history row found for " + toFingerprint);
                }
                return resultSet.getLong(1);
            }
        }
    }

    private static void seedHistoryRow(DataSource dataSource, String toFingerprint, long appliedAtUtc) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS npdev_schema_history "
                        + "(id TEXT PRIMARY KEY, applied_at_utc BIGINT NOT NULL, from_fingerprint TEXT, "
                        + "to_fingerprint TEXT, classification TEXT, items_json TEXT, ack_token_used TEXT, outcome TEXT NOT NULL)");
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO npdev_schema_history (id, applied_at_utc, from_fingerprint, to_fingerprint, "
                            + "classification, items_json, ack_token_used, outcome) VALUES (?, ?, NULL, ?, NULL, NULL, NULL, 'APPLIED')")) {
                statement.setString(1, java.util.UUID.randomUUID().toString());
                statement.setLong(2, appliedAtUtc);
                statement.setString(3, toFingerprint);
                statement.executeUpdate();
            }
        }
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            String toFingerprint,
            Map<String, List<String>> businessTableColumns,
            Map<String, Map<String, String>> businessTableColumnTypes) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.copyOf(businessTableColumns.keySet()),
                businessTableColumns, Map.of(),
                businessTableColumnTypes,
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", "", Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; avoids pulling in an H2-specific
     *  compile-time dependency -- same copy every sibling test file in this package carries. */
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
