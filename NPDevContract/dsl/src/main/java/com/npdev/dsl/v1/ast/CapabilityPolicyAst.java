package com.npdev.dsl.v1.ast;

public final class CapabilityPolicyAst {
    private final Integer retryCount;
    private final Long retryDelayMs;
    private final Long timeoutMs;
    private final Integer circuitOpenAfterFailures;
    private final Long circuitOpenMs;
    private final Integer bulkheadMaxConcurrent;
    private final String idempotencyKeyField;
    private final String failureClassification;

    public CapabilityPolicyAst(
            Integer retryCount,
            Long retryDelayMs,
            Long timeoutMs,
            Integer circuitOpenAfterFailures,
            Long circuitOpenMs,
            Integer bulkheadMaxConcurrent,
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

    public Integer getRetryCount() {
        return retryCount;
    }

    public Long getRetryDelayMs() {
        return retryDelayMs;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public Integer getCircuitOpenAfterFailures() {
        return circuitOpenAfterFailures;
    }

    public Long getCircuitOpenMs() {
        return circuitOpenMs;
    }

    public Integer getBulkheadMaxConcurrent() {
        return bulkheadMaxConcurrent;
    }

    public String getIdempotencyKeyField() {
        return idempotencyKeyField;
    }

    public String getFailureClassification() {
        return failureClassification;
    }
}
