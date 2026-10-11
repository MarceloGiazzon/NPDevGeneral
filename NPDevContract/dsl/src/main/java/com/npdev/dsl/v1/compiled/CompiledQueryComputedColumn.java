package com.npdev.dsl.v1.compiled;

/** Friction #5: compiled form of {@link com.npdev.dsl.v1.ast.QueryComputedColumnAst}. */
public record CompiledQueryComputedColumn(String name, String expression) {
}
