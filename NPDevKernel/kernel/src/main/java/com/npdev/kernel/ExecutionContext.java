package com.npdev.kernel;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record ExecutionContext(
        String tenantId,
        String actorId,
        Map<String, String> tags,
        Set<String> roles
) {
    private static final String DEFAULT_TENANT_ID = "default";
    private static final String DEFAULT_ACTOR_ID = "anonymous";
    private static final String DEFAULT_ROLE = "USER";

    public ExecutionContext {
        tenantId = normalizeTenantId(tenantId);
        actorId = normalizeOrDefault(actorId, DEFAULT_ACTOR_ID);
        tags = normalizeTags(tags);
        roles = normalizeRoles(roles);
    }

    public static ExecutionContext anonymous() {
        return new ExecutionContext(DEFAULT_TENANT_ID, DEFAULT_ACTOR_ID, Map.of(), Set.of(DEFAULT_ROLE));
    }

    public static ExecutionContext of(String tenantId, String actorId) {
        return new ExecutionContext(tenantId, actorId, Map.of(), Set.of(DEFAULT_ROLE));
    }

    /**
     * LNCH-12: the principal a background trigger (currently: the cron scheduler) runs a flow
     * under -- goes through the SAME role/permission checks a human actor would (role "ADMIN",
     * broad enough to run any flow a trusted internal job needs), deliberately NOT a bypass like
     * the ControlPanel superuser key. The distinct actorId lets an event/audit trail tell a
     * scheduled run apart from one a real admin triggered by hand.
     */
    public static ExecutionContext system(String tenantId) {
        return new ExecutionContext(tenantId, "system:scheduler", Map.of("trigger", "schedule"), Set.of("ADMIN"));
    }

    public ExecutionContext withTag(String key, String value) {
        String normalizedKey = normalize(key);
        if (normalizedKey == null) {
            return this;
        }
        Map<String, String> nextTags = new LinkedHashMap<>(tags);
        nextTags.put(normalizedKey, value == null ? "" : value.trim());
        return new ExecutionContext(tenantId, actorId, nextTags, roles);
    }

    public ExecutionContext withRoles(Set<String> nextRoles) {
        return new ExecutionContext(tenantId, actorId, tags, nextRoles);
    }

    public String correlationId() {
        String direct = tags.get("correlationId");
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        String dashed = tags.get("correlation-id");
        return dashed == null || dashed.isBlank() ? null : dashed;
    }

    public String idempotencyKey() {
        String direct = tags.get("idempotencyKey");
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        String dashed = tags.get("idempotency-key");
        return dashed == null || dashed.isBlank() ? null : dashed;
    }

    public Map<String, String> metadata() {
        return tags;
    }

    public boolean hasRole(String role) {
        String normalizedRole = normalizeRole(role);
        return normalizedRole != null && roles.contains(normalizedRole);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String normalizeOrDefault(String value, String fallback) {
        String normalized = normalize(value);
        return normalized == null ? fallback : normalized;
    }

    /**
     * REG-25: tenantId is the isolation-bucket key, so it is canonicalized to lowercase (not just
     * trimmed) at this single choke point. Every read and write derives its tenant from an
     * {@link ExecutionContext}, so two casings of the same logical tenant ({@code Acme}/{@code acme})
     * converge to one bucket here -- matching {@code TenantRegistryService}, which already lowercases
     * on insert. The reserved "default" sentinel (REG-24) is unaffected (already lowercase). actorId
     * is deliberately NOT lowercased: actor identities are case-sensitive.
     */
    private static String normalizeTenantId(String tenantId) {
        String normalized = normalize(tenantId);
        return normalized == null ? DEFAULT_TENANT_ID : normalized.toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> normalizeTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey().trim();
            if (key.isBlank()) {
                continue;
            }
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            normalized.put(key, value);
        }
        return Map.copyOf(normalized);
    }

    private static Set<String> normalizeRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of(DEFAULT_ROLE);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String role : roles) {
            String normalizedRole = normalizeRole(role);
            if (normalizedRole != null) {
                normalized.add(normalizedRole);
            }
        }
        if (normalized.isEmpty()) {
            normalized.add(DEFAULT_ROLE);
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeRole(String role) {
        String normalized = normalize(role);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }
}
