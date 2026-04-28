package com.npdev.kernel;

import com.npdev.kernel.errors.ErrorKind;
import com.npdev.kernel.errors.FailureCodes;
import com.npdev.kernel.errors.FailureInfo;

import java.util.LinkedHashMap;
import java.util.Map;

public record CapabilityError(
        String code,
        String message,
        CapabilityErrorKind kind,
        Map<String, Object> details
) {
    public CapabilityError {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must be non-blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must be non-blank");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must be non-null");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public FailureInfo toFailureInfo() {
        String stableCode = mapStableCode(code, kind);
        return FailureInfo.of(
                ErrorKind.fromCapabilityKind(kind),
                stableCode,
                message,
                stringifyDetails(details)
        );
    }

    private static String mapStableCode(String rawCode, CapabilityErrorKind rawKind) {
        String normalizedCode = rawCode == null ? "" : rawCode.trim().toUpperCase();
        if ("CAPABILITY_CIRCUIT_OPEN".equals(normalizedCode)) {
            return FailureCodes.CIRCUIT_OPEN;
        }
        if ("CAPABILITY_BULKHEAD_FULL".equals(normalizedCode)) {
            return FailureCodes.BULKHEAD_FULL;
        }
        if ("CAPABILITY_IDEMPOTENCY_CACHED_FAILURE".equals(normalizedCode)) {
            return FailureCodes.IDEMPOTENCY_HIT_FAILED;
        }
        if ("CAPABILITY_TIMEOUT".equals(normalizedCode)) {
            return FailureCodes.CAPABILITY_TIMEOUT;
        }

        if (rawKind == null) {
            return FailureCodes.SYSTEM_EXCEPTION;
        }
        return switch (rawKind) {
            case CONTRACT -> FailureCodes.CAPABILITY_CONTRACT;
            case TRANSIENT -> FailureCodes.CAPABILITY_TRANSIENT;
            case RATE_LIMIT -> FailureCodes.CAPABILITY_RATE_LIMITED;
            case TIMEOUT -> FailureCodes.CAPABILITY_TIMEOUT;
            case AUTH -> FailureCodes.CAPABILITY_AUTH;
            case NOT_FOUND -> FailureCodes.CAPABILITY_NOT_FOUND;
            case PERMANENT -> FailureCodes.SYSTEM_EXCEPTION;
        };
    }

    private static Map<String, String> stringifyDetails(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            out.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return Map.copyOf(out);
    }
}
