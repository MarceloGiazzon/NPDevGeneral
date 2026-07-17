package com.npdev.kernel.concepts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ConceptWriteRequest(
        String conceptName,
        String id,
        String tenantId,
        Map<String, Object> data,
        Long expectedRowVersion,
        boolean force
) {
    public ConceptWriteRequest {
        conceptName = normalizeRequired(conceptName, "conceptName");
        id = normalizeRequired(id, "id");
        tenantId = normalizeOptional(tenantId);
        data = copyData(data);
    }

    /**
     * LNCH-16: pre-existing 4-arg shape, preserved so every caller that predates optimistic
     * locking keeps compiling unchanged -- {@code expectedRowVersion == null} and
     * {@code force == false} together mean "don't ask for a compare-and-increment", which is
     * exactly today's behavior (an unconditional write on update, same as create).
     */
    public ConceptWriteRequest(String conceptName, String id, String tenantId, Map<String, Object> data) {
        this(conceptName, id, tenantId, data, null, false);
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
