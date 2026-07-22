package com.npdev.kernel.concepts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public record ConceptRecord(
        String conceptName,
        String id,
        String tenantId,
        Map<String, Object> data,
        Long rowVersion
) {
    public ConceptRecord {
        conceptName = normalizeRequired(conceptName, "conceptName");
        id = normalizeRequired(id, "id");
        tenantId = normalizeOrDefault(tenantId, "default");
        data = copyData(data);
    }

    /**
     * LNCH-16: pre-existing 4-arg shape, preserved so the many callers that predate optimistic
     * locking keep compiling unchanged. {@code rowVersion() == null} means "unversioned" -- a
     * {@link com.npdev.kernel.ports.ConceptStore#save} call with a null rowVersion is an
     * unconditional write (today's behavior, still the default), NOT a claim that the row has no
     * version at all.
     */
    public ConceptRecord(String conceptName, String id, String tenantId, Map<String, Object> data) {
        this(conceptName, id, tenantId, data, null);
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
