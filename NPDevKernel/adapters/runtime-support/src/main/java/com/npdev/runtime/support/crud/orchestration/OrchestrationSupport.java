package com.npdev.runtime.support.crud.orchestration;

import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.runtime.support.GeneratedCrudRuntimeSupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.normalizeType;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.readPayloadValue;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toBoolean;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toInteger;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toLong;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toUuid;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): the stateless helpers used while
 * executing declarative orchestrationRules actions -- resolving a capability call's adapter id
 * from its result, building idempotency-claim subject ids, and coercing mapped field values to
 * their declared DSL type.
 */
public final class OrchestrationSupport {

    private OrchestrationSupport() {
    }

    public static String extractResultAdapterId(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Object adapterId = map.get("adapterId");
        if (adapterId == null) {
            return null;
        }
        String text = String.valueOf(adapterId).trim();
        return text.isEmpty() ? null : text;
    }

    public static String resolveOrchestrationSubjectId(Map<String, Object> eventPayload) {
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

    public static Object coerceMappedValue(CompiledField field, Object value) {
        if (field == null) {
            return value;
        }
        String type = normalizeType(field.getDslType());
        return switch (type) {
            case "uuid", "reference" -> {
                UUID uuid = toUuid(value);
                yield uuid == null ? value : uuid;
            }
            case "int" -> {
                Integer parsed = toInteger(value);
                yield parsed == null ? value : parsed;
            }
            case "long" -> {
                Long parsed = toLong(value);
                yield parsed == null ? value : parsed;
            }
            case "boolean" -> {
                Boolean parsed = toBoolean(value);
                yield parsed == null ? value : parsed;
            }
            default -> value;
        };
    }
}
