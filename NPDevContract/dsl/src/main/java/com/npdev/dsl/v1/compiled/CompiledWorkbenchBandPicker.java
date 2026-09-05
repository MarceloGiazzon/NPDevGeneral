package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/**
 * Compiled form of {@link com.npdev.dsl.v1.ast.WorkbenchBandPickerAst}. {@code filter} is already
 * FULLY RESOLVED (a referenced selector's own {@code filter} AND-composed with any local {@code
 * filter}) and {@code columns} already carries the selector's display columns when {@code
 * selectorRef} resolved -- a downstream emitter never needs to know a selector was involved.
 * {@code orderBy} is empty unless {@code selectorRef} resolved to a real selector
 * (REAL_LIFT_PLAN_2026-09-03 package C2, boundary B16 Step 2, EDIT-18).
 */
public record CompiledWorkbenchBandPicker(
        String panel, String label, List<String> columns, String filter, boolean multiSelect,
        Map<String, String> labelLocales, List<String> orderBy
) {
    public CompiledWorkbenchBandPicker {
        labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
    }

    public CompiledWorkbenchBandPicker(String panel, String label, List<String> columns, String filter, boolean multiSelect,
                                        Map<String, String> labelLocales) {
        this(panel, label, columns, filter, multiSelect, labelLocales, List.of());
    }

    public CompiledWorkbenchBandPicker(String panel, String label, List<String> columns, String filter, boolean multiSelect) {
        this(panel, label, columns, filter, multiSelect, Map.of(), List.of());
    }

    public CompiledWorkbenchBandPicker(String panel, String label, List<String> columns) {
        this(panel, label, columns, null, false, Map.of(), List.of());
    }
}
