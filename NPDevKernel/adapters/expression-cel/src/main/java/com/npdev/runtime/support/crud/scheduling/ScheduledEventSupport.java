package com.npdev.runtime.support.crud.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.runtime.support.crud.orchestration.ScheduledEventRecord;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.OBJECT_MAPPER;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.mapWithStringKeys;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.normalize;
import static com.npdev.runtime.support.crud.executioncontext.PayloadClaimsSupport.asNonBlankString;
import static com.npdev.runtime.support.crud.orchestration.OrchestrationSupport.resolveOrchestrationSubjectId;
import static com.npdev.runtime.support.crud.orchestration.OrchestrationSupport.serializePayloadForIdempotency;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toInteger;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toOffsetDateTime;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toUuid;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): the stateless helpers around
 * {@link ScheduledEventSql} -- mapping a {@code npdev_scheduled_event} row back into a
 * {@link ScheduledEventRecord}, building its de-duplication key, and the small paging/payload
 * utilities the scheduler's JDBC methods share.
 */
public final class ScheduledEventSupport {

    public static final int DEFAULT_SCHEDULE_PAGE_SIZE = 100;

    private ScheduledEventSupport() {
    }

    public static ScheduledEventRecord toScheduledEventRecord(ResultSet row) throws SQLException {
        UUID id = toUuid(row.getObject("id"));
        String scheduleKey = asNonBlankString(row.getObject("schedule_key"));
        String orchestrationName = asNonBlankString(row.getObject("orchestration_name"));
        Integer actionIndex = toInteger(row.getObject("action_index"));
        String sourceEventName = asNonBlankString(row.getObject("source_event_name"));
        String sourceEventId = asNonBlankString(row.getObject("source_event_id"));
        String correlationId = asNonBlankString(row.getObject("trigger_correlation_id"));
        String tenantId = asNonBlankString(row.getObject("tenant_id"));
        String eventName = asNonBlankString(row.getObject("event_name"));
        OffsetDateTime dueAt = toOffsetDateTime(row.getObject("due_at"));
        String status = asNonBlankString(row.getObject("status"));
        Integer attemptCount = toInteger(row.getObject("attempt_count"));
        OffsetDateTime createdAt = toOffsetDateTime(row.getObject("created_at"));
        OffsetDateTime updatedAt = toOffsetDateTime(row.getObject("updated_at"));
        OffsetDateTime processedAt = toOffsetDateTime(row.getObject("processed_at"));
        Map<String, Object> payload = toMapPayload(row.getObject("payload"));
        if (id == null || eventName == null || status == null) {
            return null;
        }
        return new ScheduledEventRecord(
                id,
                scheduleKey,
                orchestrationName,
                actionIndex == null ? 0 : actionIndex,
                sourceEventName,
                sourceEventId,
                correlationId,
                tenantId,
                eventName,
                dueAt,
                status,
                attemptCount == null ? 0 : attemptCount,
                createdAt,
                updatedAt,
                processedAt,
                payload
        );
    }

    public static String buildScheduleKey(
            String orchestrationName,
            int actionIndex,
            EventEnvelope envelope,
            Map<String, Object> eventPayload
    ) {
        String normalizedOrchestration = normalize(orchestrationName);
        String sourceEvent = envelope == null ? "" : normalize(envelope.eventName());
        String sourceEventId = envelope == null ? "" : nullToEmpty(envelope.eventId());
        String correlationId = envelope == null ? "" : nullToEmpty(envelope.correlationId());
        String subject = resolveOrchestrationSubjectId(eventPayload);
        if (subject == null || subject.isBlank()) {
            subject = serializePayloadForIdempotency(eventPayload);
        }
        if (sourceEventId.isBlank()) {
            sourceEventId = sourceEvent + ":" + correlationId + ":" + subject;
        }
        return normalizedOrchestration + ":" + actionIndex + ":" + sourceEvent + ":" + sourceEventId;
    }

    public static int sanitizeScheduleLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_SCHEDULE_PAGE_SIZE;
        }
        return Math.min(1000, limit);
    }

    public static int sanitizeScheduleOffset(Integer offset) {
        if (offset == null || offset < 0) {
            return 0;
        }
        return offset;
    }

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static Map<String, Object> buildScheduleEvidencePayload(Object... keyValues) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (keyValues == null || keyValues.length == 0) {
            return payload;
        }
        int length = keyValues.length - (keyValues.length % 2);
        for (int index = 0; index < length; index += 2) {
            Object key = keyValues[index];
            Object value = keyValues[index + 1];
            if (!(key instanceof String textKey) || textKey.isBlank() || value == null) {
                continue;
            }
            payload.put(textKey, value);
        }
        return payload;
    }

    public static Map<String, Object> toMapPayload(Object payloadValue) {
        if (payloadValue == null) {
            return Map.of();
        }
        if (payloadValue instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = mapWithStringKeys(rawMap);
            return map.isEmpty() ? Map.of() : Map.copyOf(map);
        }
        if (payloadValue instanceof JsonNode jsonNode) {
            Object converted = OBJECT_MAPPER.convertValue(jsonNode, Object.class);
            if (converted instanceof Map<?, ?> convertedMap) {
                Map<String, Object> map = mapWithStringKeys(convertedMap);
                return map.isEmpty() ? Map.of() : Map.copyOf(map);
            }
            return Map.of();
        }
        String jsonText = String.valueOf(payloadValue).trim();
        if (jsonText.isEmpty()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(jsonText, Map.class);
            return parsed == null || parsed.isEmpty() ? Map.of() : Map.copyOf(parsed);
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
