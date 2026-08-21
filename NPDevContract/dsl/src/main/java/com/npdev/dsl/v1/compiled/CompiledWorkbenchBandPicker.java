package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/** Compiled form of {@link com.npdev.dsl.v1.ast.WorkbenchBandPickerAst}. */
public record CompiledWorkbenchBandPicker(
        String panel, String label, List<String> columns, String filter, boolean multiSelect,
        Map<String, String> labelLocales
) {
    public CompiledWorkbenchBandPicker {
        labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
    }

    public CompiledWorkbenchBandPicker(String panel, String label, List<String> columns, String filter, boolean multiSelect) {
        this(panel, label, columns, filter, multiSelect, Map.of());
    }

    public CompiledWorkbenchBandPicker(String panel, String label, List<String> columns) {
        this(panel, label, columns, null, false, Map.of());
    }
}
