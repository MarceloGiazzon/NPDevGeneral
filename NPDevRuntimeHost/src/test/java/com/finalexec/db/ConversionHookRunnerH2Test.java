package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SchemaDiffItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SER-P7.5 (schema-engine rebuild, Phase 7): one test per numbered rule in {@link ConversionHookRunner}'s
 * javadoc/spec, against real H2. Fixture hooks live under {@code src/test/resources/db/conversion-hooks/}
 * (real classpath resources -- {@code ConversionHookRunner} loads via {@code classpath*:}) with claim
 * item-keys scoped to tables unique to each scenario, so they never cross-match another test's diff.
 */
class ConversionHookRunnerH2Test {

    private DataSource dataSource;
    private List<String[]> history;
    private ConversionHookRunner.HistoryWriter historyWriter;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
        history = new ArrayList<>();
        historyWriter = (label, outcome, details) -> history.add(new String[] {label, outcome, String.valueOf(details)});
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void rule1_noUnresolvedItemsIsANoOpWithNoHistory() throws SQLException {
        exec("CREATE TABLE p75_clean (id BIGINT PRIMARY KEY)");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                "p75_clean", Map.of("id", "BIGINT"), List.of("id"), List.of("id"));

        ConversionHookRunner.run(dataSource, manifest, historyWriter);

