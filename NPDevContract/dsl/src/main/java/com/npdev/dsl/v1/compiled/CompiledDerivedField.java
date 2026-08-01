package com.npdev.dsl.v1.compiled;

/** Compiled form of {@link com.npdev.dsl.v1.ast.DerivedFieldAst}. */
public record CompiledDerivedField(
        String name,
        String label,
        String tier,
        String expression,
        String procedure
) {
}
