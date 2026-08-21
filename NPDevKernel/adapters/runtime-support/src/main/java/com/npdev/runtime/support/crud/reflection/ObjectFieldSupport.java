package com.npdev.runtime.support.crud.reflection;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.OBJECT_MAPPER;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.mapWithStringKeys;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.normalize;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.readMapValue;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.readPayloadValue;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): reading a named value off an arbitrary
 * object -- a {@code Map}, a Jackson-convertible DTO, or (as the last resort) a getter/field via
 * reflection -- plus the lifecycle-snapshot variant that also falls back to the create/update
 * payload when the snapshot itself doesn't have the field yet (e.g. a shadow {@code __id}).
 */
public final class ObjectFieldSupport {

    private ObjectFieldSupport() {
    }

    public static boolean hasMapKey(Map<String, Object> map, String key) {
        if (map == null || map.isEmpty() || key == null) {
            return false;
        }
        if (map.containsKey(key)) {
            return true;
        }
        String normalized = normalize(key);
        for (String candidate : map.keySet()) {
            if (normalize(candidate).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static void copyIfPresent(
            Map<String, Object> target,
            String key,
            Object source,
            Map<String, Object> fallbackPayload
    ) {
        if (target == null || key == null || key.isBlank()) {
            return;
        }
        Object value = readLifecycleValue(source, fallbackPayload, key);
        if (value != null) {
            target.put(key, value);
        }
    }

    public static Object readLifecycleValue(Object source, Map<String, Object> fallbackPayload, String key) {
        Object direct = readObjectValue(source, key);
        if (direct != null) {
            return direct;
        }
        if (fallbackPayload == null || fallbackPayload.isEmpty()) {
            return null;
        }
        Object fallback = readPayloadValue(fallbackPayload, key);
        if (fallback != null) {
            return fallback;
        }
        if ("id".equalsIgnoreCase(key)) {
            return readPayloadValue(fallbackPayload, "__id");
        }
        return null;
    }

    public static Object readObjectValue(Object source, String fieldName) {
        if (source == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        if (source instanceof Map<?, ?> map) {
            return readMapValue(mapWithStringKeys(map), fieldName);
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapped = OBJECT_MAPPER.convertValue(source, Map.class);
            Object mappedValue = readMapValue(mapped, fieldName);
            if (mappedValue != null) {
                return mappedValue;
            }
        } catch (IllegalArgumentException ignored) {
            // Continue with reflective access when mapping fails for proxies.
        }

        String suffix = fieldName.substring(0, 1).toUpperCase(Locale.ROOT) + fieldName.substring(1);
        for (String accessor : List.of("get" + suffix, "is" + suffix)) {
            try {
                java.lang.reflect.Method method = source.getClass().getMethod(accessor);
                return method.invoke(source);
            } catch (Exception ignored) {
                // Keep trying alternatives.
            }
        }

        java.lang.reflect.Field field = findField(source.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(source);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static java.lang.reflect.Field findField(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    public static Long extractVersion(Object source) {
        Object raw = readObjectValue(source, "version");
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static String readLifecycleToken(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public static boolean isLifecycleMissing(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        return false;
    }
}
