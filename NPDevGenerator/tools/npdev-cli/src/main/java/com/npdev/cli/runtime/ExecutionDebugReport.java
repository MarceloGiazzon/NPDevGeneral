package com.npdev.cli.runtime;

import com.npdev.adapters.tracing.inproc.InProcExecutionTracer;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.exec.ExecutionSummary;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.trace.FlowTrace;

import java.util.List;

public record ExecutionDebugReport(
        String executionId,
        FlowInstance execution,
        FlowTrace trace,
        List<InProcExecutionTracer.TraceSignal> signals,
        List<EventEnvelope> correlationEvents,
        List<ExecutionSummary> relatedExecutions,
        String diagnosis
) {
    public ExecutionDebugReport {
        signals = signals == null ? List.of() : List.copyOf(signals);
        correlationEvents = correlationEvents == null ? List.of() : List.copyOf(correlationEvents);
        relatedExecutions = relatedExecutions == null ? List.of() : List.copyOf(relatedExecutions);
        diagnosis = diagnosis == null ? "" : diagnosis;
    }
}
