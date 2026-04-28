package com.npdev.dsl.v1.compiled;

public final class CompiledCapabilityExecutionPolicy {
    private final int retryCount;
    private final long retryDelayMs;
    private final long timeoutMs;
    private final int circuitOpenAfterFailures;
    private final long circuitOpenMs;
    private final int bulkheadMaxConcurrent;
    private final String idempotencyKeyField;
    private final String failureClassification;

    public CompiledCapabilityExecutionPolicy(
            int retryCount,
            long retryDelayMs,
            long timeoutMs,
            int circuitOpenAfterFailures,
            long circuitOpenMs,
            int bulkheadMaxConcurrent,
            String idempotencyKeyField,
            String failureClassification
    ) {
        this.retryCount = retryCount;
        this.retryDelayMs = retryDelayMs;
        this.timeoutMs = timeoutMs;
        this.circuitOpenAfterFailures = circuitOpenAfterFailures;
        this.circuitOpenMs = circuitOpenMs;
        this.bulkheadMaxConcurrent = bulkheadMaxConcurrent;
        this.idempotencyKeyField = idempotencyKeyField;
        this.failureClassification = failureClassification;
    }

    public static CompiledCapabilityExecutionPolicy defaults() {
        return new CompiledCapabilityExecutionPolicy(1, 0L, 0L, 0, 0L, 0, null, null);
    }

    public int getRetryCount() {
        return retryCount;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public int getCircuitOpenAfterFailures() {
        return circuitOpenAfterFailures;
    }

    public long getCircuitOpenMs() {
        return circuitOpenMs;
    }

    public int getBulkheadMaxConcurrent() {
        return bulkheadMaxConcurrent;
    }

    public String getIdempotencyKeyField() {
        return idempotencyKeyField;
    }

    public String getFailureClassification() {
        return failureClassification;
    }
}
