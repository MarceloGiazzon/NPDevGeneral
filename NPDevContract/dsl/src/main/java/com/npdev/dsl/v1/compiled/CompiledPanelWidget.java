package com.npdev.dsl.v1.compiled;

/** Compiled twin of {@code PanelWidgetAst} (F6-2 / F6-3 console and dashboard widgets). */
public record CompiledPanelWidget(
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
