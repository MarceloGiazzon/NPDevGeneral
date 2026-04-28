package com.npdev.kernel.capability;

import com.npdev.kernel.ports.JsonCodec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class CapabilityPolicyOverrides {
    private final Map<String, Map<String, CapabilityPolicyOverride>> overrides;

    public CapabilityPolicyOverrides(Map<String, Map<String, CapabilityPolicyOverride>> overrides) {
        this.overrides = normalize(overrides);
    }

    public static CapabilityPolicyOverrides empty() {
        return new CapabilityPolicyOverrides(Map.of());
    }

    public Optional<CapabilityPolicyOverride> find(String capabilityName, String operationName) {
        String capabilityKey = normalizeKey(capabilityName);
        String operationKey = normalizeKey(operationName);
        if (capabilityKey == null || operationKey == null) {
            return Optional.empty();
        }
        Map<String, CapabilityPolicyOverride> byOperation = overrides.get(capabilityKey);
        if (byOperation == null || byOperation.isEmpty()) {
            return Optional.empty();
        }
        CapabilityPolicyOverride direct = byOperation.get(operationKey);
        if (direct != null) {
            return Optional.of(direct);
        }
        CapabilityPolicyOverride wildcard = byOperation.get("*");
        return Optional.ofNullable(wildcard);
    }

    public static CapabilityPolicyOverrides fromJson(
            String rawJson,
            JsonCodec jsonCodec
    ) {
        if (rawJson == null || rawJson.isBlank()) {
            return empty();
        }
        Objects.requireNonNull(jsonCodec, "jsonCodec");
        Object decoded = jsonCodec.fromJsonToObject(rawJson);
        if (!(decoded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Capability policy overrides must be a JSON object");
        }
        Map<String, Map<String, CapabilityPolicyOverride>> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> capabilityEntry : root.entrySet()) {
            String capabilityName = normalizeKey(String.valueOf(capabilityEntry.getKey()));
            if (capabilityName == null) {
                continue;
            }
            if (!(capabilityEntry.getValue() instanceof Map<?, ?> operationMap)) {
                continue;
            }
            Map<String, CapabilityPolicyOverride> operations = new LinkedHashMap<>();
            for (Map.Entry<?, ?> operationEntry : operationMap.entrySet()) {
                String operationName = normalizeKey(String.valueOf(operationEntry.getKey()));
                if (operationName == null) {
                    continue;
                }
                if (!(operationEntry.getValue() instanceof Map<?, ?> overrideRaw)) {
                    continue;
                }
                operations.put(operationName, parseOverride(overrideRaw));
            }
            if (!operations.isEmpty()) {
                parsed.put(capabilityName, Map.copyOf(operations));
            }
        }
        return new CapabilityPolicyOverrides(parsed);
    }

    private static CapabilityPolicyOverride parseOverride(Map<?, ?> raw) {
        return new CapabilityPolicyOverride(
                intValue(raw.get("retryMaxAttempts")),
                longValue(raw.get("retryBaseDelayMs")),
                longValue(raw.get("retryMaxDelayMs")),
                longValue(raw.get("timeoutMs")),
                intValue(raw.get("circuitOpenAfterFailures")),
                longValue(raw.get("circuitOpenMs")),
                intValue(raw.get("bulkheadMaxConcurrent")),
                boolValue(raw.get("cacheIdempotencyFailures"))
        );
    }

    private static Map<String, Map<String, CapabilityPolicyOverride>> normalize(
            Map<String, Map<String, CapabilityPolicyOverride>> raw
    ) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, CapabilityPolicyOverride>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, CapabilityPolicyOverride>> capabilityEntry : raw.entrySet()) {
            String capabilityName = normalizeKey(capabilityEntry.getKey());
            if (capabilityName == null) {
                continue;
            }
            Map<String, CapabilityPolicyOverride> operationMap = capabilityEntry.getValue();
            if (operationMap == null || operationMap.isEmpty()) {
                continue;
            }
            Map<String, CapabilityPolicyOverride> normalizedOperations = new LinkedHashMap<>();
            for (Map.Entry<String, CapabilityPolicyOverride> operationEntry : operationMap.entrySet()) {
                String operationName = normalizeKey(operationEntry.getKey());
                CapabilityPolicyOverride override = operationEntry.getValue();
                if (operationName == null || override == null) {
                    continue;
                }
                normalizedOperations.put(operationName, override);
            }
            if (!normalizedOperations.isEmpty()) {
                out.put(capabilityName, Map.copyOf(normalizedOperations));
            }
        }
        return Map.copyOf(out);
    }

    private static String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Boolean boolValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        return null;
    }
}
