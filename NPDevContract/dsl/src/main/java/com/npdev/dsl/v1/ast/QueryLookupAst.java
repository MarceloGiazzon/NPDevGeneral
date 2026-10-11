package com.npdev.dsl.v1.ast;

import java.util.Map;

/**
 * Friction #5 (Pigmentampas "beers to drink"): one {@code query.lookups[]} entry -- a second,
 * per-key aggregate over ANOTHER concept, merged into every grouped row of the base query as the
 * output column {@code name}. {@code on} maps a field of the lookup concept to a bare
 * {@code groupBy} field of the base query (e.g. {@code {"capId": "capId"}}); {@code where} filters
 * the lookup concept's rows before aggregating. A base row with no matching lookup group gets 0 for
 * {@code count}/{@code sum} and null for {@code avg}/{@code min}/{@code max}.
 */
public record QueryLookupAst(
        String name,
        String concept,
        String fn,
        String field,
        Map<String, String> on,
        String where
) {
    public QueryLookupAst {
        on = on == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(on));
    }
}
