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
    /**
     * REG-260: platform bookkeeping the execution monitor reads back to say COMPENSATING /
     * COMPENSATED -- the in-progress flag and the count of onFailure blocks that ran (see the kernel's
     * CompensationRunner). They hold a boolean and an integer the platform itself wrote, never caller
     * data, so they keep their value for every requester. Masking them like the rest made the monitor
     * report NONE / 0 for an execution whose compensation had run. Only a Boolean/Number value
     * survives: anything else under these names is masked like any other key.
     */
    private static final Set<String> OPERATIONAL_STATE_KEYS = Set.of("__npdev_compensatedSteps__", "__npdev_compensating__");

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
            keysOnly.put(key, operationalValueOrMasked(key, state.get(key)));
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
            } else if (OPERATIONAL_STATE_KEYS.contains(key)) {
                redacted.put(key, operationalValueOrMasked(key, entry.getValue()));
            } else {
                redacted.put(key, MASKED);
            }
        }
        return Map.copyOf(redacted);
    }

    private static Object operationalValueOrMasked(String key, Object value) {
        boolean plainValue = value instanceof Number || value instanceof Boolean;
        return OPERATIONAL_STATE_KEYS.contains(key) && plainValue ? value : MASKED;
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
