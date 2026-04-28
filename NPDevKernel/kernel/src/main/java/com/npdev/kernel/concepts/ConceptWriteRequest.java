package com.npdev.kernel.concepts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ConceptWriteRequest(
        String conceptName,
        String id,
        String tenantId,
        Map<String, Object> data
) {
    public ConceptWriteRequest {
        conceptName = normalizeRequired(conceptName, "conceptName");
        id = normalizeRequired(id, "id");
        tenantId = normalizeOptional(tenantId);
        data = copyData(data);
    }

    private static Map<String, Object> copyData(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
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
