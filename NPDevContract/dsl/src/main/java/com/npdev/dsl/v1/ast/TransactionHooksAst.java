package com.npdev.dsl.v1.ast;

/**
 * Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md): closed-enum lifecycle hooks for an
 * aggregate-bound AutoPanel's Aggregate Workbench transaction surface. Retires the untyped
 * {@code transaction.metadata.recompute} key ({@code onFieldChange}'s new spelling) and unifies
 * with the aggregate-level {@code onValidate}/{@code onCommit} vocabulary -- declaring them here is
 * an alternate spelling the compiler folds onto the same {@code CompiledAggregate} when the
 * aggregate itself declares neither directly (a direct {@code aggregate.onValidate}/{@code
 * onCommit} always wins). Each value is a procedure name; {@code null} means undeclared.
 */
public record TransactionHooksAst(
        String onLoad,
        String onFieldChange,
        String beforeAction,
        String onValidate,
        String onCommit
) {
}
