package com.npdev.kernel.concepts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public record ConceptRecord(
        String conceptName,
        String id,
        String tenantId,
        Map<String, Object> data
) {
    public ConceptRecord {
        conceptName = normalizeRequired(conceptName, "conceptName");
        id = normalizeRequired(id, "id");
        tenantId = normalizeOrDefault(tenantId, "default");
        data = copyData(data);
    }

    private static Map<String, Object> copyData(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static String normalizeRequired(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return normalized;
    }

    private static String normalizeOrDefault(String value, String fallback) {
        String normalized = normalize(value);
        return normalized == null ? fallback : normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
