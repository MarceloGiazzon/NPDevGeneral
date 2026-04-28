package com.npdev.kernel.capability;

public record CapabilityOpKey(
        String tenantId,
        String capabilityName,
        String operationName
) {
    public CapabilityOpKey {
        tenantId = normalize(tenantId, "tenantId");
        capabilityName = normalize(capabilityName, "capabilityName");
        operationName = normalize(operationName, "operationName");
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
}