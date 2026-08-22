package com.npdev.runtime.support.crud.executioncontext;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): the stateless helpers around request
 * claims and invariant/event payload maps -- normalizing a JWT claims map's {@code roles} entry,
 * coercing an {@code Object} value to a non-blank {@code String}, pulling the shadow {@code __id}
 * out of a payload, and copying a raw payload {@code Map<?, ?>} into an immutable
 * {@code Map<String, Object>}.
 */
public final class PayloadClaimsSupport {

    private PayloadClaimsSupport() {
    }

    public static Set<String> parseRoles(Object rawRoles) {
        if (!(rawRoles instanceof Collection<?> collection) || collection.isEmpty()) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (Object role : collection) {
            String normalized = asNonBlankString(role);
            if (normalized != null) {
                roles.add(normalized.toUpperCase(Locale.ROOT));
            }
        }
        return roles.isEmpty() ? Set.of() : Set.copyOf(roles);
    }

    public static String asNonBlankString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public static UUID extractCurrentId(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return null;
        }
        Object id = map.get("__id");
        if (id instanceof UUID uuid) {
            return uuid;
        }
        return null;
    }

    public static Map<String, Object> toPayloadMap(Object payload, Map<String, Object> fallback) {
        if (!(payload instanceof Map<?, ?> map)) {
            return fallback;
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                typed.put(key, entry.getValue());
            }
        }
        return immutablePayload(typed);
    }

    public static Map<String, Object> immutablePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
