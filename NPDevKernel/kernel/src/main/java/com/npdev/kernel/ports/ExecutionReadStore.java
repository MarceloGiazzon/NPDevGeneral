package com.npdev.kernel.ports;

import com.npdev.kernel.execution.FlowInstance;

import java.util.List;
import java.util.Optional;

public interface ExecutionReadStore {
    Optional<FlowInstance> findByExecutionId(String executionId);

    default List<FlowInstance> findRecent(String tenantId, int limit, int offset) {
        return List.of();
    }

    default List<FlowInstance> findWaiting(String tenantId, int limit, int offset) {
        return List.of();
    }

    default List<FlowInstance> findByCorrelationId(String tenantId, String correlationId) {
        return List.of();
    }
}
