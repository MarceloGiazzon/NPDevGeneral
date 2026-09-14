package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

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
        String visibleWhen,
        Map<String, String> labelLocales,
        // Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): an alternate to `procedure` -- names
        // declared aggregate.balances[] rules to evaluate against the current draft and return as a
        // report, WITHOUT invoking any procedure or persisting anything (an on-demand "Recalcular
        // Saldos" affordance). Exactly one of procedure/checkBalances must be declared -- see
        // PanelValidation#validateWorkbenchActions.
        List<String> checkBalances
) {
    public WorkbenchActionAst {
        inputFields = inputFields == null ? List.of() : List.copyOf(inputFields);
        labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
        checkBalances = checkBalances == null ? List.of() : List.copyOf(checkBalances);
    }

    /** Pre-Session-1 7-arg shape, kept so existing call sites keep compiling unchanged with an
     *  empty checkBalances list. */
    public WorkbenchActionAst(
            String procedure,
            String label,
            List<String> inputFields,
            WorkbenchActionApplyToAst applyTo,
            String afterAction,
            String visibleWhen,
            Map<String, String> labelLocales
    ) {
        this(procedure, label, inputFields, applyTo, afterAction, visibleWhen, labelLocales, List.of());
    }
}
