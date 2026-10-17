package com.finalexec.webhook;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * INT-6: body/key helpers for the inbound webhook door, kept out of the template controller so they
 * are unit-testable in runtimehost-core.
 */
public final class InboundWebhookSupport {

    /** Longest idempotency key accepted from a provider; longer is treated as malformed. */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    private static final String HEADER_PREFIX = "$header.";

    private InboundWebhookSupport() {
    }

    /** Parses an {@code application/x-www-form-urlencoded} body into first-value-wins entries. */
    public static Map<String, Object> parseFormBody(byte[] rawBody) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (rawBody == null || rawBody.length == 0) {
            return out;
        }
        String text = new String(rawBody, StandardCharsets.UTF_8);
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            if (!key.isEmpty()) {
                out.putIfAbsent(key, value);
            }
        }
        return out;
    }

    private static String decode(String component) {
        try {
            return URLDecoder.decode(component, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEscape) {
            return component;
        }
    }

    /** Reads a dot-path out of a parsed body's nested maps; null on any missing segment. */
    public static Object readDotPath(Map<String, Object> body, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Object current = body;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * Resolves a model-declared {@code idempotencyKey} spec: {@code $header.<Name>} reads a request
     * header, anything else is a dot-path into the body. Returns null when the spec is blank or the
     * value is absent/blank.
     */
    public static String resolveIdempotencyKey(String spec, Map<String, Object> body,
                                               Function<String, String> headerLookup) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String raw;
        if (spec.startsWith(HEADER_PREFIX)) {
            raw = headerLookup == null ? null : headerLookup.apply(spec.substring(HEADER_PREFIX.length()));
        } else {
            Object value = readDotPath(body, spec);
            raw = value == null ? null : String.valueOf(value);
        }
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
