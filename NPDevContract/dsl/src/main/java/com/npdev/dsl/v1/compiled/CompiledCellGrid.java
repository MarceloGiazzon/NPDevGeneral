package com.npdev.dsl.v1.compiled;

/** Compiled form of {@link com.npdev.dsl.v1.ast.CellGridAst}: a render "cellGrid" region's board config. */
public record CompiledCellGrid(
        String rowField,
        String colField,
        String valueField,
        String rowsField,
        String colsField,
        Integer defaultRows,
        Integer defaultCols,
        String layout,
        String paletteQuery,
        String paletteLabelField,
        String paletteImageField,
        String paletteColorField
) {
}
