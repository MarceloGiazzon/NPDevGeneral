package com.finalexec.db;

import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 4 (task 4.5, item (f)). Crash-mid-destruction coverage for the surgical destructive
 * path: schema DDL here runs synchronously inside one boot call, so the freeze-thread technique
 * used by the kernel's async durability tests ({@code KernelRunnerCompensationTest}) does not apply
 * -- the natural analog is intercepting the JDBC {@link PreparedStatement#executeUpdate()} call
 * site so that AFTER the first of two surgical DDL statements succeeds, the NEXT one throws,
 * simulating an abrupt crash mid-sequence. {@link FaultInjectingDataSource} does this via
 * {@link Proxy} over {@link Connection}/{@link PreparedStatement} rather than hand-implementing
 * every JDBC interface method.
 */
class SchemaLifecycleExecutorDestructiveCrashRecoveryTest {

    private DataSource realDataSource;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        realDataSource = new SingleConnectionUrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = realDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void crashAfterTheFirstOfTwoSurgicalDropsLeavesPartialCrashHistoryAndAHalfAppliedDatabase_thenAFreshBootConverges()
            throws SQLException {
        try (Connection connection = realDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, name, legacy_flag) VALUES (1, 'Alpha', TRUE)");
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO gadgets (id) VALUES (1), (2)");
        }
        seedStoredFingerprint(realDataSource, "sha256:old");

        // "gadgets" is entirely undeclared (DROP_TABLE candidate); "widgets.legacy_flag" is an
        // extra column (DROP_COLUMN candidate) -- two independent surgical DDL statements, on
        // different tables, sorted deterministically by SchemaDeltaReport ("gadgets" < "widgets").
        SchemaLifecycleExecutor.SchemaManifest manifestNoToken = manifest("sha256:new", "");
        SchemaDeltaReport originalReport = SchemaDeltaReport.generate(realDataSource, manifestNoToken);
        assertEquals(2, originalReport.items().size(), "control: exactly two surgical items expected before any fault");
        String originalToken = DestructiveAckToken.compute("sha256:new", originalReport.stableStrings());
        SchemaLifecycleExecutor.SchemaManifest manifestWithOriginalToken = manifest("sha256:new", originalToken);

        // Fail on the SECOND DDL call (0-based index 1): the first (DROP TABLE gadgets) succeeds,
        // the second (ALTER TABLE widgets DROP COLUMN legacy_flag) throws.
        FaultInjectingDataSource faultyDataSource = new FaultInjectingDataSource(realDataSource, 1);
        SchemaLifecycleExecutor firstAttemptExecutor = new SchemaLifecycleExecutor();

        IllegalStateException crash = assertThrows(IllegalStateException.class,
                () -> firstAttemptExecutor.beforeMigrate(faultyDataSource, manifestWithOriginalToken));
        assertTrue(crash.getMessage().contains("1/2 item(s) applied"), "the exception must report partial progress: " + crash.getMessage());

        // Half-applied state: gadgets is GONE (first DDL succeeded), widgets.legacy_flag is STILL
        // there (second DDL never ran) -- and the surviving row's other data is untouched.
        try (Connection connection = realDataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(tableExists(metadata, "gadgets"), "the first DDL (DROP TABLE gadgets) must have actually committed before the fault");
            assertTrue(hasColumn(metadata, "widgets", "legacy_flag"), "the second DDL (drop legacy_flag) must NOT have run");
            try (PreparedStatement statement = connection.prepareStatement("SELECT name, legacy_flag FROM widgets WHERE id = 1");
                 ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals("Alpha", resultSet.getString("name"));
                assertTrue(resultSet.getBoolean("legacy_flag"));
            }
        }

        // The history row for this attempt must be left at PARTIAL-CRASH -- never updated to
        // APPLIED, since the fault prevented the code that does that from ever running.
        HistoryRow crashedRow = latestHistoryRow(realDataSource);
        assertEquals("PARTIAL-CRASH", crashedRow.outcome());
        assertTrue(crashedRow.itemsJson().contains("DROP_TABLE:gadgets:2"));
        assertTrue(crashedRow.itemsJson().contains("DROP_COLUMN:widgets:legacy_flag:BOOLEAN"));

        // --- Fresh boot: a NEW executor instance, a NEW (non-faulty) DataSource reference, no
        // fault injection this time, driven against the half-applied database left behind above. ---
        SchemaLifecycleExecutor freshExecutor = new SchemaLifecycleExecutor();
        SchemaDeltaReport residualReport = SchemaDeltaReport.generate(realDataSource, manifestNoToken);
        assertEquals(1, residualReport.items().size(),
                "the residual report must re-classify from the LIVE (half-applied) state, not repeat the original plan");
        assertEquals("DROP_COLUMN:widgets:legacy_flag:BOOLEAN", residualReport.stableStrings().get(0));
        assertFalse(residualReport.stableStrings().stream().anyMatch(item -> item.contains("gadgets")),
                "gadgets must NOT be re-itemized -- it is already gone (idempotent-by-check)");

        String residualToken = DestructiveAckToken.compute("sha256:new", residualReport.stableStrings());
        SchemaLifecycleExecutor.SchemaManifest manifestWithResidualToken = manifest("sha256:new", residualToken);

        SchemaLifecycleExecutor.DestructiveRecreation result = freshExecutor.beforeMigrate(realDataSource, manifestWithResidualToken);
        assertTrue(result.performed());
        assertEquals(List.of("widgets"), result.droppedTables(),
                "the fresh pass must touch ONLY the residual table -- it must not attempt to re-drop gadgets");

        try (Connection connection = realDataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "widgets", "legacy_flag"), "the residual drop must now have completed");
            try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM widgets WHERE id = 1");
                 ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals("Alpha", resultSet.getString("name"), "the surviving row's other data must still be intact");
            }
        }

        HistoryRow convergedRow = latestHistoryRow(realDataSource);
        assertEquals("APPLIED", convergedRow.outcome());
        assertTrue(convergedRow.itemsJson().contains("DROP_COLUMN:widgets:legacy_flag:BOOLEAN"));
        assertFalse(convergedRow.itemsJson().contains("gadgets"), "the converged history row must show only the residual trail, not a repeat of gadgets");
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

    private record HistoryRow(String outcome, String itemsJson) {
    }

    private static HistoryRow latestHistoryRow(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT outcome, items_json FROM npdev_schema_history ORDER BY applied_at_utc DESC")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "expected at least one npdev_schema_history row");
                return new HistoryRow(resultSet.getString(1), resultSet.getString(2));
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

    private static boolean tableExists(DatabaseMetaData metadata, String table) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getTables(null, null, candidate, new String[] {"TABLE"})) {
                if (resultSet.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(String toFingerprint, String ackToken) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, toFingerprint, List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(),
                false, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "", ackToken);
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

    /**
     * Wraps a real {@link DataSource}: every {@link Connection} it hands out is a dynamic
     * {@link Proxy} that, in turn, hands out {@link PreparedStatement} proxies for any SQL
     * beginning with {@code ALTER TABLE} or {@code DROP TABLE} (the surgical destructive path's
     * only DDL shapes -- see {@code SchemaLifecycleExecutor#executeDropColumn/executeDropTableCascade
     * /executeNarrowTypeDropAndRecreate}). Every OTHER prepared statement (the history-row INSERT/
     * UPDATE, the {@code CREATE TABLE IF NOT EXISTS} bootstrap, {@code SchemaDropSnapshotWriter}'s
     * SELECTs) passes through untouched, so fault injection is scoped precisely to the DDL sequence
     * under test, not to the bookkeeping around it. A single, DataSource-scoped counter (not
     * per-connection) tracks how many matching statements have executed so far, since the executor
     * opens several short-lived connections over the course of one {@code beforeMigrate} call.
     */
    private static final class FaultInjectingDataSource implements DataSource {
        private static final Pattern DDL_PREFIX = Pattern.compile("^(ALTER TABLE|DROP TABLE)", Pattern.CASE_INSENSITIVE);

        private final DataSource delegate;
        private final int failOnCallIndex;
        private final AtomicInteger matchingCallCount = new AtomicInteger(0);

        FaultInjectingDataSource(DataSource delegate, int failOnCallIndex) {
            this.delegate = delegate;
            this.failOnCallIndex = failOnCallIndex;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    new ConnectionHandler(real));
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
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

        private final class ConnectionHandler implements InvocationHandler {
            private final Connection real;

            private ConnectionHandler(Connection real) {
                this.real = real;
            }

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("prepareStatement".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof String sql) {
                    PreparedStatement realStatement = (PreparedStatement) invokeReal(method, args);
                    if (DDL_PREFIX.matcher(sql.trim()).find()) {
                        return Proxy.newProxyInstance(
                                PreparedStatement.class.getClassLoader(),
                                new Class<?>[] {PreparedStatement.class},
                                new StatementHandler(realStatement));
                    }
                    return realStatement;
                }
                return invokeReal(method, args);
            }

            private Object invokeReal(Method method, Object[] args) throws Throwable {
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            }
        }

        private final class StatementHandler implements InvocationHandler {
            private final PreparedStatement real;

            private StatementHandler(PreparedStatement real) {
                this.real = real;
            }

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("executeUpdate".equals(method.getName())) {
                    int index = matchingCallCount.getAndIncrement();
                    if (index == failOnCallIndex) {
                        throw new SQLException("Injected fault: simulated crash immediately after "
                                + index + " successful surgical DDL statement(s)");
                    }
                }
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            }
        }
    }
}
