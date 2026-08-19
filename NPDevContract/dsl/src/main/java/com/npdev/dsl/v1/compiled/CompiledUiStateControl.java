package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/** Compiled form of {@link com.npdev.dsl.v1.ast.UiStateControlAst}. */
public record CompiledUiStateControl(
        String name,
        String label,
        List<String> values,
        String defaultValue,
        Map<String, String> labelLocales
) {
    public CompiledUiStateControl {
        values = values == null ? List.of() : List.copyOf(values);
        labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
    }

    public CompiledUiStateControl(String name, String label, List<String> values, String defaultValue) {
        this(name, label, values, defaultValue, Map.of());
    }
}
