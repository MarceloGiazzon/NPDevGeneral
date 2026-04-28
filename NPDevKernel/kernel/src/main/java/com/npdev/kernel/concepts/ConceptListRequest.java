package com.npdev.kernel.concepts;

public record ConceptListRequest(
        String conceptName,
        String tenantId
) {
    public ConceptListRequest {
        conceptName = normalizeRequired(conceptName, "conceptName");
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
