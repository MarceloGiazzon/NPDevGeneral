package com.npdev.dsl.v1.ast;

/**
 * Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md): closed-enum lifecycle hooks for an
 * aggregate-bound AutoPanel's Aggregate Workbench transaction surface. Retires the untyped
 * {@code transaction.metadata.recompute} key ({@code onFieldChange}'s new spelling). The pre-commit and
 * post-commit hooks are NOT here: they are {@code composition.onValidate}/{@code composition.onCommit} on the
 * root concept (a screen-side spelling of the same two hooks was removed, REG-359, because the concept-level one
 * always won). Each value is a procedure name; {@code null} means undeclared.
 */
public record TransactionHooksAst(
        String onLoad,
        String onFieldChange,
        String beforeAction
) {
}
