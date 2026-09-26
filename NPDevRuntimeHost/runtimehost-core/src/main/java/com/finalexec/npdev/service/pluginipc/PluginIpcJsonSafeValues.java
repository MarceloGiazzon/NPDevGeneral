package com.finalexec.npdev.service.pluginipc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates the "JSON-safe subset" a plugin IPC frame's args/value/contextState must be restricted to
 * (docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 1): primitives, {@link String},
 * {@code Map<String,?>}, {@code List<?>}, and record types Jackson can round-trip. A raw, arbitrary Java
 * object graph -- today's in-process plugin behavior -- cannot cross the process boundary Model B
 * introduces. Record types are trusted without recursing into their fields: Jackson's own record support
 * is the round-trip guarantee the design calls for, not a hand-rolled field walk here.
 *
 * <p>{@link UUID} is accepted alongside the JSON primitives (REG-217): every uuid/reference-typed concept
 * field is coerced to a native {@code UUID} well before a procedure step ever sees it
 * ({@code DslTypeCoercionSupport.normalizeFieldValue}), so any {@code listConcepts -> mapList ->
 * callCapability} chain that copies an id field would otherwise always fail here. This is not a widening
 * of what crosses the boundary: {@link PluginIpcFrameCodec} already serializes a {@code UUID} to the same
 * JSON string Jackson (and the HTTP layer) would produce for it, so a plugin handler never observes
 * anything other than the string it would have received over JSON regardless.
 *
 * <p>{@link LocalDate}/{@link OffsetDateTime}/{@link Date} are accepted the same way (WMS-14, the same
 * blast radius REG-217's own text already predicted for "any type DslTypeCoercionSupport/the storage
 * layer can produce that a procedure step never stringifies"): a "date"/"datetime" concept field
 * surfaces as one of these depending on the storage layer (WmsOffice's H2Local layer returns a raw
 * {@code java.sql.Date} specifically, a {@link Date} subclass, for a "date" field -- confirmed live by
 * this class's own leaf-path diagnostics below, not assumed), so a {@code listConcepts -> mapList ->
 * callCapability} chain copying a date field hit the identical
 * {@code PLUGIN_EXECUTION_FAILED "invoke.args[n] is not JSON-safe: java.util.ArrayList"} symptom REG-217
 * described, just for a different nested type. Unlike {@link UUID} (which {@link PluginIpcFrameCodec}'s
 * plain {@code ObjectMapper} already serializes natively, no extra module needed), a date/time type is
 * NOT converted by simply accepting it here: {@link #sanitizeForWire} replaces it with its ISO-8601
 * string BEFORE a frame is ever built, because a real {@code java -jar} boot's plugin child process
 * (launched through Spring Boot's {@code PropertiesLauncher} against the packaged fat jar) does not
 * reliably have {@code jackson-datatype-jsr310} resolvable on its restricted classpath even when the jar
 * is physically bundled -- registering that module on the codec's {@code ObjectMapper} was tried first
 * and live-crashed the child process with {@code NoClassDefFoundError} the moment it was needed, a
 * strictly worse failure than the one being fixed. Accepting these types here (in addition to
 * sanitizing them) keeps {@code isJsonSafe} correct as a general predicate and keeps the direct unit
 * tests below meaningful without requiring every caller to sanitize first.
 *
 * <p>{@code requireJsonSafe} walks into the offending {@code Map}/{@code List} and reports the exact
 * leaf path and its runtime class (WMS-14), instead of just the outer container's class -- REG-217
 * itself flagged this as "a secondary diagnostics gap that made this look like a data-shape problem at
 * first glance rather than a missing-type-case problem" and left it unfixed; a second, different-typed
 * instance of the same bug class (this one) hit the identical wall, and this exact diagnostic is what
 * identified the real leaf type ({@code java.sql.Date}, not the {@code LocalDate} guess tried first).
 */
public final class PluginIpcJsonSafeValues {

    private PluginIpcJsonSafeValues() {
    }

    public static boolean isJsonSafe(Object value) {
        return findFirstUnsafe("$", value) == null;
    }

    public static void requireJsonSafe(String label, Object value) {
        UnsafeLeaf unsafe = findFirstUnsafe(label, value);
        if (unsafe != null) {
            throw new NotJsonSafeException(unsafe.path(), unsafe.value());
        }
    }

    public static void requireJsonSafeArgs(String label, List<Object> args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.size(); i++) {
            requireJsonSafe(label + "[" + i + "]", args.get(i));
        }
    }

    /**
     * Recursively replaces a date/time value ({@link LocalDate}, {@link OffsetDateTime}, or any
     * {@link Date} -- including its {@code java.sql.Date}/{@code java.sql.Timestamp}/{@code
     * java.sql.Time} subclasses) with its ISO-8601 {@code toString()}, leaving every other value
     * (including {@link UUID}, already natively Jackson-safe) untouched. Every {@code Map}/{@code List}
     * is walked and rebuilt so a nested date deep inside a {@code listConcepts -> mapList} result is
     * caught too. Call this BEFORE {@link #requireJsonSafe}/{@link #requireJsonSafeArgs} and use the
     * RETURNED value to build the wire frame -- the original is never mutated in place.
     */
    public static Object sanitizeForWire(Object value) {
        if (value instanceof LocalDate || value instanceof OffsetDateTime || value instanceof Date) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sanitized.put(String.valueOf(entry.getKey()), sanitizeForWire(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>(list.size());
            for (Object element : list) {
                sanitized.add(sanitizeForWire(element));
            }
            return sanitized;
        }
        return value;
    }

    /** {@link #sanitizeForWire} applied to each element of an args list, preserving list order. */
    public static List<Object> sanitizeArgsForWire(List<Object> args) {
        if (args == null) {
            return null;
        }
        List<Object> sanitized = new ArrayList<>(args.size());
        for (Object arg : args) {
            sanitized.add(sanitizeForWire(arg));
        }
        return sanitized;
    }

    private static boolean isLeafSafe(Object value) {
        return value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Number
                || value instanceof UUID
                || value instanceof LocalDate
                || value instanceof OffsetDateTime
                || value instanceof Date
                || value instanceof Record;
    }

    /** The first offending leaf found (depth-first), or {@code null} if every leaf is JSON-safe. */
    private static UnsafeLeaf findFirstUnsafe(String path, Object value) {
        if (isLeafSafe(value)) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    return new UnsafeLeaf(path + ".<non-string-key>", entry.getKey());
                }
                UnsafeLeaf nested = findFirstUnsafe(path + "." + key, entry.getValue());
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                UnsafeLeaf nested = findFirstUnsafe(path + "[" + i + "]", list.get(i));
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        return new UnsafeLeaf(path, value);
    }

    private record UnsafeLeaf(String path, Object value) {
    }

    public static final class NotJsonSafeException extends RuntimeException {
        public NotJsonSafeException(String label, Object value) {
            super("Plugin IPC value '" + label + "' is not JSON-safe: "
                    + (value == null ? "null" : value.getClass().getName()));
        }
    }
}
