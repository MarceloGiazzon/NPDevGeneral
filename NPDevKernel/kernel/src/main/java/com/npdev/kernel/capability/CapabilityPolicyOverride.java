package com.npdev.kernel.capability;

public record CapabilityPolicyOverride(
        Integer retryMaxAttempts,
        Long retryBaseDelayMs,
        Long retryMaxDelayMs,
        Long timeoutMs,
        Integer circuitOpenAfterFailures,
        Long circuitOpenMs,
        Integer bulkheadMaxConcurrent,
        Boolean cacheIdempotencyFailures
) {
    public static CapabilityPolicyOverride empty() {
        return new CapabilityPolicyOverride(null, null, null, null, null, null, null, null);
    }
}
