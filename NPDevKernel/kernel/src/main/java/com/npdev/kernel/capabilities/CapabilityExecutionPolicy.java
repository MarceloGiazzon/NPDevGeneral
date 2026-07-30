package com.npdev.kernel.capabilities;

import com.npdev.kernel.CapabilityErrorKind;

public record CapabilityExecutionPolicy(
        int retryCount,
        long retryDelayMs,
        long timeoutMs,
        // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5 / capabilityPolicy): circuitOpenAfterFailures
        // and bulkheadMaxConcurrent are 0 when not declared on the model (schema requires >= 1 when
        // present, so 0 is never a legal declared value -- same "0 means unset" convention retryDelayMs/
        // timeoutMs already use). circuitOpenMs is 0 when not declared OR when explicitly declared as
        // 0ms (schema allows >= 0) -- the same pre-existing ambiguity retryDelayMs/timeoutMs already
        // have, not a new one. Resolved against KernelRunner's own hardcoded fallback constants in
        // resolveEffectiveCapabilityPolicy, exactly like retryCount/timeoutMs already are.
        int circuitOpenAfterFailures,
        long circuitOpenMs,
        int bulkheadMaxConcurrent,
        String idempotencyKeyField,
        FailureClassification failureClassification
) {
    public enum FailureClassification {
        TRANSIENT,
        PERMANENT,
        CONTRACT
    }

    public CapabilityExecutionPolicy {
        if (retryCount < 1) {
            throw new IllegalArgumentException("retryCount must be >= 1");
        }
        if (retryDelayMs < 0) {
            throw new IllegalArgumentException("retryDelayMs must be >= 0");
        }
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("timeoutMs must be >= 0");
        }
        if (circuitOpenAfterFailures < 0) {
            throw new IllegalArgumentException("circuitOpenAfterFailures must be >= 0 (0 means unset)");
        }
        if (circuitOpenMs < 0) {
            throw new IllegalArgumentException("circuitOpenMs must be >= 0");
        }
        if (bulkheadMaxConcurrent < 0) {
            throw new IllegalArgumentException("bulkheadMaxConcurrent must be >= 0 (0 means unset)");
        }
        if (idempotencyKeyField != null && idempotencyKeyField.isBlank()) {
            throw new IllegalArgumentException("idempotencyKeyField must be null or non-blank");
        }
    }

    public static CapabilityExecutionPolicy defaults() {
        return new CapabilityExecutionPolicy(1, 0, 0, 0, 0, 0, null, null);
    }

    public CapabilityErrorKind applyFailureClassification(CapabilityErrorKind original) {
        if (failureClassification == null || original == null || original == CapabilityErrorKind.NOT_FOUND) {
            return original;
        }
        return switch (failureClassification) {
            case TRANSIENT -> CapabilityErrorKind.TRANSIENT;
            case PERMANENT -> CapabilityErrorKind.PERMANENT;
            case CONTRACT -> CapabilityErrorKind.CONTRACT;
        };
    }
}

