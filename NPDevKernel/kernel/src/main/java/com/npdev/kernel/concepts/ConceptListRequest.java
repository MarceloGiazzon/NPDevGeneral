package com.npdev.kernel.concepts;

public record ConceptListRequest(
        String conceptName,
        String tenantId,
        String filterField,
        String filterValue
) {
    public ConceptListRequest {
        conceptName = normalizeRequired(conceptName, "conceptName");
        tenantId = normalizeOptional(tenantId);
        filterField = normalizeOptional(filterField);
        // filterValue intentionally NOT blank-normalized to null: an empty-string match is a legitimate value.
    }

    public ConceptListRequest(String conceptName, String tenantId) {
        this(conceptName, tenantId, null, null);
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
