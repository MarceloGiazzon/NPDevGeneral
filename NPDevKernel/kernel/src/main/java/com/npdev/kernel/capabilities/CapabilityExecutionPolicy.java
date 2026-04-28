package com.npdev.kernel.capabilities;

import com.npdev.kernel.CapabilityErrorKind;

public record CapabilityExecutionPolicy(
        int retryCount,
        long retryDelayMs,
        long timeoutMs,
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
        if (idempotencyKeyField != null && idempotencyKeyField.isBlank()) {
            throw new IllegalArgumentException("idempotencyKeyField must be null or non-blank");
        }
    }

    public static CapabilityExecutionPolicy defaults() {
        return new CapabilityExecutionPolicy(1, 0, 0, null, null);
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

