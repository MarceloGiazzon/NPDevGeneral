package com.npdev.dsl.v1.compiled;

import java.util.Map;

/** Friction #5: compiled form of {@link com.npdev.dsl.v1.ast.QueryLookupAst}. */
public record CompiledQueryLookup(
        String name,
        String concept,
        String fn,
        String field,
        Map<String, String> on,
        String where
) {
    public CompiledQueryLookup {
        on = on == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(on));
    }
}
