package com.npdev.runtime.support.crud.orchestration;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): a row read back from the
 * {@code npdev_scheduled_event} table.
 */
public record ScheduledEventRecord(
        UUID id,
        String scheduleKey,
        String orchestrationName,
        int actionIndex,
        String sourceEventName,
        String sourceEventId,
        String correlationId,
        String tenantId,
        String eventName,
        OffsetDateTime dueAt,
        String status,
        int attemptCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime processedAt,
        Map<String, Object> payload
) {
    public ScheduledEventRecord {
        payload = payload == null || payload.isEmpty()
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(payload));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id == null ? null : id.toString());
        out.put("scheduleKey", scheduleKey);
        out.put("orchestration", orchestrationName);
        out.put("actionIndex", actionIndex);
        out.put("sourceEventName", sourceEventName);
        out.put("sourceEventId", sourceEventId);
        out.put("correlationId", correlationId);
        out.put("tenantId", tenantId);
        out.put("eventName", eventName);
        out.put("dueAt", dueAt == null ? null : dueAt.toString());
        out.put("status", status);
        out.put("attemptCount", attemptCount);
        out.put("createdAt", createdAt == null ? null : createdAt.toString());
        out.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
        out.put("processedAt", processedAt == null ? null : processedAt.toString());
        out.put("payload", payload);
        return out;
    }
}
