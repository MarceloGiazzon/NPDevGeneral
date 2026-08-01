package com.npdev.dsl.v1.compiled;

import java.util.List;

/** Compiled form of {@link com.npdev.dsl.v1.ast.WorkbenchActionAst}. */
public record CompiledWorkbenchAction(
        String procedure,
        String label,
        List<String> inputFields,
        CompiledWorkbenchActionApplyTo applyTo,
        String afterAction,
        String visibleWhen
) {
}
