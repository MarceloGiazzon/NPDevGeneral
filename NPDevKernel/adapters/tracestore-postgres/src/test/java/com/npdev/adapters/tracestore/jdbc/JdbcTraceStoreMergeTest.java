package com.npdev.adapters.tracestore.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ports.TraceQuery;
import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import com.npdev.kernel.trace.TraceSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * FLOW-1: {@link JdbcTraceStore#save} merges a resumed or retried segment into the stored row instead
 * of overwriting it, and the denormalised {@code started_at_ms}/{@code outcome} columns follow the
 * MERGED trace (they are what {@code search}/{@code searchSummaries} filter on). Runs on H2 locally;
 * the real-Postgres twin of this table is exercised by CI.
 */
class JdbcTraceStoreMergeTest {

    private static final String SCHEMA = """
            CREATE TABLE npdev_trace (
                execution_id VARCHAR(191) PRIMARY KEY,
                correlation_id TEXT NOT NULL,
                flow_name TEXT NOT NULL,
                tenant_id TEXT,
                actor_id TEXT,
                outcome TEXT NOT NULL,
                started_at_ms BIGINT NOT NULL,
                ended_at_ms BIGINT NOT NULL,
                trace_json TEXT NOT NULL
            )
            """;

    private DataSource dataSource;
    private JdbcTraceStore store;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(SCHEMA);
        }
        store = new JdbcTraceStore(dataSource, new ObjectMapper().findAndRegisterModules(), H2Dialect.INSTANCE);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    private static StepTrace step(int index, String name, long start, long end, StepOutcome outcome, Map<String, Object> info) {
        return new StepTrace(index, name, "CAPABILITY_CALL", start, end, outcome, info, List.of(), null);
    }

    private static FlowTrace segment(String executionId, long start, long end, StepOutcome outcome,
                                     Map<String, Object> tags, StepTrace... steps) {
        return new FlowTrace(new FlowTraceMeta(executionId, "corr-" + executionId, "ApproveInvoice", "tenant-a", "actor", tags),
                start, end, outcome, List.of(steps));
    }

    @Test
    void parkedThenResumedSegmentsEndUpAsOneMergedRow() {
        store.save(segment("exec-1", 1000, 1300, StepOutcome.FAILED, Map.of("startStepIndex", 0),
                step(0, "prepare", 1000, 1100, StepOutcome.OK, Map.of()),
                step(1, "wait-approval", 1200, 1300, StepOutcome.FAILED, Map.of("awaitedEventStatus", "WAITING"))));
        assertEquals("WAITING", store.searchSummaries(TraceQuery.empty()).get(0).outcome());

        store.save(segment("exec-1", 9000, 9100, StepOutcome.OK, Map.of("startStepIndex", 1, "resumed", true),
                step(1, "wait-approval", 9000, 9010, StepOutcome.OK, Map.of()),
                step(2, "ship", 9010, 9100, StepOutcome.OK, Map.of())));

        FlowTrace merged = store.findByExecutionId("exec-1").orElseThrow();
        assertEquals(List.of("prepare", "wait-approval", "ship"), merged.steps().stream().map(StepTrace::stepName).toList());
        assertEquals(1000L, merged.startedAtEpochMs());
        assertEquals(9100L, merged.endedAtEpochMs());
        assertEquals(7700L, ((Number) merged.steps().get(1).info().get("waitedMs")).longValue(),
                "the wait survives the JSON round trip through the database");
        assertEquals(2L, ((Number) merged.meta().tags().get("segments")).longValue());

        List<TraceSummary> summaries = store.searchSummaries(TraceQuery.empty());
        assertEquals(1, summaries.size(), "still one row for the execution");
        assertEquals("OK", summaries.get(0).outcome());
        assertEquals(1000L, summaries.get(0).startedAtMs(), "the denormalised column follows the merged start");
        assertEquals(1, store.search(new TraceQuery(null, null, "OK", 900L, 1100L, 10, 0)).size());
        assertEquals(0, store.search(new TraceQuery(null, null, "WAITING", null, null, 10, 0)).size());
    }

    @Test
    void operatorRetryReplacesTheFailedStepInTheStoredRow() {
        store.save(segment("exec-2", 1000, 1200, StepOutcome.FAILED, Map.of("startStepIndex", 0),
                step(0, "charge", 1000, 1100, StepOutcome.OK, Map.of()),
                step(1, "ship", 1100, 1200, StepOutcome.FAILED, Map.of())));
        store.save(segment("exec-2", 4000, 4100, StepOutcome.OK, Map.of("startStepIndex", 1, "resumed", true),
                step(1, "ship", 4000, 4100, StepOutcome.OK, Map.of())));

        FlowTrace merged = store.findByExecutionId("exec-2").orElseThrow();
        assertEquals(2, merged.steps().size());
        assertEquals(StepOutcome.OK, merged.steps().get(1).outcome());
        assertEquals(2L, ((Number) merged.steps().get(1).info().get("stepAttempts")).longValue());
        assertFalse(merged.steps().get(1).info().containsKey("waitedMs"));
        assertEquals("OK", store.searchSummaries(TraceQuery.empty()).get(0).outcome());
    }

    @Test
    void anUnreadableStoredTraceDoesNotBlockTheSaveAndTheNewSegmentIsStoredAlone() throws SQLException {
        store.save(segment("exec-4", 1000, 1100, StepOutcome.OK, Map.of("startStepIndex", 0),
                step(0, "prepare", 1000, 1100, StepOutcome.OK, Map.of())));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE npdev_trace SET trace_json = 'not json {' WHERE execution_id = 'exec-4'");
        }

        store.save(segment("exec-4", 9000, 9100, StepOutcome.OK, Map.of("startStepIndex", 1, "resumed", true),
                step(1, "ship", 9000, 9100, StepOutcome.OK, Map.of())));

        FlowTrace stored = store.findByExecutionId("exec-4").orElseThrow();
        assertEquals(List.of("ship"), stored.steps().stream().map(StepTrace::stepName).toList(),
                "tracing is observability: the save must not fail, the incoming segment is stored alone");
        assertEquals(1, store.searchSummaries(TraceQuery.empty()).size());
    }

    @Test
    void aFreshRunUnderTheSameExecutionIdStillOverwrites() {
        store.save(segment("exec-3", 1000, 1100, StepOutcome.OK, Map.of("startStepIndex", 0),
                step(0, "a", 1000, 1100, StepOutcome.OK, Map.of())));
        store.save(segment("exec-3", 5000, 5100, StepOutcome.FAILED, Map.of("startStepIndex", 0),
                step(0, "b", 5000, 5100, StepOutcome.FAILED, Map.of())));
        FlowTrace stored = store.findByExecutionId("exec-3").orElseThrow();
        assertEquals(List.of("b"), stored.steps().stream().map(StepTrace::stepName).toList());
        assertEquals("FAILED", store.searchSummaries(TraceQuery.empty()).get(0).outcome());
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
