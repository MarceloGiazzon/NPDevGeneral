package com.npdev.kernel.mvp;

import com.npdev.kernel.events.EventEnvelope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical deterministic serialization helper used by tests and diagnostics.
 */
public final class ExecutionTraceCanonicalJson {

    private ExecutionTraceCanonicalJson() {
    }

    public static String toCanonicalJson(ExecutionTrace trace) {
        StringBuilder out = new StringBuilder(2048);
        appendTrace(out, trace);
        return out.toString();
    }

    private static void appendTrace(StringBuilder out, ExecutionTrace trace) {
        out.append('{');
        appendField(out, "flowName", trace.flowName());
        out.append(',');
        appendField(out, "status", trace.status().name());
        out.append(',');
        out.append("\"steps\":");
        appendSteps(out, trace.steps());
        out.append(',');
        out.append("\"invariantChecks\":");
        appendInvariants(out, trace.invariantChecks());
        out.append(',');
        out.append("\"emittedEvents\":");
        appendEvents(out, trace.emittedEvents());
        out.append(',');
        out.append("\"failure\":");
        appendFailure(out, trace.failure());
        out.append('}');
    }

    private static void appendSteps(StringBuilder out, List<StepExecutionTrace> steps) {
        out.append('[');
        for (int index = 0; index < steps.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            StepExecutionTrace step = steps.get(index);
            out.append('{');
            appendField(out, "stepId", step.stepId());
            out.append(',');
            appendField(out, "stepType", step.stepType());
            out.append(',');
            appendField(out, "status", step.status().name());
            out.append(',');
            appendField(out, "inputSummary", step.inputSummary());
            out.append(',');
            appendField(out, "outputSummary", step.outputSummary());
            out.append('}');
        }
        out.append(']');
    }

    private static void appendInvariants(StringBuilder out, List<InvariantTrace> invariants) {
        out.append('[');
        for (int index = 0; index < invariants.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            InvariantTrace invariant = invariants.get(index);
            out.append('{');
            appendField(out, "invariantRef", invariant.invariantRef());
            out.append(',');
            appendField(out, "stepId", invariant.stepId());
            out.append(',');
            appendField(out, "passed", invariant.passed());
            out.append(',');
            appendField(out, "message", invariant.message());
            out.append('}');
        }
        out.append(']');
    }

    private static void appendEvents(StringBuilder out, List<EventEnvelope> events) {
        out.append('[');
        for (int index = 0; index < events.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            EventEnvelope event = events.get(index);
            out.append('{');
            appendField(out, "eventId", event.eventId());
            out.append(',');
            appendField(out, "eventName", event.eventName());
            out.append(',');
            appendField(out, "timestampEpochMs", event.timestampEpochMs());
            out.append(',');
            appendField(out, "correlationId", event.correlationId());
            out.append(',');
            appendField(out, "causationId", event.causationId());
            out.append(',');
            appendField(out, "flowName", event.flowName());
            out.append(',');
            appendField(out, "stepIndex", event.stepIndex());
            out.append(',');
            out.append("\"payload\":");
            appendAny(out, canonicalizeValue(event.payload()));
            out.append('}');
        }
        out.append(']');
    }

    private static void appendFailure(StringBuilder out, ExecutionTraceFailure failure) {
        if (failure == null) {
            out.append("null");
            return;
        }
        out.append('{');
        appendField(out, "code", failure.code());
        out.append(',');
        appendField(out, "message", failure.message());
        out.append(',');
        appendField(out, "stepId", failure.stepId());
        out.append('}');
    }

    private static void appendField(StringBuilder out, String name, Object value) {
        out.append('"').append(escape(name)).append('"').append(':');
        appendAny(out, value);
    }

    private static void appendAny(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof String text) {
            out.append('"').append(escape(text)).append('"');
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            appendMap(out, map);
            return;
        }
        if (value instanceof List<?> list) {
            appendList(out, list);
            return;
        }
        out.append('"').append(escape(String.valueOf(value))).append('"');
    }

    private static void appendMap(StringBuilder out, Map<?, ?> map) {
        TreeMap<String, Object> ordered = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            ordered.put(String.valueOf(entry.getKey()), canonicalizeValue(entry.getValue()));
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : ordered.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendField(out, entry.getKey(), entry.getValue());
        }
        out.append('}');
    }

    private static void appendList(StringBuilder out, List<?> list) {
        List<Object> ordered = new ArrayList<>(list.size());
        for (Object value : list) {
            ordered.add(canonicalizeValue(value));
        }
        out.append('[');
        for (int index = 0; index < ordered.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            appendAny(out, ordered.get(index));
        }
        out.append(']');
    }

    private static Object canonicalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> ordered = new TreeMap<>(Comparator.naturalOrder());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                ordered.put(String.valueOf(entry.getKey()), canonicalizeValue(entry.getValue()));
            }
            return new LinkedHashMap<>(ordered);
        }
        if (value instanceof List<?> list) {
            List<Object> ordered = new ArrayList<>(list.size());
            for (Object entry : list) {
                ordered.add(canonicalizeValue(entry));
            }
            return ordered;
        }
        return value;
    }

    private static String escape(String value) {
        String escaped = value;
        escaped = escaped.replace("\\", "\\\\");
        escaped = escaped.replace("\"", "\\\"");
        escaped = escaped.replace("\n", "\\n");
        escaped = escaped.replace("\r", "\\r");
        escaped = escaped.replace("\t", "\\t");
        return escaped;
    }
}
