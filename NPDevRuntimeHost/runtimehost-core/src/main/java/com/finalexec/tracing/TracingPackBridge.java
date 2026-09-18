package com.finalexec.tracing;

import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * S4 (Tracing pack): bridges kernel execution traces into the tracing pack's business table
 * ({@code trace_entries}) so the trace viewer panel can display them to non-technical users.
 *
 * <p>Registered as an {@link ExecutionTracer} bean alongside the existing kernel-level tracer
 * ({@code InProcExecutionTracer} or {@code PersistentExecutionTracer}) -- the kernel's own
 * {@code KernelRunner} calls every registered tracer transparently, so this bridge receives
 * the same callbacks without any kernel changes.</p>
 *
 * <p>Actor name resolution: looks up {@code display_name} from the identity pack's users table
 * ({@code identity_v1_users}) by {@code actor_id}. When the users table is absent (pre-bootstrap,
 * or an app without the identity pack), the actor name is left null and the viewer shows the
 * raw actor_id instead.</p>
 */
public class TracingPackBridge implements ExecutionTracer {

    private final JdbcTemplate jdbc;

    public TracingPackBridge(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void onFlowEnd(FlowTrace flowTrace) {
        FlowTraceMeta meta = flowTrace.meta();
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
                    flowTrace.outcome() == StepOutcome.OK ? "SUCCESS" : "FAILURE",
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