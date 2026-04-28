package com.npdev.kernel.ports;

import java.util.Locale;

public record TraceQuery(
        String correlationId,
        String flowName,
        String status,
        Long fromEpochMs,
        Long toEpochMs,
        int limit,
        int offset,
        String tenantId,
        String actorId
) {
    public TraceQuery {
        correlationId = normalize(correlationId);
        flowName = normalize(flowName);
        status = normalizeStatus(status);
        fromEpochMs = normalizeEpoch(fromEpochMs);
        toEpochMs = normalizeEpoch(toEpochMs);
        tenantId = normalize(tenantId);
        actorId = normalize(actorId);
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

    public TraceQuery(
            String correlationId,
            String flowName,
            String status,
            Long fromEpochMs,
            Long toEpochMs,
            int limit,
            int offset
    ) {
        this(correlationId, flowName, status, fromEpochMs, toEpochMs, limit, offset, null, null);
    }

    public static TraceQuery empty() {
        return new TraceQuery(null, null, null, null, null, 50, 0, null, null);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String normalizeStatus(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static Long normalizeEpoch(Long value) {
        if (value == null) {
            return null;
        }
        return value < 0 ? 0L : value;
    }
}
