package com.npdev.kernel.ports;

public record AuditQuery(
        String tenantId,
        String actorId,
        String action,
        String resourceType,
        String resourceId,
        Long fromMs,
        Long toMs,
        int limit,
        int offset
) {
    public AuditQuery {
        tenantId = normalize(tenantId);
        actorId = normalize(actorId);
        action = normalize(action);
        resourceType = normalize(resourceType);
        resourceId = normalize(resourceId);
        fromMs = normalizeEpoch(fromMs);
        toMs = normalizeEpoch(toMs);
        if (limit <= 0) {
            limit = 50;
        }
        if (limit > 1000) {
            limit = 1000;
        }
        if (offset < 0) {
            offset = 0;
        }
    }

    public static AuditQuery emptyForTenant(String tenantId) {
        return new AuditQuery(tenantId, null, null, null, null, null, null, 50, 0);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static Long normalizeEpoch(Long value) {
        if (value == null) {
            return null;
        }
        return value < 0L ? 0L : value;
    }
}

