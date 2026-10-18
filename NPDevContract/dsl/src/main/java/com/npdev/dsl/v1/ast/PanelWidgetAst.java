package com.npdev.dsl.v1.ast;

/**
 * F6-2 / F6-3: one declarative widget of a {@code console} or {@code dashboard} panel layout, computed
 * client-side over the panel's own data sources. {@code kind} is {@code tile} (one aggregate number),
 * {@code chart} (an aggregate per {@code groupBy} drawn as {@code bar}, {@code line} or {@code donut}),
 * {@code actions} (the panel's action bar), {@code history} (this session's action runs) or
 * {@code table} (a data source's rows). {@code filterField}/{@code filterEquals} optionally restrict
 * the rows a tile or chart aggregates; {@code span} is its width in a 4-column grid.
 */
public record PanelWidgetAst(
        String kind,
        String title,
        String dataSource,
        String aggregate,
        String field,
        String groupBy,
        String chart,
        String filterField,
        String filterEquals,
        Integer span
) {
}
