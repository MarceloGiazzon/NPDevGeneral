package com.npdev.dsl.v1.ast;

/**
 * R4.4 (Roadmap Wave 1 2026-08-19): a declarative, cross-collection invariant scoped to a whole
 * {@link AggregateAst} tree -- e.g. {@code lines.all(l => l.qty > 0) && lines.sum(qty) <= totalQty},
 * evaluated against the aggregate's draft tree (root fields plus every named collection) rather
 * than a single concept's payload the way {@code ConceptAst}'s own {@code invariants[]} are.
 *
 * <p>{@code name} is required and must be unique within its aggregate -- it is the rule identifier
 * an API error names when the invariant vetoes a commit (done-when: "the failing rule named in the
 * API error"), not just a description. {@code expression} is required and must be boolean-shaped
 * (see {@link com.npdev.dsl.v1.expr.ComputedExpression#isBooleanShaped(String)}). {@code message} is
 * an optional author-supplied override for the veto message; when absent, the runtime composes one
 * from {@code name} plus the evaluator's own failure detail.
 */
public record AggregateInvariantAst(
        String name,
        String expression,
        String message
) {
    public AggregateInvariantAst {
        name = name == null ? null : name.trim();
        expression = expression == null ? null : expression.trim();
        message = message == null || message.isBlank() ? null : message.trim();
    }
}
