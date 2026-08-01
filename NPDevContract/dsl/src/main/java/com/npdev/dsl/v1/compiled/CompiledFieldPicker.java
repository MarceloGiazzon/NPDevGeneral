package com.npdev.dsl.v1.compiled;

/** Compiled form of {@link com.npdev.dsl.v1.ast.FieldPickerAst}. */
public record CompiledFieldPicker(String filter, boolean multiSelect) {
}
