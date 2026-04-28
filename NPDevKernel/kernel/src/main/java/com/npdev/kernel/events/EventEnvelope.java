package com.npdev.kernel.events;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record EventEnvelope(
        String eventId,
        String eventName,
        long timestampEpochMs,
        Map<String, Object> payload,
        String correlationId,
        String causationId,
        String flowName,
        int stepIndex,
        String tenantId,
        String actorId
) {
    public EventEnvelope {
        eventId = requireNonBlank(eventId, "eventId");
        eventName = requireNonBlank(eventName, "eventName");
        if (timestampEpochMs <= 0) {
            throw new IllegalArgumentException("timestampEpochMs must be > 0");
        }
        correlationId = requireNonBlank(correlationId, "correlationId");
        causationId = requireNonBlank(causationId, "causationId");
        flowName = requireNonBlank(flowName, "flowName");
        if (stepIndex < -1) {
            throw new IllegalArgumentException("stepIndex must be >= -1");
        }
        tenantId = normalizeOptional(tenantId);
        actorId = normalizeOptional(actorId);
        payload = payload == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
    }

    public EventEnvelope(
            String eventId,
            String eventName,
            long timestampEpochMs,
            Map<String, Object> payload,
            String correlationId,
            String causationId,
            String flowName,
            int stepIndex
    ) {
        this(eventId, eventName, timestampEpochMs, payload, correlationId, causationId, flowName, stepIndex, null, null);
    }

    public static EventEnvelope create(
            String eventName,
            Map<String, Object> payload,
            String correlationId,
            String causationId,
            String flowName,
            int stepIndex
    ) {
        return create(eventName, payload, correlationId, causationId, flowName, stepIndex, null, null);
    }

    public static EventEnvelope create(
            String eventName,
            Map<String, Object> payload,
            String correlationId,
            String causationId,
            String flowName,
            int stepIndex,
            String tenantId,
            String actorId
    ) {
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                eventName,
                System.currentTimeMillis(),
                payload,
                correlationId,
                causationId,
                flowName,
                stepIndex,
                tenantId,
                actorId
        );
    }

    public static EventEnvelope of(String eventName, Map<String, Object> payload) {
        return create(
                eventName,
                payload == null ? Map.of() : payload,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "external",
                0
        );
    }

    public static EventEnvelope of(
            String eventName,
            Map<String, Object> payload,
            String correlationId,
            String causationId,
            Map<String, Object> metadata
    ) {
        return of(eventName, payload, correlationId, causationId, "external", 0, metadata);
    }

    public static EventEnvelope of(
            String eventName,
            Map<String, Object> payload,
            String correlationId,
            String causationId,
            String flowName,
            int stepIndex,
            Map<String, Object> metadata
    ) {
        return of(eventName, payload, correlationId, causationId, flowName, stepIndex, metadata, null, null);
    }

    public static EventEnvelope of(
            String eventName,
            Map<String, Object> payload,
            String correlationId,
            String causationId,
            String flowName,
            int stepIndex,
            Map<String, Object> metadata,
            String tenantId,
            String actorId
    ) {
        Map<String, Object> effectivePayload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        if (metadata != null && !metadata.isEmpty()) {
            effectivePayload.put("_meta", Map.copyOf(metadata));
        }
        return create(
                eventName,
                effectivePayload,
                correlationId == null || correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId,
                causationId == null || causationId.isBlank() ? UUID.randomUUID().toString() : causationId,
                flowName == null || flowName.isBlank() ? "external" : flowName,
                Math.max(stepIndex, 0),
                tenantId,
                actorId
        );
    }

    public String id() {
        return eventId;
    }

    public String topic() {
        return eventName;
    }

    public long timestamp() {
        return timestampEpochMs;
    }

    public String version() {
        return "v1";
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must be non-blank");
        }
        return value;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
