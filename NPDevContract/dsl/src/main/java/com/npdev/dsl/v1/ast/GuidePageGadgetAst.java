package com.npdev.dsl.v1.ast;

/**
 * Move 10 B2 (LC-B2, MOVE10_AI_LOWCODE_PLAN Part B): {@code query}/{@code x}/{@code y}/
 * {@code series} bind a chart-type gadget ({@code kpi}/{@code bar}/{@code line}/{@code table}) to
 * a named {@link QueryAst} -- null for the pre-existing rail gadget types
 * ({@code recent-items}/{@code context-info}/{@code page-fragment}), which need none.
 * {@code url} (UI-2) is used only by {@code page-fragment}: a same-origin absolute path.
 */
public record GuidePageGadgetAst(
        String name,
        String type,
        String title,
        String query,
        String x,
        String y,
        String series,
        String url
) {
}
