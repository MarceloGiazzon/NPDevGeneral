package com.npdev.runtime.support.crud.valuecoercion;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.compiled.CompiledField;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.OBJECT_MAPPER;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.normalizeType;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toBigDecimal;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toBoolean;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toInteger;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toLong;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toOffsetDateTime;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toUuid;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): coercing a raw payload value according
 * to a {@code CompiledField}'s (or a bare DSL type string's) declared type -- the DSL-type-aware
 * layer above {@link ValueCoercionSupport}'s scalar {@code toXxx(Object)} conversions.
 */
public final class DslTypeCoercionSupport {

    private DslTypeCoercionSupport() {
    }

    public static Object normalizeFieldValue(CompiledField field, Object rawValue) {
        String dslType = normalizeType(field == null ? null : field.getDslType());
        if ("uuid".equals(dslType) || "reference".equals(dslType)) {
            UUID uuid = toUuid(rawValue);
            return uuid == null ? rawValue : uuid;
        }
        // R4.1 (roadmap): a defaultExpression/derivedExpression computed through ComputedExpression
        // (e.g. "quantity * unitPrice") yields a raw Long/Double, not a BigDecimal -- unlike a
        // client-submitted JSON value, which Jackson already binds straight into the DTO's
        // BigDecimal field before this method ever sees it. Left uncoerced, that Long/Double reached
        // JdbcBusinessConceptStore unchanged and failed to bind against a DECIMAL column (measured
        // live: H2 "NumberFormatException: Character array is missing 'e' notation exponential
        // mark" from a schemaless MERGE). A decimal field's default/derived arithmetic was refused
        // at author time until this same roadmap item widened the validator, so this path was never
        // exercised end-to-end before. toBigDecimal (ValueCoercionSupport) is the existing helper
        // used to convert a raw payload value into a decimal field's DTO/response shape elsewhere.
        if ("decimal".equals(dslType) && !(rawValue instanceof java.math.BigDecimal)) {
            java.math.BigDecimal decimal = toBigDecimal(rawValue);
            return decimal == null ? rawValue : decimal;
        }
        if (!"object".equals(dslType) && !"array".equals(dslType)) {
            return rawValue;
        }
        return toJavaJsonValue(rawValue);
    }

    public static Object normalizeByDslType(String dslType, Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String normalized = normalizeType(dslType);
        return switch (normalized) {
            case "uuid", "reference" -> toUuid(rawValue);
            case "int", "integer" -> toInteger(rawValue);
            case "long" -> toLong(rawValue);
            case "boolean" -> toBoolean(rawValue);
            case "date" -> rawValue instanceof LocalDate ? rawValue : String.valueOf(rawValue).trim();
            case "datetime" -> {
                OffsetDateTime dateTime = toOffsetDateTime(rawValue);
                yield dateTime == null ? rawValue : dateTime;
            }
            default -> rawValue instanceof String text ? text.trim() : rawValue;
        };
    }

    public static Object toJavaJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode jsonNode) {
            return OBJECT_MAPPER.convertValue(jsonNode, Object.class);
        }
        if (value instanceof String text) {
            String candidate = text.trim();
            if (candidate.startsWith("{") || candidate.startsWith("[")) {
                try {
                    return OBJECT_MAPPER.readValue(candidate, Object.class);
                } catch (Exception ignored) {
                    return value;
                }
            }
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    converted.put(key, toJavaJsonValue(entry.getValue()));
                }
            }
            return converted;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> converted = new ArrayList<>();
            for (Object item : collection) {
                converted.add(toJavaJsonValue(item));
            }
            return converted;
        }
        return value;
    }
}
