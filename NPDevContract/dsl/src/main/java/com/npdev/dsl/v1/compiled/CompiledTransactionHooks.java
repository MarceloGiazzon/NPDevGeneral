package com.npdev.dsl.v1.compiled;

/** Compiled form of {@link com.npdev.dsl.v1.ast.TransactionHooksAst}. */
public record CompiledTransactionHooks(
        String onLoad,
        String onFieldChange,
        String beforeAction,
        String onValidate,
        String onCommit
) {
}
