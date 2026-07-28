package com.npdev.kernel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T2.B.5 (pure mechanical extraction, see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.5): the
 * {@code _npdev.await} flow-state marshaling keys and helpers, split out of {@link KernelRunner}
 * verbatim -- no behavior change. Used by both the {@code AWAIT_EVENT} step body ({@link
 * AwaitEventStep}) when a flow parks, and by {@link ResumeCoordinator#resolveWaitCriteria} when
 * rehydrating a waiting instance (including after a real process restart -- this is the durable
 * checkpoint shape). This file deliberately stays a flat sibling in {@code com.npdev.kernel}, not a
 * subpackage, for the same reason {@code TableRenamePass}/{@code DesiredSchemaFactory} documented
 * for the 2.B.4 split: several helpers it depends on (e.g. {@link KernelRunner#normalizeRef}) are
 * package-private, and Java sub-packages get no special access to them.
 */
final class FlowStateCodec {

    private FlowStateCodec() {
    }

    static final String AWAIT_STATE_KEY = "_npdev.await";
    static final String AWAIT_FIELD_EVENT_NAME = "awaitEventName";
    static final String AWAIT_FIELD_MATCH_CORRELATION = "matchCorrelation";
    static final String AWAIT_FIELD_PAYLOAD_MATCH_REFS = "payloadMatchRefs";
    static final String AWAIT_FIELD_STEP_INDEX = "stepIndex";
    static final String AWAIT_FIELD_STEP_NAME = "stepName";
    static final String AWAIT_FIELD_AWAIT_REF = "awaitRef";

    static Map<String, Object> buildAwaitState(
            FlowStepDefinition step,
            int stepIndex,
            String awaitRef
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(AWAIT_FIELD_EVENT_NAME, step.getAwaitEventName());
        out.put(AWAIT_FIELD_MATCH_CORRELATION, step.isAwaitMatchCorrelation());
        out.put(AWAIT_FIELD_PAYLOAD_MATCH_REFS, Map.copyOf(step.getAwaitPayloadMatchRefs()));
        out.put(AWAIT_FIELD_STEP_INDEX, Math.max(0, stepIndex));
        out.put(AWAIT_FIELD_STEP_NAME, step.getName());
        out.put(AWAIT_FIELD_AWAIT_REF, normalizeAwaitRef(awaitRef));
        return Map.copyOf(out);
    }

    static Map<String, String> parseStringMap(Object rawValue) {
        if (!(rawValue instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey()).trim();
            String value = String.valueOf(entry.getValue()).trim();
            if (key.isBlank() || value.isBlank()) {
                continue;
            }
            out.put(key, value);
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    static boolean parseBoolean(Object rawValue, boolean defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        if (rawValue instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(rawValue).trim();
        if (text.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(text);
    }

    static int parseInt(Object rawValue, int defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        if (rawValue instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(rawValue).trim();
        if (text.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    static String normalizeAwaitRef(String awaitRef) {
        String normalized = KernelRunner.normalizeRef(awaitRef);
        return normalized.isBlank() ? "awaitedEvent" : normalized;
    }
}
