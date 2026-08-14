package com.npdev.dsl.v1.compiled;

/**
 * npdev-capability-execution-policy-mirror: this shape MIRRORS, field-for-field,
 * {@code com.npdev.kernel.capabilities.CapabilityExecutionPolicy} in the kernel module (the DSL
 * module cannot depend on kernel types, so the two are independent classes rather than one shared
 * type). See that class's javadoc for the twin-pair rule this comment anchors
 * (scripts/quality/twin-pair-registry.json, enforced by check-twin-pair-consistency.py) -- a field
 * added to one side without the other silently never reaches the generator/runtime on whichever side
 * was skipped.
 */
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
