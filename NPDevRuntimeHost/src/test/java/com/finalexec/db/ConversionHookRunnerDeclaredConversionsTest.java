package com.finalexec.db;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S7 Phase B (B13 declarative conversion vocabulary): proves, against a real H2 database, that the
 * EXACT convert.sql {@link ConversionHookEmitter} (NPDevGenerator) generates for each of the three
 * declarative ops actually converts real data when run through the real {@link ConversionHookRunner}
 * -- the fixtures under {@code src/test/resources/db/conversion-hooks/s7-*} are captured verbatim
 * from {@code ConversionHookEmitterDeclaredConversionsTest}'s own generator output (only the table
 * names were changed, to keep this suite's claims from colliding with the sibling P7.5 fixtures).
 * Idempotence (B12's guarantee) is proven by running each hook a second time and confirming it is a
 * true no-op -- {@code ConversionHookRunner.run} returns {@code false} because nothing remains
 * unresolved.
 */
class ConversionHookRunnerDeclaredConversionsTest {

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
    void copyConversionPopulatesTheNewColumnFromTheSourceColumn_thenIsANoOpOnRerun() throws SQLException {
        exec("CREATE TABLE s7_orders (id BIGINT PRIMARY KEY, legacy_code VARCHAR(50))");
        exec("INSERT INTO s7_orders (id, legacy_code) VALUES (1, 'ORD-LEGACY-1')");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                "s7_orders", Map.of("id", "BIGINT", "legacy_code", "VARCHAR(50)"),
                List.of("id", "legacy_code"), List.of("id", "legacy_code", "external_ref"));

        boolean applied = ConversionHookRunner.run(dataSource, manifest, historyWriter);

        assertTrue(applied, "the copy hook must have run");
        assertEquals("ORD-LEGACY-1", singleStringQuery("SELECT external_ref FROM s7_orders WHERE id = 1"));
        assertTrue(history.stream().map(row -> row[1]).toList().contains("HOOK_APPLIED"));

        history.clear();
        boolean appliedAgain = ConversionHookRunner.run(dataSource, manifest, historyWriter);
        assertTrue(history.isEmpty(), "a fully-converged conversion must write zero history on rerun: " + history);
        assertEquals(false, appliedAgain, "rerunning an already-resolved conversion must be a true no-op");
        assertEquals("ORD-LEGACY-1", singleStringQuery("SELECT external_ref FROM s7_orders WHERE id = 1"),
                "the value must be unchanged by the idempotent rerun");
    }

    @Test
    void splitConversionPopulatesBothTargetColumnsFromTheSourceColumn_thenIsANoOpOnRerun() throws SQLException {
        exec("CREATE TABLE s7_customers (id BIGINT PRIMARY KEY, full_name VARCHAR(100))");
        exec("INSERT INTO s7_customers (id, full_name) VALUES (1, 'Ada Lovelace')");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                "s7_customers", Map.of("id", "BIGINT", "full_name", "VARCHAR(100)"),
                List.of("id", "full_name"), List.of("id", "full_name", "first_name", "last_name"));

        boolean applied = ConversionHookRunner.run(dataSource, manifest, historyWriter);

        assertTrue(applied, "the split hook must have run");
        assertEquals("Ada", singleStringQuery("SELECT first_name FROM s7_customers WHERE id = 1"));
        assertEquals("Lovelace", singleStringQuery("SELECT last_name FROM s7_customers WHERE id = 1"));

        history.clear();
        boolean appliedAgain = ConversionHookRunner.run(dataSource, manifest, historyWriter);
        assertTrue(history.isEmpty(), "a fully-converged conversion must write zero history on rerun: " + history);
        assertEquals(false, appliedAgain);
        assertEquals("Ada", singleStringQuery("SELECT first_name FROM s7_customers WHERE id = 1"),
                "the value must be unchanged by the idempotent rerun");
    }

    @Test
    void splitConversionOnAValueWithNoSpaceFailsTheBootLoudlyRatherThanLeavingANullResidue() throws SQLException {
        // X0 rule, data-layer half: a row the UPDATE cannot populate (no space in full_name) is left
        // NULL by the WHERE guard, and the closing ALTER COLUMN ... SET NOT NULL must then fail the
        // whole hook rather than silently leaving first_name/last_name null on that row.
        exec("CREATE TABLE s7_customers (id BIGINT PRIMARY KEY, full_name VARCHAR(100))");
        exec("INSERT INTO s7_customers (id, full_name) VALUES (1, 'Madonna')");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                "s7_customers", Map.of("id", "BIGINT", "full_name", "VARCHAR(100)"),
                List.of("id", "full_name"), List.of("id", "full_name", "first_name", "last_name"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> ConversionHookRunner.run(dataSource, manifest, historyWriter));
        assertTrue(exception.getMessage().contains("s7-split-name"), exception.getMessage());
    }

    @Test
    void lookupConversionSetsTheForeignKeyFromTheMatchedRow_thenIsANoOpOnRerun() throws SQLException {
        exec("CREATE TABLE s7_products (id UUID PRIMARY KEY, sku VARCHAR(20))");
        exec("CREATE TABLE s7_order_lines (id BIGINT PRIMARY KEY, product_sku VARCHAR(20))");
        UUID productId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO s7_products (id, sku) VALUES (?, 'SKU-1')")) {
            statement.setObject(1, productId);
            statement.executeUpdate();
        }
        exec("INSERT INTO s7_order_lines (id, product_sku) VALUES (1, 'SKU-1')");
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(
                "s7_order_lines", Map.of("id", "BIGINT", "product_sku", "VARCHAR(20)"),
                List.of("id", "product_sku"), List.of("id", "product_sku", "product_id"));

        boolean applied = ConversionHookRunner.run(dataSource, manifest, historyWriter);

        assertTrue(applied, "the lookup hook must have run");
        Object resolved;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT product_id FROM s7_order_lines WHERE id = 1");
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            resolved = resultSet.getObject(1);
        }
        assertEquals(productId, resolved);

        history.clear();
        boolean appliedAgain = ConversionHookRunner.run(dataSource, manifest, historyWriter);
        assertTrue(history.isEmpty(), "a fully-converged conversion must write zero history on rerun: " + history);
        assertEquals(false, appliedAgain);
    }

    // ---- helpers (mirrors ConversionHookRunnerH2Test's own) ----

    private void exec(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String singleStringQuery(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifestFor(String table, Map<String, String> columnTypes,
            List<String> liveColumns, List<String> desiredColumns) {
        List<String> required = new ArrayList<>(desiredColumns);
        required.removeAll(liveColumns);
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:s7-test",
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
