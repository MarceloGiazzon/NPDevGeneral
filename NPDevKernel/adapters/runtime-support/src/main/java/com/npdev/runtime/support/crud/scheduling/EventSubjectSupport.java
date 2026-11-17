package com.npdev.runtime.support.crud.scheduling;

import com.npdev.runtime.support.GeneratedCrudRuntimeSupport;

import java.util.List;
import java.util.Map;

import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.readPayloadValue;

/**
 * Stateless helpers shared by every exactly-once event consumer (the scheduler's de-duplication key and
 * the flow event-trigger service's claim keys): which record an event is "about", and a stable text form
 * of a payload to fall back on. Extracted from the retired orchestration runtime (FORM P3-T5c stage 6b).
 */
public final class EventSubjectSupport {

    private EventSubjectSupport() {
    }

    /** {@code key=value} for the first of recordId / entityId / id / claimId present in the payload, else null. */
    public static String resolveSubjectId(Map<String, Object> eventPayload) {
        if (eventPayload == null || eventPayload.isEmpty()) {
            return null;
        }
        for (String key : List.of("recordId", "entityId", "id", "claimId")) {
            Object value = readPayloadValue(eventPayload, key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return key + "=" + text;
            }
        }
        return null;
    }

    /** {@code payload=<json>} (or {@code payload=empty}) -- the subject of last resort. */
    public static String serializePayloadForIdempotency(Map<String, Object> eventPayload) {
        if (eventPayload == null || eventPayload.isEmpty()) {
            return "payload=empty";
        }
        try {
            return "payload=" + GeneratedCrudRuntimeSupport.OBJECT_MAPPER.writeValueAsString(eventPayload);
        } catch (Exception ignored) {
            return "payload=" + eventPayload.toString();
        }
    }
}
