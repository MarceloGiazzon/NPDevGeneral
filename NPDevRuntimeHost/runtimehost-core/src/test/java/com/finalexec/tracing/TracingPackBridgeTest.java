package com.finalexec.tracing;

import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * REG-243 (the "RuntimeHost generated-app Postgres integration tests" CI failure): on Postgres, a
 * single failed statement aborts the WHOLE transaction, so a missing {@code identity_v1_users}
 * used to poison the caller's shared connection even though the Java exception was caught locally,
 * silently rolling back the caller's real business write. The fix checks table existence up front
 * (via a genuinely separate connection) so a doomed statement never reaches the shared connection.
 * These tests run entirely on H2, which doesn't reproduce Postgres's abort semantics -- they don't
 * need to, since the fix's whole point is to never execute the failing statement at all.
 */
class TracingPackBridgeTest {

    @Test
    void onFlowEndIsANoOpWhenTraceEntriesTableIsAbsent() throws SQLException {
        DataSource dataSource = freshH2DataSource("no-trace-entries");
        TracingPackBridge bridge = new TracingPackBridge(dataSource);

        assertDoesNotThrow(() -> bridge.onFlowEnd(flowTrace("actor-1")));
    }

    @Test
    void onFlowEndWritesNullActorNameWhenIdentityUsersTableIsAbsent() throws SQLException {
        DataSource dataSource = freshH2DataSource("no-identity-users");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(TRACE_ENTRIES_DDL);
        }
        TracingPackBridge bridge = new TracingPackBridge(dataSource);

        assertDoesNotThrow(() -> bridge.onFlowEnd(flowTrace("actor-1")));

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            var rs = statement.executeQuery("SELECT actor_id, actor_name FROM trace_entries");
            assertEquals(true, rs.next());
            assertEquals("actor-1", rs.getString("actor_id"));
            assertNull(rs.getString("actor_name"));
        }
    }

    @Test
    void onFlowEndResolvesActorNameWhenIdentityUsersTableHasAMatchingRow() throws SQLException {
        DataSource dataSource = freshH2DataSource("with-identity-users");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(TRACE_ENTRIES_DDL);
            statement.execute("CREATE TABLE identity_v1_users (id VARCHAR(64) PRIMARY KEY, display_name VARCHAR(255))");
            statement.execute("INSERT INTO identity_v1_users (id, display_name) VALUES ('actor-1', 'Ada')");
        }
        TracingPackBridge bridge = new TracingPackBridge(dataSource);

        bridge.onFlowEnd(flowTrace("actor-1"));

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            var rs = statement.executeQuery("SELECT actor_name FROM trace_entries");
            assertEquals(true, rs.next());
            assertEquals("Ada", rs.getString("actor_name"));
        }
    }

    private static final String TRACE_ENTRIES_DDL =
            "CREATE TABLE trace_entries (id VARCHAR(64) PRIMARY KEY, execution_id VARCHAR(64),"
                    + " correlation_id VARCHAR(64), flow_name VARCHAR(255), tenant_id VARCHAR(120),"
                    + " actor_id VARCHAR(64), actor_name VARCHAR(255), outcome VARCHAR(16),"
                    + " started_at TIMESTAMP, ended_at TIMESTAMP, summary VARCHAR(1000))";

    private static FlowTrace flowTrace(String actorId) {
        FlowTraceMeta meta = new FlowTraceMeta(
                "exec-1", "corr-1", "CreateProbeRecord", "tenant-1", actorId, Map.of());
        long now = System.currentTimeMillis();
        return new FlowTrace(meta, now, now + 5, StepOutcome.OK, List.of());
    }

    private static DataSource freshH2DataSource(String name) {
        String url = "jdbc:h2:mem:tracing-bridge-" + name + "-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        return new SingleConnectionUrlDataSource(url);
    }

    /** A fresh physical connection per {@code getConnection()} call, kept alive across them by the
     *  H2 URL's own {@code DB_CLOSE_DELAY=-1} rather than by pinning one JDBC {@link Connection} --
     *  same minimal pattern {@code JdbcBusinessConceptStorePredicateV2Test} uses. */
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
