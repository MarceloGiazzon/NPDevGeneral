package com.npdev.dsl.v1.compiled;

import java.util.List;

/** Compiled form of {@link com.npdev.dsl.v1.ast.UiStateControlAst}. */
public record CompiledUiStateControl(
        String name,
        String label,
        List<String> values,
        String defaultValue
) {
    public CompiledUiStateControl {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
