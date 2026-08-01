package com.npdev.dsl.v1.compiled;

import java.util.List;

/** Compiled form of {@link com.npdev.dsl.v1.ast.WorkbenchBandPickerAst}. */
public record CompiledWorkbenchBandPicker(String panel, String label, List<String> columns, String filter, boolean multiSelect) {
    public CompiledWorkbenchBandPicker(String panel, String label, List<String> columns) {
        this(panel, label, columns, null, false);
    }
}
