package com.npdev.runtime.support.crud.condition;

import java.util.Map;
import java.util.Objects;

import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.mapWithStringKeys;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.readMapValue;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.readPayloadValue;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): the small hand-rolled boolean/equality
 * condition grammar shared by declarative orchestrationRules {@code condition} strings and
 * lifecycle state-machine transition guards -- both are a token (a {@code $event.}/{@code $payload.}
 * /{@code $current.}-prefixed path, a literal, or a bare payload field name) compared with
 * {@code ==}/{@code !=}, or coerced to a boolean on its own.
 */
public final class ConditionValueSupport {

    private ConditionValueSupport() {
    }

    public static Object resolveConditionValue(String rawToken, Map<String, Object> eventPayload) {
        if (rawToken == null) {
            return null;
        }
        String token = rawToken.trim();
        if (token.isEmpty()) {
            return null;
        }
        if ("$event".equals(token)) {
            return eventPayload;
        }
        if (token.startsWith("$event.")) {
            return readPathValue(eventPayload, token.substring("$event.".length()));
        }
        if ((token.startsWith("\"") && token.endsWith("\""))
                || (token.startsWith("'") && token.endsWith("'"))) {
            return token.length() >= 2 ? token.substring(1, token.length() - 1) : "";
        }
        if ("null".equalsIgnoreCase(token)) {
            return null;
        }
        if ("true".equalsIgnoreCase(token)) {
            return true;
        }
        if ("false".equalsIgnoreCase(token)) {
            return false;
        }
        if (token.matches("-?\\d+")) {
            try {
                return Long.parseLong(token);
            } catch (NumberFormatException ignored) {
                // Fall through to direct payload lookup.
            }
        }
        if (token.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(token);
            } catch (NumberFormatException ignored) {
                // Fall through to direct payload lookup.
            }
        }
        Object direct = readPayloadValue(eventPayload, token);
        if (direct != null) {
            return direct;
        }
        return null;
    }

    public static boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue()) == 0;
        }
        if (left instanceof Boolean leftBool && right instanceof Boolean rightBool) {
            return leftBool.equals(rightBool);
        }
        return Objects.equals(left, right);
    }

    public static boolean asBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        if (value instanceof String text) {
            String normalized = text.trim();
            if (normalized.isEmpty()) {
                return false;
            }
            if ("true".equalsIgnoreCase(normalized)) {
                return true;
            }
            if ("false".equalsIgnoreCase(normalized)) {
                return false;
            }
            return true;
        }
        return true;
    }

    public static Object readPathValue(Map<String, Object> root, String path) {
        if (root == null || root.isEmpty() || path == null || path.isBlank()) {
            return null;
        }
        Object current = root;
        String[] segments = path.split("\\.");
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                return null;
            }
            if (!(current instanceof Map<?, ?> rawMap)) {
                return null;
            }
            current = readMapValue(mapWithStringKeys(rawMap), segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    public static Object resolveStateMachineGuardValue(
            String rawToken,
            Map<String, Object> payload,
            String previousStatus,
            String nextStatus
    ) {
        if (rawToken == null) {
            return null;
        }
        String token = rawToken.trim();
        if (token.isEmpty()) {
            return null;
        }
        if ("$payload".equals(token) || "$current".equals(token)) {
            return payload;
        }
        if (token.startsWith("$payload.")) {
            return readPathValue(payload, token.substring("$payload.".length()));
        }
        if (token.startsWith("$current.")) {
            return readPathValue(payload, token.substring("$current.".length()));
        }
        if ("$next".equals(token)) {
            return nextStatus;
        }
        if ("$previous".equals(token)) {
            return previousStatus;
        }
        if ((token.startsWith("\"") && token.endsWith("\""))
                || (token.startsWith("'") && token.endsWith("'"))) {
            return token.length() >= 2 ? token.substring(1, token.length() - 1) : "";
        }
        if ("null".equalsIgnoreCase(token)) {
            return null;
        }
        if ("true".equalsIgnoreCase(token)) {
            return true;
        }
        if ("false".equalsIgnoreCase(token)) {
            return false;
        }
        if (token.matches("-?\\d+")) {
            try {
                return Long.parseLong(token);
            } catch (NumberFormatException ignored) {
                // Fall through to direct payload lookup.
            }
        }
        if (token.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(token);
            } catch (NumberFormatException ignored) {
                // Fall through to direct payload lookup.
            }
        }
        Object direct = readPayloadValue(payload, token);
        if (direct != null) {
            return direct;
        }
        if ("previousStatus".equals(token)) {
            return previousStatus;
        }
        if ("nextStatus".equals(token)) {
            return nextStatus;
        }
        return null;
    }
}
