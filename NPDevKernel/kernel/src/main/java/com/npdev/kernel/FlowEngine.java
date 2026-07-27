package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;

import java.util.List;
import java.util.Map;

/**
 * Port for NPDev's durable flow engine.
 *
 * <p><b>Implementation:</b> {@code KernelRunner} (this interface is the port; the engine is not a
 * separate class today -- see {@code docs/EXECUTION_TREES.md} item 2.B.5 for the planned split into
 * per-step-kind classes).
 *
 * <p><b>Durability contract.</b> A flow that reaches an {@code AWAIT_EVENT} step is persisted as
 * {@code WAITING_EVENT} ({@link com.npdev.kernel.execution.FlowInstanceStatus}) via
 * {@code FlowInstanceStore} ({@code JdbcFlowInstanceStore} / {@code InProcFlowInstanceStore}) and
 * survives JVM restart; {@code ResumeBootstrapRunner} rehydrates waiters on boot. Correlation
 * ownership -- which waiting instance claims an arriving event -- is held by
 * {@code CorrelationOwnershipStore}. A failure after partial progress is compensated rather than
 * re-run forward (LNCH-17), and a {@code forEach} loop resumes at the first not-yet-completed
 * iteration (LIFT-LOOP-P2).
 *
 * <p><b>Full documentation:</b> {@code docs/FLOWS.md} -- the 9 step kinds, the 6-status state
 * machine, event correlation, compensation, scheduling, hooks, and the documented limits.
 */
public interface FlowEngine {
    ExecutionResult startFlow(String flowName, Map<String, Object> input, ExecutionContext executionContext);

    ResumeOutcome resumeFlow(String correlationId, EventEnvelope eventEnvelope);

    record ResumeOutcome(
            int matchedWaiters,
            int resumedWaiters,
            List<String> resumedExecutionIds
    ) {
        public ResumeOutcome {
            resumedExecutionIds = resumedExecutionIds == null ? List.of() : List.copyOf(resumedExecutionIds);
        }

        public static ResumeOutcome noMatch() {
            return new ResumeOutcome(0, 0, List.of());
        }
    }
}
