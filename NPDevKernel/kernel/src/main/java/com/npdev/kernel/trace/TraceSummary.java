package com.npdev.kernel.trace;

public record TraceSummary(
        String executionId,
        String tenantId,
        String correlationId,
        String flowName,
        String outcome,
        long startedAtMs,
        long endedAtMs,
        long updatedAtMs
) {
}

