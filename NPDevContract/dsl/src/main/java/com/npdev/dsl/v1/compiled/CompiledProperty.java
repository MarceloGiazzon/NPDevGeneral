package com.npdev.dsl.v1.compiled;

import java.util.List;

/**
 * Wave 6 (RC-A1): compiled form of {@link com.npdev.dsl.v1.ast.PropertyAst}. See that class's
 * javadoc for the type/default/settableAt/securityRelevant contract this mirrors exactly.
 */
public record CompiledProperty(
        String name,
        String type,
        Object defaultValue,
        List<String> settableAt,
        String label,
        boolean securityRelevant
) {
    public CompiledProperty {
        settableAt = settableAt == null ? List.of() : List.copyOf(settableAt);
    }
}
