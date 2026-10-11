package com.npdev.dsl.v1.ast;

/**
 * Friction #5: one {@code query.computed[]} entry -- an output column {@code name} whose value is
 * {@code expression} ({@link com.npdev.dsl.v1.expr.ComputedExpression} grammar) evaluated over the
 * row's groupBy, aggregate, lookup and earlier computed columns, e.g. {@code "needed - owned"}.
 */
public record QueryComputedColumnAst(String name, String expression) {
}
