package com.npdev.dsl.v1.ast;

/**
 * Move 8 D3 (docs/MOVE8_CLOSE_TABLE_SPEC.md, item G6 / Move 6 §B.7): an AutoPanel surface's
 * generated row source can be REPLACED by a procedure instead of the bound concept's table --
 * {@code PanelRuntime}'s pre-existing {@code produce} disposition, until now only reachable from a
 * hand-authored {@code Panel.dataSources[].procedure}, never from a generated AutoPanel surface.
 * {@code null} means undeclared (the surface's generated data source stays concept-bound, unchanged
 * behavior). Distinct from a data source's {@code onRowLoad} (Move 6 Move C), which ENRICHES rows
 * the gateway already produced rather than replacing the row source itself.
 */
public record AutoPanelDataSourceAst(String procedure) {
}