        assertTrue(history.isEmpty(), "a fully-converged diff must write zero history rows: " + history);
    }

    @Test
    void rule2_aHookThatClaimsNothingInScopeIsSkippedNotAnError() throws SQLException {
        // p75_other has a live-only column with no matching fixture hook -- proves stale hooks (every
        // fixture on the classpath that doesn't match) are silently skipped, not an error, and the
        // unrelated unresolved item is untouched.
        exec("CREATE TABLE p75_other (id BIGINT PRIMARY KEY, legacy VARCHAR(10))");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                "p75_other", Map.of("id", "BIGINT"), List.of("id"), List.of("id"));

        ConversionHookRunner.run(dataSource, manifest, historyWriter);

        assertTrue(history.isEmpty(), "no hook claims this table; nothing should execute: " + history);
        assertTrue(unresolvedKeys(manifest).stream().anyMatch(k -> k.contains("p75_other") && k.contains("legacy")),
                "the unrelated item must remain unresolved -- ConversionHookRunner never touched it");
    }

    @Test
    void rule6_hookResolvesTheNeedsHookItem_bootGreenNoResidualNoToken() throws SQLException {
        exec("CREATE TABLE p75_resolve (id BIGINT PRIMARY KEY)");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                "p75_resolve", Map.of("id", "BIGINT", "status", "VARCHAR(20)"),
                List.of("id"), List.of("id", "status"));

        Set<String> before = unresolvedKeys(manifest);
        assertTrue(before.stream().anyMatch(k -> k.startsWith("ADD_REQUIRED_COLUMN:p75_resolve:status")), before.toString());

        ConversionHookRunner.run(dataSource, manifest, historyWriter);

        Set<String> after = unresolvedKeys(manifest);
        assertTrue(after.isEmpty(), "the hook's own convert.sql added the column for real -- nothing left unresolved: " + after);

        List<String> outcomes = history.stream().map(row -> row[1]).toList();
        assertTrue(outcomes.contains("HOOK_STARTED"), outcomes.toString());
        assertTrue(outcomes.contains("HOOK_VERIFIED"), outcomes.toString());
        assertTrue(outcomes.contains("HOOK_APPLIED"), outcomes.toString());
        assertTrue(outcomes.contains("RESOLVED"), outcomes.toString());
        assertEquals(0L, singleLongQuery("SELECT COUNT(*) FROM p75_resolve WHERE status IS NULL"));
    }

    @Test
    void rule4_verifyMismatchAbortsBeforeAnyDestructiveStep() throws SQLException {
        exec("CREATE TABLE p75_verifyfail (id BIGINT PRIMARY KEY)");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                "p75_verifyfail", Map.of("id", "BIGINT", "status", "VARCHAR(20)"),
                List.of("id"), List.of("id", "status"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> ConversionHookRunner.run(dataSource, manifest, historyWriter));
        assertTrue(exception.getMessage().contains("verification failed"), exception.getMessage());

        List<String> outcomes = history.stream().map(row -> row[1]).toList();
        assertTrue(outcomes.contains("HOOK_VERIFY_FAILED"), outcomes.toString());
        assertFalse(outcomes.contains("HOOK_APPLIED"), "a failed verify must never reach HOOK_APPLIED: " + outcomes);
    }

    @Test
    void rule5_claimedButNotActuallyResolvedRefusesTheBoot() throws SQLException {
        exec("CREATE TABLE p75_notresolved (id BIGINT PRIMARY KEY)");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                "p75_notresolved", Map.of("id", "BIGINT", "status", "VARCHAR(20)"),
                List.of("id"), List.of("id", "status"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> ConversionHookRunner.run(dataSource, manifest, historyWriter));
        assertTrue(exception.getMessage().contains("still required"), exception.getMessage());
        assertTrue(exception.getMessage().contains("p75-notresolved"), exception.getMessage());

        List<String> outcomes = history.stream().map(row -> row[1]).toList();
        assertTrue(outcomes.contains("REFUSED"), outcomes.toString());
    }

    @Test
    void rule7_hookSqlFailureRollsBackTheWholeTransactionAndRefuses() throws SQLException {
        exec("CREATE TABLE p75_sqlerror (id BIGINT PRIMARY KEY)");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                "p75_sqlerror", Map.of("id", "BIGINT", "status", "VARCHAR(20)"),
                List.of("id"), List.of("id", "status"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> ConversionHookRunner.run(dataSource, manifest, historyWriter));
        assertTrue(exception.getMessage().contains("p75-sqlerror"), exception.getMessage());

        assertEquals(0L, singleLongQuery("SELECT COUNT(*) FROM p75_sqlerror WHERE id = 999"),
                "the INSERT that ran before the failing ALTER must have been rolled back too (own-transaction, atomic)");

        List<String> outcomes = history.stream().map(row -> row[1]).toList();
        assertTrue(outcomes.contains("HOOK_FAILED"), outcomes.toString());
    }

    @Test
    void rule3_twoMatchingHooksExecuteInAscendingIdOrder() throws SQLException {
        exec("CREATE TABLE p75_order (id BIGINT PRIMARY KEY)");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                "p75_order", Map.of("id", "BIGINT", "col_a", "VARCHAR(20)", "col_b", "VARCHAR(20)"),
                List.of("id"), List.of("id", "col_a", "col_b"));

        ConversionHookRunner.run(dataSource, manifest, historyWriter);

        List<String> startedOrder = history.stream()
                .filter(row -> "HOOK_STARTED".equals(row[1]))
                .map(row -> row[0])
                .toList();
        assertEquals(List.of("CONVERSION_HOOK:p75-order-1-a", "CONVERSION_HOOK:p75-order-2-b"), startedOrder);
    }

    @Test
    void rule6_unclaimedItemsAreUntouchedAndRemainUnresolved() throws SQLException {
        // No fixture hook claims p75_other's dropped 'legacy' column -- rule 6's other half: an
        // unclaimed item is not this runner's concern at all (still exactly as token-gated as before,
        // by the existing SchemaDeltaReport/token code downstream that ConversionHookRunner never
        // touches for unclaimed items).
        exec("CREATE TABLE p75_other (id BIGINT PRIMARY KEY, legacy VARCHAR(10))");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                "p75_other", Map.of("id", "BIGINT"), List.of("id"), List.of("id"));

        Set<String> before = unresolvedKeys(manifest);
        ConversionHookRunner.run(dataSource, manifest, historyWriter);
        Set<String> after = unresolvedKeys(manifest);

        assertEquals(before, after, "an item no hook claims must be completely unaffected by the run");
    }

    // ---- helpers ----

    private void exec(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long singleLongQuery(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : -1L;
        }
    }

    private Set<String> unresolvedKeys(SchemaLifecycleExecutor.SchemaManifest manifest) {
        CurrentSchema current = new CurrentSchemaReader().read(dataSource);
        SchemaDiff diff = new SchemaDiffEngine().diff(DesiredSchemaFactory.fromManifest(manifest),
                ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        Set<String> keys = new LinkedHashSet<>();
        for (SchemaDiffItem item : diff.items()) {
            keys.add(item.itemKey());
        }
        return keys;
    }

    /** One-table manifest: {@code liveColumns} exist in the H2 DB already (via the test's own {@code
     *  CREATE TABLE}); {@code desiredColumns} is the full desired set (a superset naming the column the
     *  scenario's fixture hook is meant to add), with types from {@code columnTypes}. */
    private static SchemaLifecycleExecutor.SchemaManifest manifestFor(String table, Map<String, String> columnTypes,
            List<String> liveColumns, List<String> desiredColumns) {
        List<String> required = new ArrayList<>(desiredColumns);
        required.removeAll(liveColumns);
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:p75-test",
                List.of(), List.of(table),
                Map.of(table, desiredColumns),
                Map.of(table, List.of()), // additiveColumns: empty -> every missing column is NEEDS_HOOK
                Map.of(table, columnTypes),
                Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "",
                Map.of(table, List.copyOf(required)), Map.of(), Map.of(), Map.of());
    }

    /** Minimal {@link DataSource} over {@link DriverManager} (no H2-specific compile dependency). */
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
