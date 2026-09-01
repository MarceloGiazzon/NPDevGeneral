package com.finalexec.npdev.service.pluginipc;

import java.util.List;
import java.util.Map;

/**
 * Validates the "JSON-safe subset" a plugin IPC frame's args/value/contextState must be restricted to
 * (docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 1): primitives, {@link String},
 * {@code Map<String,?>}, {@code List<?>}, and record types Jackson can round-trip. A raw, arbitrary Java
 * object graph -- today's in-process plugin behavior -- cannot cross the process boundary Model B
 * introduces. Record types are trusted without recursing into their fields: Jackson's own record support
 * is the round-trip guarantee the design calls for, not a hand-rolled field walk here.
 */
public final class PluginIpcJsonSafeValues {

    private PluginIpcJsonSafeValues() {
    }

    public static boolean isJsonSafe(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Number
                || value instanceof Record) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String) || !isJsonSafe(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (!isJsonSafe(element)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public static void requireJsonSafe(String label, Object value) {
        if (!isJsonSafe(value)) {
            throw new NotJsonSafeException(label, value);
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

    public static final class NotJsonSafeException extends RuntimeException {
        public NotJsonSafeException(String label, Object value) {
            super("Plugin IPC value '" + label + "' is not JSON-safe: "
                    + (value == null ? "null" : value.getClass().getName()));
        }
    }
}
