package com.npdev.kernel.ports;

import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.CircuitBreakerStateSummary;
import com.npdev.kernel.capability.CircuitBreakerTransitions;

import java.util.List;

public interface CircuitBreakerStateStore {
    CircuitBreakerState get(CapabilityOpKey key);

    void put(CapabilityOpKey key, CircuitBreakerState state);

    void reset(CapabilityOpKey key);

    /**
     * Atomically record one circuit-eligible failure against {@code key} and return the resulting state.
     *
     * <p><b>REG-37.</b> This method exists because {@code get()} + {@link CircuitBreakerTransitions} +
     * {@code put()} done by the caller is a read-modify-write race: concurrent failures -- the exact
     * load a breaker exists for -- both read the same count and both write count+1, so the counter
     * undercounts and the circuit opens late. Read, decide and write must happen inside one critical
     * section, and only the store owns one.</p>
     *
     * <p><b>Implementors: override this.</b> The default below is the non-atomic get-then-put, kept
     * only so that a store predating this method still compiles and behaves as it did. It is correct
     * only where no two threads can record a failure for the same key at once. Every store shipped in
     * this repo overrides it -- {@code ConcurrentHashMap.compute} in-process, {@code SELECT ... FOR
     * UPDATE} inside a transaction on JDBC.</p>
     */
    default CircuitBreakerState recordFailure(CapabilityOpKey key, long nowMs, int openAfterFailures, long openMs) {
        CircuitBreakerState next = CircuitBreakerTransitions.afterFailure(get(key), nowMs, openAfterFailures, openMs);
        put(key, next);
        return next;
    }

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
            public CircuitBreakerState recordFailure(CapabilityOpKey key, long nowMs, int openAfterFailures, long openMs) {
                // Trivially atomic: it stores nothing, so there is nothing to race on.
                return CircuitBreakerState.closed();
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
