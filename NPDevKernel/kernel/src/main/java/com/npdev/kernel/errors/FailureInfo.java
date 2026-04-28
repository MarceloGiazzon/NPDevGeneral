package com.npdev.kernel.errors;

import java.util.LinkedHashMap;
import java.util.Map;

public record FailureInfo(
        ErrorKind kind,
        String code,
        String message,
        Map<String, String> details
) {
    private static final int MAX_MESSAGE_LENGTH = 1024;
    private static final int MAX_DETAIL_VALUE_LENGTH = 256;

    public FailureInfo {
        if (kind == null) {
            throw new IllegalArgumentException("kind must be non-null");
        }
        code = normalizeCode(code);
        message = sanitizeMessage(message);
        details = sanitizeDetails(details);
    }

    public static FailureInfo of(
            ErrorKind kind,
            String code,
            String message
    ) {
        return new FailureInfo(kind, code, message, Map.of());
    }

    public static FailureInfo of(
            ErrorKind kind,
            String code,
            String message,
            Map<String, String> details
    ) {
        return new FailureInfo(kind, code, message, details);
    }

    private static String normalizeCode(String value) {
        if (value == null) {
            return FailureCodes.SYSTEM_EXCEPTION;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? FailureCodes.SYSTEM_EXCEPTION : trimmed;
    }

    private static String sanitizeMessage(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= MAX_MESSAGE_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_MESSAGE_LENGTH);
    }

    private static Map<String, String> sanitizeDetails(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            String key = entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (value.length() > MAX_DETAIL_VALUE_LENGTH) {
                value = value.substring(0, MAX_DETAIL_VALUE_LENGTH);
            }
            out.put(key, value);
        }
        return Map.copyOf(out);
    }
}
