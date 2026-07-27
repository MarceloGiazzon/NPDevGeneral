package com.npdev.adapters.tracing.redaction;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.ports.ExecutionRedactionPolicy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class DefaultExecutionRedactionPolicy implements ExecutionRedactionPolicy {
    private static final String MASKED = "***";
    private static final Set<String> DEBUG_STATE_ALLOWLIST = Set.of("entityId", "status", "reason", "type");

    @Override
    public FlowInstance redact(FlowInstance instance, ExecutionContext requester) {
        if (instance == null) {
            return null;
        }
        Map<String, Object> redactedState = hasDebugRole(requester)
                ? redactDebugState(instance.state())
                : redactStateKeysOnly(instance.state());
        return new FlowInstance(
                instance.executionId(),
                instance.flowName(),
                instance.correlationId(),
                instance.tenantId(),
                instance.actorId(),
                instance.currentStepIndex(),
                instance.status(),
                redactedState,
                instance.waitingForEventName(),
                instance.createdAtEpochMs(),
                instance.updatedAtEpochMs()
        );
    }

    private static Map<String, Object> redactStateKeysOnly(Map<String, Object> state) {
        if (state == null || state.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> keysOnly = new LinkedHashMap<>();
        for (String key : state.keySet()) {
            if (key == null || key.isBlank()) {
                continue;
            }
            keysOnly.put(key, MASKED);
        }
        return Map.copyOf(keysOnly);
    }

    private static Map<String, Object> redactDebugState(Map<String, Object> state) {
        if (state == null || state.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            if (DEBUG_STATE_ALLOWLIST.contains(key)) {
                redacted.put(key, sanitizeValue(key, entry.getValue()));
            } else {
                redacted.put(key, MASKED);
            }
        }
        return Map.copyOf(redacted);
    }

    private static Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (SensitiveKeyPolicy.isSensitiveKey(key)) {
            return MASKED;
        }
        if (value instanceof String text) {
            if (SensitiveKeyPolicy.looksLikeSensitiveValue(text)) {
                return MASKED;
            }
            return text;
        }
        return value;
    }

    private static boolean hasDebugRole(ExecutionContext requester) {
        return requester != null && (requester.hasRole("DEBUG") || requester.hasRole("ADMIN"));
    }
}
