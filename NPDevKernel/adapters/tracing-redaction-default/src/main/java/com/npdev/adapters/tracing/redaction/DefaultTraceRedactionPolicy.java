package com.npdev.adapters.tracing.redaction;

import com.npdev.kernel.CapabilityError;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.TraceRedactionPolicy;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.StepTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DefaultTraceRedactionPolicy implements TraceRedactionPolicy {
    private static final String MASKED = "***";
    private static final Set<String> INFO_ALLOWLIST = Set.of(
            "emittedEventName",
            "emittedEventId",
            "awaitedEventName",
            "awaitedEventFoundEventId",
            "awaitedEventStatus",
            "writtenStateKeys",
            "adapterId",
            "capName",
            "opName",
            "capability",
            "operation",
            "durationMs",
            "attemptCount",
            "retryCount",
            "retryDelayMs",
            "retryMaxDelayMs",
            "timeoutMs",
            "circuitOpenAfterFailures",
            "circuitOpenMs",
            "bulkheadMaxConcurrent",
            "cacheIdempotencyFailures",
            "failureClassification",
            "circuitState",
            "bulkheadState",
            "idempotencyState"
    );
    private static final Set<String> DEBUG_INFO_ALLOWLIST_EXTRA = Set.of(
            "correlationId",
            "causationId",
            "checkpoint",
            "phase",
            "scope",
            "eventName",
            "eventId",
            "entityId",
            "errorCode",
            "errorMessage",
            "stateSnapshot",
            "payloadPreview"
    );

    @Override
    public FlowTrace redact(FlowTrace trace, ExecutionContext requester) {
        if (trace == null) {
            return null;
        }
        boolean debugMode = hasDebugRole(requester);
        List<StepTrace> redactedSteps = new ArrayList<>();
        for (StepTrace step : trace.steps()) {
            redactedSteps.add(redactStep(step, debugMode));
        }
        return new FlowTrace(
                trace.meta(),
                trace.startedAtEpochMs(),
                trace.endedAtEpochMs(),
                trace.outcome(),
                List.copyOf(redactedSteps)
        );
    }

    private StepTrace redactStep(StepTrace step, boolean debugMode) {
        Map<String, Object> redactedInfo = redactInfo(step.info(), debugMode);
        List<InvariantEngine.Violation> redactedViolations = redactViolations(step.invariantViolations());
        CapabilityError redactedCapabilityError = redactCapabilityError(step.capabilityError(), debugMode);
        return new StepTrace(
                step.stepIndex(),
                step.stepName(),
                step.stepType(),
                step.startedAtEpochMs(),
                step.endedAtEpochMs(),
                step.outcome(),
                redactedInfo,
                redactedViolations,
                redactedCapabilityError
        );
    }

    private static Map<String, Object> redactInfo(Map<String, Object> info, boolean debugMode) {
        if (info == null || info.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> redacted = new LinkedHashMap<>();
        Set<String> allowlist = debugMode ? mergeAllowlists(INFO_ALLOWLIST, DEBUG_INFO_ALLOWLIST_EXTRA) : INFO_ALLOWLIST;
        for (Map.Entry<String, Object> entry : info.entrySet()) {
            String key = entry.getKey();
            if (key == null || !allowlist.contains(key)) {
                continue;
            }
            redacted.put(key, sanitizeValue(key, entry.getValue()));
        }
        return Map.copyOf(redacted);
    }

    private static List<InvariantEngine.Violation> redactViolations(List<InvariantEngine.Violation> violations) {
        if (violations == null || violations.isEmpty()) {
            return List.of();
        }
        List<InvariantEngine.Violation> redacted = new ArrayList<>();
        for (InvariantEngine.Violation violation : violations) {
            if (violation == null) {
                continue;
            }
            redacted.add(new InvariantEngine.Violation(
                    violation.code(),
                    sanitizeString("message", violation.message()),
                    violation.invariantRef(),
                    violation.conceptName(),
                    violation.flowName(),
                    violation.stepName(),
                    violation.stepIndex(),
                    Map.of()
            ));
        }
        return List.copyOf(redacted);
    }

    private static CapabilityError redactCapabilityError(CapabilityError error, boolean debugMode) {
        if (error == null) {
            return null;
        }
        return new CapabilityError(
                error.code(),
                sanitizeString("message", error.message()),
                error.kind(),
                debugMode ? sanitizeDetails(error.details()) : Map.of()
        );
    }

    private static Map<String, Object> sanitizeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            sanitized.put(entry.getKey(), sanitizeValue(entry.getKey(), entry.getValue()));
        }
        return Map.copyOf(sanitized);
    }

    private static Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveKey(key)) {
            return MASKED;
        }
        if (value instanceof String text) {
            return sanitizeString(key, text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                sanitized.put(childKey, sanitizeValue(childKey, entry.getValue()));
            }
            return Map.copyOf(sanitized);
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : list) {
                sanitized.add(sanitizeValue(key, item));
            }
            return List.copyOf(sanitized);
        }
        if (value instanceof Set<?> set) {
            Set<Object> sanitized = new LinkedHashSet<>();
            for (Object item : set) {
                sanitized.add(sanitizeValue(key, item));
            }
            return Set.copyOf(sanitized);
        }
        return value;
    }

    private static String sanitizeString(String key, String value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveKey(key)) {
            return MASKED;
        }
        if (value.contains("@")) {
            return MASKED;
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("secret");
    }

    private static boolean hasDebugRole(ExecutionContext requester) {
        return requester != null && (requester.hasRole("DEBUG") || requester.hasRole("ADMIN"));
    }

    private static Set<String> mergeAllowlists(Set<String> base, Set<String> extra) {
        Set<String> merged = new LinkedHashSet<>(base);
        merged.addAll(extra);
        return Set.copyOf(merged);
    }
}
