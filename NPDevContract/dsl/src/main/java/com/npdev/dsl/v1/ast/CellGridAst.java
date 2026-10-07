package com.npdev.dsl.v1.ast;

/**
 * A {@code transaction.regions.<collection>} rendered as a paintable board instead of a row grid
 * ({@code render: "cellGrid"}): each child row is one painted cell at ({@code rowField},
 * {@code colField}) holding {@code valueField}; a cell with no row is empty. The board's size is read
 * from the root's {@code rowsField}/{@code colsField} (falling back to {@code defaultRows}/
 * {@code defaultCols}); {@code layout} is {@code "square"} or {@code "hexOffset"} (odd rows shifted half
 * a cell). The palette is the rows of {@code paletteQuery}: each row's id is the value painted, shown
 * with its {@code paletteImageField} (a file field), {@code paletteColorField} (a #hex) and
 * {@code paletteLabelField}. Edits go through the workbench store like any region, so Save commits the
 * board in the same single aggregate transaction.
 */
public record CellGridAst(
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
    public CellGridAst {
        layout = layout == null || layout.isBlank() ? "square" : layout.trim();
    }
}
