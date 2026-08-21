package com.npdev.dsl.v1.compiled;

import java.util.Map;

/** Compiled form of {@link com.npdev.dsl.v1.ast.DerivedFieldAst}. */
public record CompiledDerivedField(
        String name,
        String label,
        String tier,
        String expression,
        String procedure,
        Map<String, String> labelLocales
) {
    public CompiledDerivedField {
        labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
    }

    public CompiledDerivedField(String name, String label, String tier, String expression, String procedure) {
        this(name, label, tier, expression, procedure, Map.of());
    }
}
