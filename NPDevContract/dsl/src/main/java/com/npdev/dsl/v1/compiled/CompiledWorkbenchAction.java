package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/** Compiled form of {@link com.npdev.dsl.v1.ast.WorkbenchActionAst}. */
public record CompiledWorkbenchAction(
        String procedure,
        String label,
        List<String> inputFields,
        CompiledWorkbenchActionApplyTo applyTo,
        String afterAction,
        String visibleWhen,
        Map<String, String> labelLocales,
        // Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): see WorkbenchActionAst's javadoc.
        List<String> checkBalances
) {
    public CompiledWorkbenchAction {
        labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
        checkBalances = checkBalances == null ? List.of() : List.copyOf(checkBalances);
    }

    /** Pre-Session-1 7-arg shape, kept so existing call sites keep compiling unchanged with an
     *  empty checkBalances list. */
    public CompiledWorkbenchAction(
            String procedure,
            String label,
            List<String> inputFields,
            CompiledWorkbenchActionApplyTo applyTo,
            String afterAction,
            String visibleWhen,
            Map<String, String> labelLocales
    ) {
        this(procedure, label, inputFields, applyTo, afterAction, visibleWhen, labelLocales, List.of());
    }
}
