package com.npdev.adapters.tracestore.postgres;

import com.npdev.kernel.ports.TraceQuery;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import com.npdev.kernel.trace.TraceSummary;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresTraceStoreTest {
    private static final String[] SCHEMA_SQL = new String[]{
            """
            CREATE TABLE IF NOT EXISTS npdev_trace (
                execution_id TEXT PRIMARY KEY,
                correlation_id TEXT NOT NULL,
                flow_name TEXT NOT NULL,
                tenant_id TEXT,
                actor_id TEXT,
                outcome TEXT NOT NULL,
                started_at_ms BIGINT NOT NULL,
                ended_at_ms BIGINT NOT NULL,
                trace_json TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_npdev_trace_corr ON npdev_trace(correlation_id)",
            "CREATE INDEX IF NOT EXISTS idx_npdev_trace_flow ON npdev_trace(flow_name)",
            "CREATE INDEX IF NOT EXISTS idx_npdev_trace_outcome ON npdev_trace(outcome)",
            "CREATE INDEX IF NOT EXISTS idx_npdev_trace_started ON npdev_trace(started_at_ms)"
    };

    private PostgresTraceStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:tracestore;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("sa");
        executeSchema(dataSource, SCHEMA_SQL);
        store = new PostgresTraceStore(dataSource);
    }

    @Test
    void saveAndFindByExecutionId() {
        FlowTrace trace = trace("exec-1", "corr-1", "CreateUser", "tenant-1", "actor-1", StepOutcome.OK, 1000L, Map.of());
        store.save(trace);

        FlowTrace loaded = store.findByExecutionId("exec-1").orElseThrow();
        assertEquals("exec-1", loaded.meta().executionId());
        assertEquals("corr-1", loaded.meta().correlationId());
        assertEquals("CreateUser", loaded.meta().flowName());
        assertEquals("tenant-1", loaded.meta().tenantId());
        assertEquals("actor-1", loaded.meta().actorId());
        assertEquals(StepOutcome.OK, loaded.outcome());
    }

    @Test
    void findByCorrelationAndSearchAreDeterministic() {
        store.save(trace("exec-a", "corr-2", "FlowA", "tenant-a", "actor-a", StepOutcome.OK, 1000L, Map.of()));
        store.save(trace("exec-b", "corr-2", "FlowA", "tenant-a", "actor-b", StepOutcome.FAILED, 1500L, Map.of()));
        store.save(trace("exec-c", "corr-2", "FlowB", "tenant-a", "actor-c", StepOutcome.FAILED, 2000L, Map.of("awaitedEventStatus", "WAITING")));
        store.save(trace("exec-d", "corr-3", "FlowA", "tenant-b", "actor-d", StepOutcome.OK, 2500L, Map.of()));

        List<FlowTrace> byCorrelation = store.findByCorrelationId("corr-2", 10, 0);
        assertEquals(List.of("exec-c", "exec-b", "exec-a"),
                byCorrelation.stream().map(t -> t.meta().executionId()).toList());

        List<FlowTrace> waiting = store.search(new TraceQuery("corr-2", null, "WAITING", null, null, 10, 0));
        assertEquals(1, waiting.size());
        assertEquals("exec-c", waiting.get(0).meta().executionId());

        List<FlowTrace> tenantScoped = store.search(new TraceQuery(
                null,
                null,
                null,
                null,
                null,
                10,
                0,
                "tenant-a",
                null
        ));
        assertEquals(List.of("exec-c", "exec-b", "exec-a"),
                tenantScoped.stream().map(t -> t.meta().executionId()).toList());

        List<FlowTrace> flowAInRange = store.search(new TraceQuery(
                null,
                "FlowA",
                null,
                900L,
                2000L,
                10,
                0
        ));
        assertEquals(List.of("exec-b", "exec-a"),
                flowAInRange.stream().map(t -> t.meta().executionId()).toList());
        assertTrue(flowAInRange.stream().allMatch(t -> "FlowA".equals(t.meta().flowName())));
    }

    @Test
    void searchSummariesReturnsOnlySummaryRows() {
        store.save(trace("exec-a", "corr-2", "FlowA", "tenant-a", "actor-a", StepOutcome.OK, 1000L, Map.of("blob", "x".repeat(20000))));
        store.save(trace("exec-b", "corr-2", "FlowA", "tenant-a", "actor-a", StepOutcome.FAILED, 2000L, Map.of("blob", "y".repeat(20000))));
        store.save(trace("exec-c", "corr-3", "FlowB", "tenant-b", "actor-b", StepOutcome.OK, 3000L, Map.of("blob", "z".repeat(20000))));

        List<TraceSummary> summaries = store.searchSummaries(new TraceQuery(
                "corr-2",
                "FlowA",
                null,
                null,
                null,
                10,
                0,
                "tenant-a",
                null
        ));

        assertEquals(List.of("exec-b", "exec-a"), summaries.stream().map(TraceSummary::executionId).toList());
        assertTrue(summaries.stream().allMatch(summary -> "tenant-a".equals(summary.tenantId())));
        assertTrue(summaries.stream().allMatch(summary -> "corr-2".equals(summary.correlationId())));
    }

    private static FlowTrace trace(
            String executionId,
            String correlationId,
            String flowName,
            String tenantId,
            String actorId,
            StepOutcome outcome,
            long startedAt,
            Map<String, Object> stepInfo
    ) {
        StepTrace step = new StepTrace(
                0,
                "step-0",
                "RETURN",
                startedAt,
                startedAt + 10,
                outcome,
                stepInfo,
                List.of(),
                null
        );
        return new FlowTrace(
                new FlowTraceMeta(executionId, correlationId, flowName, tenantId, actorId, Map.of()),
                startedAt,
                startedAt + 20,
                outcome,
                List.of(step)
        );
    }

    private static void executeSchema(DataSource dataSource, String[] statements) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            for (String raw : statements) {
                String sql = raw.trim();
                if (sql.isEmpty()) {
                    continue;
                }
                statement.execute(sql);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed preparing trace store schema", exception);
        }
    }
}
