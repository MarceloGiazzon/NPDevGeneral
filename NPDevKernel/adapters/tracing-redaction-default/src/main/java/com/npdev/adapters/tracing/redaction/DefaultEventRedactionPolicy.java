package com.npdev.adapters.tracing.redaction;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.EventRedactionPolicy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class DefaultEventRedactionPolicy implements EventRedactionPolicy {
    private static final String MASKED = "***";
    private static final Set<String> DEBUG_PAYLOAD_ALLOWLIST = Set.of(
            "type",
            "status",
            "reason",
            "eventName",
            "adapterId",
            "entityId",
            "scheduleId",
            "appointmentId",
            "patientId",
            "providerId",
            "roomId",
            "capability",
            "operation",
            "orchestration",
            "sourceEvent",
            "sourceEventName",
            "sourceEventId",
            "correlationId"
    );

    @Override
    public EventEnvelope redact(EventEnvelope event, ExecutionContext requester) {
        if (event == null) {
            return null;
        }
        Map<String, Object> redactedPayload = hasDebugRole(requester)
                ? redactDebugPayload(event.payload())
                : Map.of();
        return new EventEnvelope(
                event.eventId(),
                event.eventName(),
                event.timestampEpochMs(),
                redactedPayload,
                event.correlationId(),
                event.causationId(),
                event.flowName(),
                event.stepIndex(),
                event.tenantId(),
                event.actorId()
        );
    }

    private static Map<String, Object> redactDebugPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            if (key == null || !DEBUG_PAYLOAD_ALLOWLIST.contains(key)) {
                continue;
            }
            redacted.put(key, sanitizeValue(key, entry.getValue()));
        }
        return Map.copyOf(redacted);
    }

    private static Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (SensitiveKeyPolicy.isSensitiveKey(key)) {
            return MASKED;
        }
        if (value instanceof String text) {
            if (SensitiveKeyPolicy.looksLikeSensitiveValue(text)) {
                return MASKED;
            }
            return text;
        }
        return value;
    }

    private static boolean hasDebugRole(ExecutionContext requester) {
        return requester != null && (requester.hasRole("DEBUG") || requester.hasRole("ADMIN"));
    }
}
