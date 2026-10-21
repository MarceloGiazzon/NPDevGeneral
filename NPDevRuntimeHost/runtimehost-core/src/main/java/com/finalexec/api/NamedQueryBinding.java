package com.finalexec.api;

import com.npdev.dsl.v1.compiled.CompiledProcedureParameter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Binds a named query's declared {@code parameters[]} from request query-string values.
 *
 * <p>REP-1: extracted verbatim from the generated {@code GeneratedQueryController}
 * ({@code GET /api/queries/{name}}, WMS-12) so a {@code kind: list} report document binds its named
 * query's parameters EXACTLY the way the REST endpoint does -- one rule, one place. Free of any
 * servlet dependency (the caller passes a plain lookup function), so it is unit-testable in the
 * hermetic gate.</p>
 *
 * <p>Per X0 an unbound declared parameter is REFUSED rather than defaulted to null or dropped from
 * the filter -- an unfiltered result is never served for a query that declares a bound placeholder.
 * Values are coerced by shape (boolean / integer / decimal / string) so the compiled filter literal
 * matches the stored column type.</p>
 */
public final class NamedQueryBinding {

    private NamedQueryBinding() {
    }

    /**
     * @param lookup returns the raw request value for a parameter name, or {@code null} when absent
     * @throws IllegalArgumentException when a declared parameter has no value (callers map this to 400)
     */
    public static Map<String, Object> bind(
            Function<String, String> lookup, List<CompiledProcedureParameter> parameters) {
        Map<String, Object> bound = new LinkedHashMap<>();
        for (CompiledProcedureParameter parameter : parameters) {
            String raw = lookup.apply(parameter.name());
            if (raw == null) {
                throw new IllegalArgumentException("Query parameter '" + parameter.name()
                        + "' is required (declared in this query's parameters[] but not supplied).");
            }
            bound.put(parameter.name(), coerceLiteral(raw));
        }
        return bound;
    }

    public static Object coerceLiteral(String raw) {
        String trimmed = raw.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            // not an integer -- fall through
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {
            // not a number -- keep the raw string
        }
        return raw;
    }
}
