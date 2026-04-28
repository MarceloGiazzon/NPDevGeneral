package com.npdev.cli.trace;

import com.npdev.cli.runtime.ExecutionDebugReport;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.exec.ExecutionSummary;

import java.time.Instant;

public final class DebugReportPrinter {

    public String print(ExecutionDebugReport report) {
        if (report == null) {
            return "Debug report not available.";
        }

        StringBuilder out = new StringBuilder();
        out.append("Execution Diagnosis").append(System.lineSeparator());
        out.append("ExecutionId: ").append(report.executionId()).append(System.lineSeparator());
        out.append("Diagnosis : ").append(report.diagnosis()).append(System.lineSeparator());

        if (report.execution() != null) {
            out.append("Status    : ").append(report.execution().status()).append(System.lineSeparator());
            out.append("Flow      : ").append(report.execution().flowName()).append(System.lineSeparator());
            out.append("Correlation: ").append(report.execution().correlationId()).append(System.lineSeparator());
            out.append("Tenant    : ").append(report.execution().tenantId()).append(System.lineSeparator());
            out.append("Actor     : ").append(report.execution().actorId()).append(System.lineSeparator());
            out.append("StepIndex : ").append(report.execution().currentStepIndex()).append(System.lineSeparator());
            if (report.execution().waitingForEventName() != null && !report.execution().waitingForEventName().isBlank()) {
                out.append("WaitingFor: ").append(report.execution().waitingForEventName()).append(System.lineSeparator());
            }
            if (report.execution().lastErrorCode() != null && !report.execution().lastErrorCode().isBlank()) {
                out.append("LastError : ")
                        .append(report.execution().lastErrorKind())
                        .append("/")
                        .append(report.execution().lastErrorCode())
                        .append(" - ")
                        .append(report.execution().lastErrorMessage())
                        .append(System.lineSeparator());
            }
        }

        out.append("Signals   : ").append(report.signals().size()).append(System.lineSeparator());
        out.append("Events    : ").append(report.correlationEvents().size()).append(System.lineSeparator());
        for (EventEnvelope event : report.correlationEvents()) {
            out.append("  - ")
                    .append(formatEpoch(event.timestampEpochMs()))
                    .append(" | ")
                    .append(event.eventName())
                    .append(" | eventId=")
                    .append(event.eventId())
                    .append(System.lineSeparator());
        }

        out.append("Related Executions: ").append(report.relatedExecutions().size()).append(System.lineSeparator());
        for (ExecutionSummary summary : report.relatedExecutions()) {
            out.append("  - ")
                    .append(summary.executionId())
                    .append(" | ")
                    .append(summary.flowName())
                    .append(" | ")
                    .append(summary.status())
                    .append(" | updated=")
                    .append(formatEpoch(summary.updatedAtMs()))
                    .append(System.lineSeparator());
        }
        return out.toString();
    }

    private static String formatEpoch(long epochMs) {
        return Instant.ofEpochMilli(epochMs).toString();
    }
}
