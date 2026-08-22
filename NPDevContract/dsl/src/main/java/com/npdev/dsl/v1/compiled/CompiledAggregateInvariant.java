package com.npdev.dsl.v1.compiled;

/**
 * Compiled form of a declared aggregate-scope invariant. See
 * {@link com.npdev.dsl.v1.ast.AggregateInvariantAst} and ADR-0004 / R4.4 (Roadmap Wave 1 2026-08-19).
 */
public record CompiledAggregateInvariant(
        String name,
        String expression,
        String message
) {
    public CompiledAggregateInvariant {
        name = name == null || name.isBlank() ? null : name.trim();
        expression = expression == null || expression.isBlank() ? null : expression.trim();
        message = message == null || message.isBlank() ? null : message.trim();
    }
}
