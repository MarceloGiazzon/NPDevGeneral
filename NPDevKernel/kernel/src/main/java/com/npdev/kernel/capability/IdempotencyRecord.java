package com.npdev.kernel.capability;

public record IdempotencyRecord(
        String tenantId,
        String idempotencyKey,
        String capabilityName,
        String operationName,
        long createdAtMs,
        String status,
        String resultJsonRedacted,
        String errorCode
) {
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    public IdempotencyRecord {
        tenantId = normalize(tenantId, "tenantId");
        idempotencyKey = normalize(idempotencyKey, "idempotencyKey");
        capabilityName = normalize(capabilityName, "capabilityName");
        operationName = normalize(operationName, "operationName");
        if (createdAtMs <= 0) {
            throw new IllegalArgumentException("createdAtMs must be > 0");
        }
        status = normalize(status, "status");
        resultJsonRedacted = normalizeNullable(resultJsonRedacted);
        errorCode = normalizeNullable(errorCode);
    }

    public boolean success() {
        return STATUS_SUCCESS.equals(status);
    }

    private static String normalize(String value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " must be non-blank");
        }
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException(label + " must be non-blank");
        }
        return trimmed;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}