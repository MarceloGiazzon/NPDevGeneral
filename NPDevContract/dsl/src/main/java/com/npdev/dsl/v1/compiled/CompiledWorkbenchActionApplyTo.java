package com.npdev.dsl.v1.compiled;

import java.util.Map;

/** Compiled form of {@link com.npdev.dsl.v1.ast.WorkbenchActionApplyToAst}. */
public record CompiledWorkbenchActionApplyTo(String collection, String mode, Map<String, String> map) {
}
