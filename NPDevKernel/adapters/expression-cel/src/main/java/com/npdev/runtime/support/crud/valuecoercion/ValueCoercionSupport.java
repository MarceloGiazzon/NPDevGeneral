package com.npdev.runtime.support.crud.valuecoercion;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.OBJECT_MAPPER;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.normalize;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.findField;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): the generic scalar-value coercion
 * helpers used throughout runtime CRUD/bond/validation processing -- payload lookups tolerant of
 * key casing, {@code Object -> boxed-type} conversions for reflective setter/field writes, and the
 * primitive {@code toXxx(Object)} conversions everything else is built from.
 */
public final class ValueCoercionSupport {

    private ValueCoercionSupport() {
    }

    public static Object readPayloadValue(Map<String, Object> payload, String fieldName) {
        if (payload == null || payload.isEmpty() || fieldName == null) {
            return null;
        }
        if (payload.containsKey(fieldName)) {
            return payload.get(fieldName);
        }
        String normalized = normalize(fieldName);
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getKey() != null && normalize(entry.getKey()).equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static Integer toInteger(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String raw) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Long toLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String raw) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Double toDouble(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String raw) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static java.math.BigDecimal toBigDecimal(Object value) {
        if (value instanceof java.math.BigDecimal bd) {
            return bd;
        }
        if (value instanceof java.math.BigInteger bi) {
            return new java.math.BigDecimal(bi);
        }
        if (value instanceof Number number) {
            return new java.math.BigDecimal(number.toString());
        }
        if (value instanceof String raw) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return new java.math.BigDecimal(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String raw) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            if ("true".equalsIgnoreCase(trimmed)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    public static UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String raw) {
            try {
                return UUID.fromString(raw.trim());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    public static OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (value instanceof java.time.Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        if (value instanceof String raw) {
            String candidate = raw.trim();
            if (candidate.isEmpty()) {
                return null;
            }
            try {
                java.time.temporal.TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(
                        candidate,
                        OffsetDateTime::from,
                        LocalDateTime::from
                );
                if (parsed instanceof OffsetDateTime offsetDateTime) {
                    return offsetDateTime;
                }
                if (parsed instanceof LocalDateTime localDateTime) {
                    return localDateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
                }
                return null;
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Timestamp toTimestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    public static String normalizeType(String dslType) {
        if (dslType == null || dslType.isBlank()) {
            return "";
        }
        String normalized = dslType.trim().toLowerCase(Locale.ROOT);
        if ("integer".equals(normalized)) {
            return "int";
        }
        return normalized;
    }

    public static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof String text) {
            try {
                return LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    public static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toLocalDateTime();
        }
        if (value instanceof String text) {
            try {
                return LocalDateTime.parse(text.trim(), DateTimeFormatter.ISO_DATE_TIME);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Class<?> boxType(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return type == null ? Object.class : type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    public static Object coerceWriteValue(Class<?> targetType, Object value) {
        if (value == null) {
            return null;
        }
        Class<?> boxedTargetType = boxType(targetType);
        if (boxedTargetType.isAssignableFrom(value.getClass())) {
            return value;
        }
        if (JsonNode.class.isAssignableFrom(boxedTargetType)) {
            try {
                return OBJECT_MAPPER.valueToTree(value);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (UUID.class.equals(boxedTargetType)) {
            return toUuid(value);
        }
        if (LocalDate.class.equals(boxedTargetType)) {
            return toLocalDate(value);
        }
        if (LocalDateTime.class.equals(boxedTargetType)) {
            return toLocalDateTime(value);
        }
        if (OffsetDateTime.class.equals(boxedTargetType)) {
            return toOffsetDateTime(value);
        }
        if (Long.class.equals(boxedTargetType)) {
            return toLong(value);
        }
        if (Integer.class.equals(boxedTargetType)) {
            return toInteger(value);
        }
        if (Double.class.equals(boxedTargetType)) {
            return toDouble(value);
        }
        if (Float.class.equals(boxedTargetType)) {
            Double d = toDouble(value);
            return d == null ? null : Float.valueOf(d.floatValue());
        }
        if (Short.class.equals(boxedTargetType)) {
            Integer i = toInteger(value);
            return i == null ? null : Short.valueOf(i.shortValue());
        }
        if (Byte.class.equals(boxedTargetType)) {
            Integer i = toInteger(value);
            return i == null ? null : Byte.valueOf(i.byteValue());
        }
        if (Boolean.class.equals(boxedTargetType)) {
            return toBoolean(value);
        }
        if (java.math.BigDecimal.class.equals(boxedTargetType)) {
            return toBigDecimal(value);
        }
        if (java.math.BigInteger.class.equals(boxedTargetType)) {
            java.math.BigDecimal bigDecimal = toBigDecimal(value);
            return bigDecimal == null ? null : bigDecimal.toBigInteger();
        }
        return null;
    }

    public static void writeObjectValue(Object target, String fieldName, Object value) {
        if (target == null || fieldName == null || fieldName.isBlank()) {
            return;
        }

        String suffix = fieldName.substring(0, 1).toUpperCase(Locale.ROOT) + fieldName.substring(1);
        String setterName = "set" + suffix;
        for (java.lang.reflect.Method method : target.getClass().getMethods()) {
            if (!setterName.equals(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            Object coercedValue = coerceWriteValue(parameterType, value);
            if (coercedValue == null && value != null) {
                continue;
            }
            if (coercedValue == null && parameterType.isPrimitive()) {
                continue;
            }
            try {
                method.invoke(target, coercedValue);
                return;
            } catch (Exception ignored) {
                // Fall back to field access below.
            }
        }

        java.lang.reflect.Field field = findField(target.getClass(), fieldName);
        if (field == null) {
            return;
        }
        Object coercedValue = coerceWriteValue(field.getType(), value);
        if (coercedValue == null && value != null) {
            return;
        }
        if (coercedValue == null && field.getType().isPrimitive()) {
            return;
        }
        try {
            field.setAccessible(true);
            field.set(target, coercedValue);
        } catch (Exception ignored) {
            // Ignore write failures; generated services remain the main behavior owner.
        }
    }

    public static boolean referenceValuesEqual(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right) || String.valueOf(left).equalsIgnoreCase(String.valueOf(right));
    }
}
