package com.finalexec.tracing;

import com.npdev.kernel.trace.FlowTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

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
 * <p>{@code onFlowEnd} runs synchronously inside the caller's own business transaction (it is
 * invoked from {@code KernelRunner} mid-flow, still under the create/update service's
 * {@code @Transactional}). A missing {@code identity_v1_users} table is caught locally as a Java
 * exception, but on Postgres a single failed statement marks the WHOLE transaction aborted at the
 * protocol level -- every later statement on that same connection fails too, regardless of the
 * Java-level catch, which silently rolled back the caller's real business write. Running this
 * bridge's own work in its own {@code PROPAGATION_REQUIRES_NEW} transaction (a separate pooled
 * connection) contains any such failure to this best-effort side write, exactly as the "not an
 * error" comment below always intended.</p>
 */
public class TracingPackBridge {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate isolatedTransaction;

    public TracingPackBridge(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.isolatedTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.isolatedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void onFlowEnd(FlowTrace flowTrace) {
        try {
            isolatedTransaction.executeWithoutResult(status -> writeTraceEntry(flowTrace));
        } catch (Exception ignored) {
            // Best-effort: trace_entries is a business-level convenience view.
            // A missing table (pre-migration or tracing pack not composed) is not an error.
        }
    }

    private void writeTraceEntry(FlowTrace flowTrace) {
        com.npdev.kernel.trace.FlowTraceMeta meta = flowTrace.meta();
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
    }

    private String resolveActorName(String actorId) {
        if (actorId == null || actorId.isBlank()) {
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

    private static String buildSummary(FlowTrace flowTrace) {
        if (flowTrace.steps() == null || flowTrace.steps().isEmpty()) {
            return flowTrace.meta().flowName();
        }
        int stepCount = flowTrace.steps().size();
        long durationMs = flowTrace.endedAtEpochMs() - flowTrace.startedAtEpochMs();
        return flowTrace.meta().flowName() + " (" + stepCount + " step(s), " + durationMs + "ms)";
    }
}