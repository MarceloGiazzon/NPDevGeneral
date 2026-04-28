package com.npdev.kernel.ports;

import com.npdev.kernel.trace.FlowTrace;

import java.util.List;
import java.util.Optional;

public interface TraceStore {
    void save(FlowTrace trace);

    Optional<FlowTrace> findByExecutionId(String executionId);

    default List<FlowTrace> findByCorrelationId(String correlationId, int limit, int offset) {
        return search(new TraceQuery(correlationId, null, null, null, null, limit, offset));
    }

    default List<FlowTrace> findByFlowName(String flowName, int limit, int offset) {
        return search(new TraceQuery(null, flowName, null, null, null, limit, offset));
    }

    List<FlowTrace> search(TraceQuery query);

    static TraceStore noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final TraceStore INSTANCE = new TraceStore() {
            @Override
            public void save(FlowTrace trace) {
            }

            @Override
            public Optional<FlowTrace> findByExecutionId(String executionId) {
                return Optional.empty();
            }

            @Override
            public List<FlowTrace> search(TraceQuery query) {
                return List.of();
            }
        };

        private NoopHolder() {
        }
    }
}
