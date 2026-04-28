package com.npdev.kernel.ports;

import com.npdev.kernel.execution.FlowInstance;

import java.util.List;
import java.util.Optional;

public interface FlowInstanceStore extends ExecutionReadStore {
    void save(FlowInstance instance);

    void update(FlowInstance instance);

    Optional<FlowInstance> findByExecutionId(String executionId);

    List<FlowInstance> findWaitingByCorrelation(String correlationId);

    List<FlowInstance> findWaitingByEvent(String eventName);

    List<FlowInstance> findAllWaiting(int limit);

    List<FlowInstance> findWaitingEligibleToResume(String tenantId, long nowEpochMs, int limit);

    List<FlowInstance> findStaleWaiting(String tenantId, long olderThanEpochMs, int limit, int offset);

    static FlowInstanceStore noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final FlowInstanceStore INSTANCE = new FlowInstanceStore() {
            @Override
            public void save(FlowInstance instance) {
            }

            @Override
            public void update(FlowInstance instance) {
            }

            @Override
            public Optional<FlowInstance> findByExecutionId(String executionId) {
                return Optional.empty();
            }

            @Override
            public List<FlowInstance> findWaitingByCorrelation(String correlationId) {
                return List.of();
            }

            @Override
            public List<FlowInstance> findWaitingByEvent(String eventName) {
                return List.of();
            }

            @Override
            public List<FlowInstance> findAllWaiting(int limit) {
                return List.of();
            }

            @Override
            public List<FlowInstance> findWaitingEligibleToResume(String tenantId, long nowEpochMs, int limit) {
                return List.of();
            }

            @Override
            public List<FlowInstance> findStaleWaiting(String tenantId, long olderThanEpochMs, int limit, int offset) {
                return List.of();
            }
        };

        private NoopHolder() {
        }
    }
}