package com.npdev.dsl.v1.ast;

import java.util.List;

/**
 * Move 7 W1 (docs/MOVE7_IMPLEMENTATION_SPEC.md): a button on the Aggregate Workbench that invokes a
 * declared {@code procedure} over the current draft. Retires the untyped {@code
 * transaction.metadata.actions} list -- a typo'd key here now fails at schema time instead of
 * silently doing nothing. {@code afterAction} (a procedure receiving {@code {draft, result}} and
 * returning a patched draft) is more general than {@code applyTo} and should be preferred; both may
 * be declared but {@code afterAction} wins when both are present (see {@code AutoPanelExpander}).
 */
public record WorkbenchActionAst(
        String procedure,
        String label,
        List<String> inputFields,
        WorkbenchActionApplyToAst applyTo,
        String afterAction,
        String visibleWhen
) {
    public WorkbenchActionAst {
        inputFields = inputFields == null ? List.of() : List.copyOf(inputFields);
    }
}
