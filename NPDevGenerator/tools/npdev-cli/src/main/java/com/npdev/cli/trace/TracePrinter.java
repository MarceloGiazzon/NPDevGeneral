package com.npdev.cli.trace;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.StepTrace;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class TracePrinter {
    public String printExecutionTrace(FlowTrace trace) {
        if (trace == null) {
            return "Trace not found.";
        }
        StringBuilder out = new StringBuilder();
        out.append("Execution ").append(trace.meta().executionId()).append(System.lineSeparator());
        out.append("Flow: ").append(trace.meta().flowName())
                .append(" | Correlation: ").append(trace.meta().correlationId())
                .append(" | Tenant: ").append(trace.meta().tenantId())
                .append(" | Actor: ").append(trace.meta().actorId())
                .append(System.lineSeparator());
        out.append("Outcome: ").append(trace.outcome())
                .append(" | Started: ").append(formatEpoch(trace.startedAtEpochMs()))
                .append(" | Ended: ").append(formatEpoch(trace.endedAtEpochMs()))
                .append(" | DurationMs: ").append(Math.max(0L, trace.endedAtEpochMs() - trace.startedAtEpochMs()))
                .append(System.lineSeparator());
        out.append("Steps:").append(System.lineSeparator());
        for (StepTrace step : trace.steps()) {
            appendStep(out, step);
        }
        return out.toString();
    }

    public String printCorrelationTimeline(
            String correlationId,
            List<EventEnvelope> events,
            List<?> executions,
            List<FlowTrace> traces
    ) {
        StringBuilder out = new StringBuilder();
        out.append("Correlation ").append(correlationId).append(System.lineSeparator());
        out.append("Events (").append(events == null ? 0 : events.size()).append("):").append(System.lineSeparator());
        if (events != null) {
            for (EventEnvelope event : events) {
                out.append(" - ").append(formatEpoch(event.timestampEpochMs()))
                        .append(" | ").append(event.eventName())
                        .append(" | eventId=").append(event.eventId())
                        .append(" | flow=").append(event.flowName())
                        .append(" | step=").append(event.stepIndex())
                        .append(System.lineSeparator());
            }
        }
        out.append("Executions: ").append(executions == null ? 0 : executions.size()).append(System.lineSeparator());
        out.append("Traces: ").append(traces == null ? 0 : traces.size()).append(System.lineSeparator());
        return out.toString();
    }

    private static void appendStep(StringBuilder out, StepTrace step) {
        long durationMs = Math.max(0L, step.endedAtEpochMs() - step.startedAtEpochMs());
        out.append(" - [").append(step.stepIndex()).append("] ")
                .append(step.stepName())
                .append(" (").append(step.stepType()).append(")")
                .append(" -> ").append(step.outcome())
                .append(" | durationMs=").append(durationMs)
                .append(System.lineSeparator());

        Map<String, Object> info = step.info();
        if (info != null && !info.isEmpty()) {
            out.append("     info=").append(info).append(System.lineSeparator());
        }
        if (step.capabilityError() != null) {
            out.append("     capabilityError=")
                    .append(step.capabilityError().kind()).append("/")
                    .append(step.capabilityError().code()).append(" - ")
                    .append(step.capabilityError().message())
                    .append(System.lineSeparator());
        }
        if (step.invariantViolations() != null && !step.invariantViolations().isEmpty()) {
            out.append("     invariantViolations=").append(step.invariantViolations()).append(System.lineSeparator());
        }
    }

    private static String formatEpoch(long epochMs) {
        return Instant.ofEpochMilli(epochMs).toString();
    }
}
