package com.npdev.kernel.capability;

public record CircuitBreakerState(
        CircuitState state,
        int consecutiveFailures,
        long openedAtMs,
        long lastFailureAtMs,
        long halfOpenAllowedAtMs,
        int halfOpenTrialCount
) {
    public CircuitBreakerState {
        state = state == null ? CircuitState.CLOSED : state;
        if (consecutiveFailures < 0) {
            throw new IllegalArgumentException("consecutiveFailures must be >= 0");
        }
        if (openedAtMs < 0) {
            throw new IllegalArgumentException("openedAtMs must be >= 0");
        }
        if (lastFailureAtMs < 0) {
            throw new IllegalArgumentException("lastFailureAtMs must be >= 0");
        }
        if (halfOpenAllowedAtMs < 0) {
            throw new IllegalArgumentException("halfOpenAllowedAtMs must be >= 0");
        }
        if (halfOpenTrialCount < 0) {
            throw new IllegalArgumentException("halfOpenTrialCount must be >= 0");
        }
    }

    public static CircuitBreakerState closed() {
        return new CircuitBreakerState(CircuitState.CLOSED, 0, 0L, 0L, 0L, 0);
    }
}