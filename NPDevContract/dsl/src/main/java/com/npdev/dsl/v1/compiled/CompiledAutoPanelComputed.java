package com.npdev.dsl.v1.compiled;

/** Compiled form of an AutoPanel surface computed column. See {@link com.npdev.dsl.v1.ast.AutoPanelComputedAst}. */
public record CompiledAutoPanelComputed(String col, String expr) {
}
