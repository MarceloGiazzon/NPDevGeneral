package com.npdev.kernel.ports;

import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.CircuitBreakerStateSummary;

import java.util.List;

public interface CircuitBreakerStateStore {
    CircuitBreakerState get(CapabilityOpKey key);

    void put(CapabilityOpKey key, CircuitBreakerState state);

    void reset(CapabilityOpKey key);

    default List<CircuitBreakerStateSummary> listStates(
            String tenantId,
            String capabilityName,
            String operationName,
            int limit,
            int offset
    ) {
        return List.of();
    }

    static CircuitBreakerStateStore noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final CircuitBreakerStateStore INSTANCE = new CircuitBreakerStateStore() {
            @Override
            public CircuitBreakerState get(CapabilityOpKey key) {
                return CircuitBreakerState.closed();
            }

            @Override
            public void put(CapabilityOpKey key, CircuitBreakerState state) {
            }

            @Override
            public void reset(CapabilityOpKey key) {
            }

            @Override
            public List<CircuitBreakerStateSummary> listStates(
                    String tenantId,
                    String capabilityName,
                    String operationName,
                    int limit,
                    int offset
            ) {
                return List.of();
            }
        };

        private NoopHolder() {
        }
    }
}
