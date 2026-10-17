package com.finalexec.tasks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PROC-2: resolves {@code $saved.recipientUsername}-style references against a flow instance's
 * persisted state. Same lookup rules as {@code KernelRunner.resolveReferenceStrict} (which is
 * private): strip a leading {@code $}, whole-key lookup first, then split at the FIRST '.' and walk
 * Maps. Deliberately differs in ONE way: an unresolved reference is {@code null}, never the literal
 * reference string ({@code KernelRunner.resolveReference} falls back to the literal) -- otherwise a
 * task assigned to {@code $saved.recipientUsername} would be answerable by a user literally named so.
 */
final class StateRefs {

    private StateRefs() {
    }

    static Object resolve(Map<String, Object> state, String reference) {
        if (state == null || reference == null) {
            return null;
        }
        String ref = reference.trim();
        if (ref.startsWith("$")) {
            ref = ref.substring(1);
        }
        if (ref.isBlank()) {
            return null;
        }
        if (state.containsKey(ref)) {
            return state.get(ref);
        }
        int separator = ref.indexOf('.');
        if (separator <= 0) {
            return null;
        }
        Object current = state.get(ref.substring(0, separator));
        for (String segment : ref.substring(separator + 1).split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    /** Deepest nesting {@link #flatten} expands: {@code saved.data.code} is three segments (concept records are
     *  {@code {conceptName, id, tenantId, data:{...}}} in flow state). */
    private static final int MAX_DEPTH = 3;

    /** Every {@code a.b.c} dotted path (up to three segments) mapped to its value, for {@code ${a.b.c}} templates. */
    static Map<String, Object> flatten(Map<String, Object> state) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (state != null) {
            expand("", state, 1, out);
        }
        return out;
    }

    private static void expand(String prefix, Map<?, ?> map, int depth, Map<String, Object> out) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix + entry.getKey();
            out.put(key, entry.getValue());
            if (depth < MAX_DEPTH && entry.getValue() instanceof Map<?, ?> child) {
                expand(key + ".", child, depth + 1, out);
            }
        }
    }
}
