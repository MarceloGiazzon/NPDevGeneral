package com.npdev.kernel.ports;

import com.npdev.kernel.exec.ExecutionSummary;

import java.util.List;

public interface ExecutionSummaryStore {
    List<ExecutionSummary> listSummaries(String tenantId, String mode, int limit, int offset);

    default List<ExecutionSummary> listByCorrelation(
            String tenantId,
            String correlationId,
            int limit,
            int offset
    ) {
        return List.of();
    }

    default List<ExecutionSummary> listFailureSummaries(
            String tenantId,
            int limit,
            int offset
    ) {
        return List.of();
    }

    default List<ExecutionSummary> listStuckSummaries(
            String tenantId,
            int limit,
            int offset
    ) {
        return List.of();
    }

    static ExecutionSummaryStore noop() {
        return (tenantId, mode, limit, offset) -> List.of();
    }
}
