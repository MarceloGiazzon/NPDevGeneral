package com.npdev.kernel.concepts;

public record ConceptReadRequest(
        String conceptName,
        String id,
        String tenantId
) {
    public ConceptReadRequest {
        conceptName = normalizeRequired(conceptName, "conceptName");
        id = normalizeRequired(id, "id");
        tenantId = normalizeOptional(tenantId);
    }

    private static String normalizeRequired(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
