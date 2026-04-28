package com.npdev.kernel.capability;

public record CircuitBreakerStateSummary(
        String tenantId,
        String capabilityName,
        String operationName,
        CircuitState state,
        int consecutiveFailures,
        long openedAtMs,
        long lastFailureAtMs,
        long halfOpenAllowedAtMs,
        int halfOpenTrialCount
) {
}
