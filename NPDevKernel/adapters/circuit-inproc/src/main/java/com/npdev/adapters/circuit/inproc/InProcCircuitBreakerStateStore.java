package com.npdev.adapters.circuit.inproc;

import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.CircuitBreakerStateSummary;
import com.npdev.kernel.ports.CircuitBreakerStateStore;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class InProcCircuitBreakerStateStore implements CircuitBreakerStateStore {
    private final Map<CapabilityOpKey, CircuitBreakerState> states = new ConcurrentHashMap<>();

    @Override
    public CircuitBreakerState get(CapabilityOpKey key) {
        Objects.requireNonNull(key, "key");
        return states.getOrDefault(key, CircuitBreakerState.closed());
    }

    @Override
    public void put(CapabilityOpKey key, CircuitBreakerState state) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(state, "state");
        states.put(key, state);
    }

    @Override
    public void reset(CapabilityOpKey key) {
        Objects.requireNonNull(key, "key");
        states.remove(key);
    }

    @Override
    public List<CircuitBreakerStateSummary> listStates(
            String tenantId,
            String capabilityName,
            String operationName,
            int limit,
            int offset
    ) {
        String scopedTenant = normalize(tenantId);
        if (scopedTenant == null) {
            return List.of();
        }
        String scopedCapability = normalize(capabilityName);
        String scopedOperation = normalize(operationName);
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 1000);
        int effectiveOffset = Math.max(offset, 0);

        List<CircuitBreakerStateSummary> rows = states.entrySet().stream()
                .filter(entry -> scopedTenant.equals(entry.getKey().tenantId()))
                .filter(entry -> scopedCapability == null || scopedCapability.equals(entry.getKey().capabilityName()))
                .filter(entry -> scopedOperation == null || scopedOperation.equals(entry.getKey().operationName()))
                .map(entry -> toSummary(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(CircuitBreakerStateSummary::capabilityName)
                        .thenComparing(CircuitBreakerStateSummary::operationName)
                        .thenComparing(CircuitBreakerStateSummary::tenantId))
                .toList();
        int fromIndex = Math.min(effectiveOffset, rows.size());
        int toIndex = Math.min(fromIndex + effectiveLimit, rows.size());
        return List.copyOf(rows.subList(fromIndex, toIndex));
    }

    public Map<CapabilityOpKey, CircuitBreakerState> snapshotStates() {
        return Map.copyOf(states);
    }

    private static CircuitBreakerStateSummary toSummary(CapabilityOpKey key, CircuitBreakerState state) {
        if (state == null) {
            state = CircuitBreakerState.closed();
        }
        return new CircuitBreakerStateSummary(
                key.tenantId(),
                key.capabilityName(),
                key.operationName(),
                state.state(),
                state.consecutiveFailures(),
                state.openedAtMs(),
                state.lastFailureAtMs(),
                state.halfOpenAllowedAtMs(),
                state.halfOpenTrialCount()
        );
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
