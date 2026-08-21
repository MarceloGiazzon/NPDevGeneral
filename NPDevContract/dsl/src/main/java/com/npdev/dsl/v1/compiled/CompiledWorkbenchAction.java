package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/** Compiled form of {@link com.npdev.dsl.v1.ast.WorkbenchActionAst}. */
public record CompiledWorkbenchAction(
        String procedure,
        String label,
        List<String> inputFields,
        CompiledWorkbenchActionApplyTo applyTo,
        String afterAction,
        String visibleWhen,
        Map<String, String> labelLocales
) {
    public CompiledWorkbenchAction {
        labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
    }
}
