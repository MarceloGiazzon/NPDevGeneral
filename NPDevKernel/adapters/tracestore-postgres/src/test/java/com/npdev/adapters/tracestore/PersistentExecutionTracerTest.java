package com.npdev.adapters.tracestore;

import com.npdev.adapters.tracestore.jdbc.JdbcTraceStore;
import com.npdev.kernel.ports.TraceQuery;
import com.npdev.kernel.ports.TraceStore;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import com.npdev.test.postgres.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-127: this test previously asserted nothing ({@code assertTrue(true)}) while its comments
 * described a real save/query/performance anchor that was never wired up -- it counted toward the
 * module's test-file coverage without exercising {@link PersistentExecutionTracer} at all.
 * {@link PersistentExecutionTracer} has no other test coverage anywhere in the repo (only
 * {@link PostgresTraceStoreTest} covers the underlying store it bridges to), so this now exercises
 * the tracer itself against a real Postgres-backed {@link TraceStore}, plus the performance anchor
 * the original comments named: retrieving 1000 already-persisted traces.
 */
class PersistentExecutionTracerTest {
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

    private TraceStore store;
    private PersistentExecutionTracer tracer;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PostgresTestSupport.dataSource();
        PostgresTestSupport.execute(dataSource, SCHEMA_SQL);
        PostgresTestSupport.truncate(dataSource, "npdev_trace");
        store = new JdbcTraceStore(dataSource);
        tracer = new PersistentExecutionTracer(store);
    }

    @Test
    void onFlowEndPersistsTheTraceThroughTheProvidedStore() {
        FlowTrace trace = trace("exec-tracer-1", "corr-tracer-1", "CreateUser", "tenant-1", "actor-1",
                StepOutcome.OK, 1_000L);

        tracer.onFlowEnd(trace);

        FlowTrace loaded = store.findByExecutionId("exec-tracer-1").orElseThrow();
        assertEquals("exec-tracer-1", loaded.meta().executionId());
        assertEquals("corr-tracer-1", loaded.meta().correlationId());
        assertEquals(StepOutcome.OK, loaded.outcome());
    }

    @Test
    void onFlowEndIgnoresANullTraceInsteadOfThrowing() {
        tracer.onFlowEnd(null);

        List<FlowTrace> found = store.search(new TraceQuery(null, null, null, null, null, 10, 0));
        assertTrue(found.isEmpty());
    }

    @Test
    void retrieving1000PersistedTracesCompletesWithinPerformanceBudget() {
        String correlationId = "corr-tracer-perf";
        for (int i = 0; i < 1000; i++) {
            tracer.onFlowEnd(trace("exec-tracer-perf-" + i, correlationId, "PerfFlow", "tenant-perf",
                    "actor-perf", StepOutcome.OK, 1_000L + i));
        }

        long startedAtNanos = System.nanoTime();
        List<FlowTrace> retrieved = store.search(new TraceQuery(correlationId, null, null, null, null, 1000, 0));
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000;

        assertEquals(1000, retrieved.size());
        // A budget wide enough to absorb Testcontainers/CI jitter but tight enough that a real
        // regression (e.g. an accidental N+1 query per trace) still fails it -- 1000 individual
        // round trips would run for seconds, not milliseconds, on any real Postgres connection.
        assertTrue(elapsedMillis < 5_000,
                "expected retrieval of 1000 traces to complete in under 5000ms, took " + elapsedMillis + "ms");
    }

    private static FlowTrace trace(
            String executionId,
            String correlationId,
            String flowName,
            String tenantId,
            String actorId,
            StepOutcome outcome,
            long startedAt
    ) {
        StepTrace step = new StepTrace(
                0,
                "step-0",
                "RETURN",
                startedAt,
                startedAt + 10,
                outcome,
                Map.of(),
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
}
