package com.npdev.kernel;

import com.npdev.kernel.errors.ErrorKind;
import com.npdev.kernel.errors.FailureInfo;

import java.util.Map;

public record CapabilityResult(
        boolean ok,
        Object value,
        CapabilityError error
) {
    public CapabilityResult {
        if (ok && error != null) {
            throw new IllegalArgumentException("Successful capability result must not contain error");
        }
        if (!ok && error == null) {
            throw new IllegalArgumentException("Failed capability result must contain error");
        }
        if (!ok && value != null) {
            throw new IllegalArgumentException("Failed capability result must not contain value");
        }
    }

    public static CapabilityResult success(Object value) {
        return new CapabilityResult(true, value, null);
    }

    public static CapabilityResult failure(CapabilityError error) {
        return new CapabilityResult(false, null, error);
    }

    public static CapabilityResult failure(
            String code,
            String message,
            CapabilityErrorKind kind,
            Map<String, Object> details
    ) {
        return failure(new CapabilityError(code, message, kind, details));
    }

    public static CapabilityResult failure(FailureInfo failureInfo) {
        FailureInfo safeFailure = failureInfo == null
                ? FailureInfo.of(ErrorKind.SYSTEM, "system_exception", "Capability failed")
                : failureInfo;
        CapabilityErrorKind kind = switch (safeFailure.kind()) {
            case CONTRACT -> CapabilityErrorKind.CONTRACT;
            case TRANSIENT -> CapabilityErrorKind.TRANSIENT;
            case RATE_LIMIT -> CapabilityErrorKind.RATE_LIMIT;
            case TIMEOUT -> CapabilityErrorKind.TIMEOUT;
            case AUTH, FORBIDDEN -> CapabilityErrorKind.AUTH;
            case INPUT_VALIDATION, INVARIANT_VIOLATION, SYSTEM -> CapabilityErrorKind.PERMANENT;
        };
        return failure(new CapabilityError(
                safeFailure.code(),
                safeFailure.message(),
                kind,
                Map.copyOf(safeFailure.details())
        ));
    }
}
