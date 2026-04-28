package com.npdev.kernel.capability;

public record CapabilityPolicy(
        int retryMaxAttempts,
        long retryBaseDelayMs,
        long retryMaxDelayMs,
        long timeoutMs,
        int circuitOpenAfterFailures,
        long circuitOpenMs,
        int bulkheadMaxConcurrent,
        boolean cacheIdempotencyFailures
) {
    public CapabilityPolicy {
        if (retryMaxAttempts < 1) {
            throw new IllegalArgumentException("retryMaxAttempts must be >= 1");
        }
        if (retryBaseDelayMs < 0) {
            throw new IllegalArgumentException("retryBaseDelayMs must be >= 0");
        }
        if (retryMaxDelayMs < 0) {
            throw new IllegalArgumentException("retryMaxDelayMs must be >= 0");
        }
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("timeoutMs must be >= 0");
        }
        if (circuitOpenAfterFailures < 1) {
            throw new IllegalArgumentException("circuitOpenAfterFailures must be >= 1");
        }
        if (circuitOpenMs < 0) {
            throw new IllegalArgumentException("circuitOpenMs must be >= 0");
        }
        if (bulkheadMaxConcurrent < 1) {
            throw new IllegalArgumentException("bulkheadMaxConcurrent must be >= 1");
        }
    }

    public static CapabilityPolicy defaults() {
        return new CapabilityPolicy(1, 0, 0, 0, 5, 30_000L, 10, false);
    }
}
