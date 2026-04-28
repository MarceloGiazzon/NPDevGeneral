package com.npdev.kernel.trace;

import java.util.Map;

public record FlowTraceMeta(
        String executionId,
        String correlationId,
        String flowName,
        String tenantId,
        String actorId,
        Map<String, Object> tags
) {
    public FlowTraceMeta {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must be non-blank");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must be non-blank");
        }
        if (flowName == null || flowName.isBlank()) {
            throw new IllegalArgumentException("flowName must be non-blank");
        }
        tenantId = normalizeOptional(tenantId);
        actorId = normalizeOptional(actorId);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    public FlowTraceMeta(
            String executionId,
            String correlationId,
            String flowName,
            Map<String, Object> tags
    ) {
        this(executionId, correlationId, flowName, null, null, tags);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
