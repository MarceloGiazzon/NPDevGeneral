package com.npdev.kernel.events;

public record EventMetaSummary(
        String eventId,
        String tenantId,
        String correlationId,
        String eventName,
        String flowName,
        int stepIndex,
        long timestampMs
) {
}

