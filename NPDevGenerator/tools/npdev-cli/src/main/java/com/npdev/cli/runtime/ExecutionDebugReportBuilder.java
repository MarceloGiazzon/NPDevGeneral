package com.npdev.cli.runtime;

import com.npdev.adapters.tracing.inproc.InProcExecutionTracer;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.exec.ExecutionSummary;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.kernel.trace.FlowTrace;

import java.util.List;

public final class ExecutionDebugReportBuilder {

    public ExecutionDebugReport build(CliRuntime runtime, String executionId, int limit) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must be non-null");
        }
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must be non-blank");
        }
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 500);

        FlowInstance execution = runtime.flowInstanceStore().findByExecutionId(executionId).orElse(null);
        FlowTrace trace = runtime.traceStore().findByExecutionId(executionId).orElse(null);
        List<InProcExecutionTracer.TraceSignal> signals = runtime.traceStore().getSignals(executionId);

        String correlationId = execution != null ? execution.correlationId() : trace != null ? trace.meta().correlationId() : null;
        String tenantId = execution != null ? execution.tenantId() : trace != null ? trace.meta().tenantId() : "default";

        List<EventEnvelope> events = correlationId == null || correlationId.isBlank()
                ? List.of()
                : runtime.eventStore().findByCorrelationId(tenantId == null ? "default" : tenantId, correlationId, effectiveLimit, 0);

        List<ExecutionSummary> relatedExecutions = correlationId == null || correlationId.isBlank()
                ? List.of()
                : runtime.flowInstanceStore().listByCorrelation(tenantId == null ? "default" : tenantId, correlationId, effectiveLimit, 0);

        return new ExecutionDebugReport(
                executionId,
                execution,
                trace,
                signals,
                events,
                relatedExecutions,
                diagnose(execution, trace, events)
        );
    }

    private static String diagnose(FlowInstance execution, FlowTrace trace, List<EventEnvelope> events) {
        if (execution == null && trace == null) {
            return "execution_not_found";
        }
        if (execution != null && execution.status() == FlowInstanceStatus.WAITING_EVENT) {
            return "waiting_for_event:" + execution.waitingForEventName();
        }
        if (execution != null && execution.status() == FlowInstanceStatus.FAILED) {
            return "failed:" + nullable(execution.lastErrorCode());
        }
        if (execution != null && execution.status() == FlowInstanceStatus.STUCK) {
            return "stuck:" + nullable(execution.lastErrorCode());
        }
        if (trace != null && trace.steps().stream().anyMatch(step -> step.capabilityError() != null)) {
            return "capability_error_in_trace";
        }
        if (events != null && !events.isEmpty()) {
            return "events_present:" + events.size();
        }
        return "ok";
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
