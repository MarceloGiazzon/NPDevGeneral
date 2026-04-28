package com.npdev.kernel.audit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record AuditRecord(
        String auditId,
        long timestampMs,
        String tenantId,
        String actorId,
        Set<String> roles,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String reasonCode,
        Map<String, String> tags,
        Map<String, String> meta
) {
    public AuditRecord {
        auditId = normalizeOrDefault(auditId, UUID.randomUUID().toString());
        if (timestampMs <= 0L) {
            timestampMs = System.currentTimeMillis();
        }
        tenantId = normalizeOrDefault(tenantId, "default");
        actorId = normalizeOrDefault(actorId, "anonymous");
        roles = normalizeSet(roles, "USER");
        action = normalizeRequired(action, "UNKNOWN_ACTION");
        resourceType = normalizeRequired(resourceType, "UNKNOWN_RESOURCE");
        resourceId = normalizeOrDefault(resourceId, "<none>");
        outcome = normalizeRequired(outcome, "ERROR");
        reasonCode = normalizeOrDefault(reasonCode, "n/a");
        tags = normalizeMap(tags);
        meta = normalizeMap(meta);
    }

    public static AuditRecord create(
            String tenantId,
            String actorId,
            Set<String> roles,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String reasonCode,
            Map<String, String> tags,
            Map<String, String> meta
    ) {
        return new AuditRecord(
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                tenantId,
                actorId,
                roles,
                action,
                resourceType,
                resourceId,
                outcome,
                reasonCode,
                tags,
                meta
        );
    }

    private static String normalizeRequired(String value, String fallback) {
        String normalized = normalize(value);
        return normalized == null ? fallback : normalized;
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

    private static Set<String> normalizeSet(Set<String> values, String fallback) {
        if (values == null || values.isEmpty()) {
            return Set.of(fallback);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = normalize(value);
            if (item != null) {
                normalized.add(item);
            }
        }
        if (normalized.isEmpty()) {
            normalized.add(fallback);
        }
        return Set.copyOf(normalized);
    }

    private static Map<String, String> normalizeMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = normalize(entry.getKey());
            if (key == null) {
                continue;
            }
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            normalized.put(key, value);
        }
        return Map.copyOf(normalized);
    }
}

