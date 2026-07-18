package com.finalexec.db;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 5 (5.4). Crash-mid-backfill coverage, same fault-injection {@link Proxy} technique
 * as {@code SchemaLifecycleExecutorDestructiveCrashRecoveryTest} (LNCH-1 Phase 4): intercepts
 * {@link PreparedStatement#executeUpdate()} so that AFTER the first of the backfill sequence's
 * statements succeeds, the NEXT one throws -- simulating an abrupt crash between
 * {@code ADD COLUMN} and the backfill {@code UPDATE}.
 *
 * <p>This test caught a real bug during development: {@code applyRequiredFieldBackfills}'s
 * idempotency gate originally skipped a required column the instant it existed live, regardless of
 * whether it was already {@code NOT NULL} -- which meant a crash between {@code ADD COLUMN} and the
 * backfill {@code UPDATE}/{@code SET NOT NULL} would converge on the WRONG state on the next boot
 * (silently treating the half-applied, still-nullable, not-yet-backfilled column as "done"). Fixed
 * by gating on "present AND already NOT NULL", not just "present" -- this test pins that fix.
 */
class SchemaLifecycleExecutorRequiredFieldBackfillCrashRecoveryTest {

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
    void crashBetweenAddColumnAndBackfillLeavesAHalfAppliedColumn_thenAFreshBootConverges() throws SQLException {
        try (Connection connection = realDataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'alpha', 1)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (2, 'beta', 1)");
        }
        seedStoredFingerprint(realDataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest();

        // Fail on the SECOND matching DDL/DML call (0-based index 1): the ADD COLUMN succeeds, the
        // backfill UPDATE throws (simulating a crash before the row values are set / NOT NULL applied).
        FaultInjectingDataSource faultyDataSource = new FaultInjectingDataSource(realDataSource, 1);
        SchemaLifecycleExecutor firstAttemptExecutor = new SchemaLifecycleExecutor();

        assertThrows(IllegalStateException.class, () -> firstAttemptExecutor.beforeMigrate(faultyDataSource, manifest));

        // Half-applied state: the column exists (ADD COLUMN committed) but is still nullable and
        // still NULL for both existing rows (the backfill UPDATE never ran).
        try (Connection connection = realDataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "status"), "the ADD COLUMN half must have committed before the fault");
            assertTrue(!isColumnNotNull(metadata, "widgets", "status"), "the column must still be nullable -- SET NOT NULL never ran");
            assertEquals(null, readStatus(connection, 1), "the backfill UPDATE never ran -- row 1's status must still be NULL");
            assertEquals(null, readStatus(connection, 2), "the backfill UPDATE never ran -- row 2's status must still be NULL");
        }

        // --- Fresh boot: a NEW executor instance, the REAL (non-faulty) DataSource, driven against
        // the half-applied database left behind above. ---
        SchemaLifecycleExecutor freshExecutor = new SchemaLifecycleExecutor();
        SchemaLifecycleExecutor.DestructiveRecreation result = freshExecutor.beforeMigrate(realDataSource, manifest);
        assertTrue(result.safeAdditive(), "the fresh boot must converge via the safe-additive path, not error or go destructive");

        try (Connection connection = realDataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(isColumnNotNull(metadata, "widgets", "status"),
                    "the fresh boot must finish the interrupted backfill and enforce NOT NULL");
            assertEquals("PENDING", readStatus(connection, 1), "the fresh boot must backfill the still-NULL row");
            assertEquals("PENDING", readStatus(connection, 2), "the fresh boot must backfill the still-NULL row");
        }

        // A THIRD boot against the now-fully-converged column must be a clean idempotent no-op too
        // (proves addBackfillAndTightenColumn's own idempotency, not just applyRequiredFieldBackfills'
        // outer gate).
        SchemaLifecycleExecutor thirdExecutor = new SchemaLifecycleExecutor();
        SchemaLifecycleExecutor.DestructiveRecreation thirdResult = thirdExecutor.beforeMigrate(realDataSource, manifest);
        assertTrue(thirdResult.safeAdditive());
    }

    private static String readStatus(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT status FROM widgets WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
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

    private static boolean isColumnNotNull(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return "NO".equalsIgnoreCase(resultSet.getString("IS_NULLABLE"));
                    }
                }
            }
        }
        return false;
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest() {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:new", List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id", "name", "status", "version")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)", "version", "BIGINT")),
                Map.of(), Map.of(),
                true, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED", "",
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("status", "\"PENDING\"")),
                Map.of(),
                Map.of());
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
     * {@link Proxy} that, in turn, hands out {@link PreparedStatement} proxies for any SQL beginning
     * with {@code ALTER TABLE} or {@code UPDATE} (the required-field-backfill sequence's DDL/DML
     * shapes -- see {@code SchemaLifecycleExecutor#addBackfillAndTightenColumn}). Every OTHER
     * prepared statement passes through untouched. Adapted verbatim from
     * {@code SchemaLifecycleExecutorDestructiveCrashRecoveryTest}'s {@code FaultInjectingDataSource}
     * (LNCH-1 Phase 4), matching a different SQL-prefix pattern for this phase's DDL/DML shapes.
     */
    private static final class FaultInjectingDataSource implements DataSource {
        private static final Pattern DDL_PREFIX = Pattern.compile("^(ALTER TABLE|UPDATE)", Pattern.CASE_INSENSITIVE);

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
                                + index + " successful backfill statement(s)");
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
