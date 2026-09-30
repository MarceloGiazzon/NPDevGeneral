package com.finalexec.tracing;

import com.npdev.kernel.trace.FlowTrace;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S4 (Tracing pack): bridges kernel execution traces into the tracing pack's business table
 * ({@code trace_entries}) so the trace viewer panel can display them to non-technical users.
 *
 * <p>Deliberately NOT an {@code ExecutionTracer} itself: registering a second bean of that type
 * would collide with the storage-mode tracer ({@code jdbcExecutionTracer}/
 * {@code inProcExecutionTracer}) at the single {@code ExecutionTracer} injection point in the
 * kernel binder. Instead {@code ChainedExecutionTracer} (same package) composes the primary tracer
 * with this bridge into exactly ONE bean; {@link #onFlowEnd} is the chain's extra call, invoked
 * after the primary tracer's own flow-end handling.</p>
 *
 * <p>Actor name resolution: looks up {@code display_name} from the identity pack's users table
 * ({@code identity_v1_users}) by {@code actor_id}. When the users table is absent (pre-bootstrap,
 * or an app without the identity pack), the actor name is left null and the viewer shows the
 * raw actor_id instead.</p>
 *
 * <p>{@code onFlowEnd} runs synchronously inside the caller's own business {@code @Transactional}
 * (invoked mid-flow from {@code KernelRunner}), sharing its JDBC connection. A missing table is
 * caught locally as a Java exception, but on Postgres a single failed statement aborts the WHOLE
 * transaction at the protocol level -- every later statement on that same connection fails too,
 * regardless of the Java-level catch, silently rolling back the caller's real business write. The
 * fix is to never let a doomed statement reach that shared connection in the first place: both
 * {@code trace_entries} and {@code identity_v1_users} existence are checked once (cached for the
 * process lifetime, via a genuinely separate {@link DataSource#getConnection()} call that never
 * participates in Spring's transaction synchronization) before either table is ever touched on the
 * caller's connection. H2/SQL Server don't enforce the same strict per-statement abort semantics,
 * which is why this was Postgres-only and invisible locally.</p>
 */
public class TracingPackBridge {

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final Map<String, Boolean> tableExistsCache = new ConcurrentHashMap<>();

    public TracingPackBridge(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.dataSource = dataSource;
    }

    public void onFlowEnd(FlowTrace flowTrace) {
        if (!tableExists("trace_entries")) {
            return;
        }
        com.npdev.kernel.trace.FlowTraceMeta meta = flowTrace.meta();
        try {
            String actorName = resolveActorName(meta.actorId());
            jdbc.update(
                    "INSERT INTO trace_entries (id, execution_id, correlation_id, flow_name,"
                            + " tenant_id, actor_id, actor_name, outcome, started_at, ended_at, summary)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    java.util.UUID.randomUUID().toString(),
                    meta.executionId(),
                    meta.correlationId(),
                    meta.flowName(),
                    meta.tenantId(),
                    meta.actorId(),
                    actorName,
                    flowTrace.outcome() == com.npdev.kernel.trace.StepOutcome.OK ? "SUCCESS" : "FAILURE",
                    Timestamp.from(Instant.ofEpochMilli(flowTrace.startedAtEpochMs())),
                    Timestamp.from(Instant.ofEpochMilli(flowTrace.endedAtEpochMs())),
                    buildSummary(flowTrace)
            );
        } catch (Exception ignored) {
            // Best-effort: trace_entries is a business-level convenience view.
            // A missing table (pre-migration or tracing pack not composed) is not an error.
        }
    }

    private String resolveActorName(String actorId) {
        if (actorId == null || actorId.isBlank() || !tableExists("identity_v1_users")) {
            return null;
        }
        try {
            return jdbc.queryForObject(
                    "SELECT display_name FROM identity_v1_users WHERE id = ?",
                    String.class,
                    actorId
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean tableExists(String table) {
        return tableExistsCache.computeIfAbsent(table, this::checkTableExists);
    }

    private boolean checkTableExists(String table) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            if (hasRow(metaData.getTables(null, null, table, null))) {
                return true;
            }
            return hasRow(metaData.getTables(null, null, table.toUpperCase(Locale.ROOT), null));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasRow(ResultSet resultSet) throws java.sql.SQLException {
        try (ResultSet toClose = resultSet) {
            return toClose.next();
        }
    }

    private static String buildSummary(FlowTrace flowTrace) {
        if (flowTrace.steps() == null || flowTrace.steps().isEmpty()) {
            return flowTrace.meta().flowName();
        }
        int stepCount = flowTrace.steps().size();
        long durationMs = flowTrace.endedAtEpochMs() - flowTrace.startedAtEpochMs();
        return flowTrace.meta().flowName() + " (" + stepCount + " step(s), " + durationMs + "ms)";
    }
}