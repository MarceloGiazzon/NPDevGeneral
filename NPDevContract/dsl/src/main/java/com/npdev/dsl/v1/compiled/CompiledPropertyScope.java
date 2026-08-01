package com.npdev.dsl.v1.compiled;

/**
 * Wave 6 (RC-A1): compiled form of {@link com.npdev.dsl.v1.ast.PropertyScopeAst}. See that
 * class's javadoc for the resolution-order and {@code from}-grammar contract this mirrors exactly.
 */
public record CompiledPropertyScope(String name, String from) {
    public CompiledPropertyScope {
        from = from == null || from.isBlank() ? null : from.trim();
    }
}
