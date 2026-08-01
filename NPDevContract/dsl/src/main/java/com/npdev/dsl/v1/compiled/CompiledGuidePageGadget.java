package com.npdev.dsl.v1.compiled;

/**
 * Move 10 B2 (LC-B2): {@code query}/{@code x}/{@code y}/{@code series} bind a chart-type gadget
 * ({@code kpi}/{@code bar}/{@code line}/{@code table}) to a named {@link CompiledQuery} -- null
 * for the pre-existing rail gadget types ({@code recent-items}/{@code context-info}/
 * {@code page-fragment}), which need none.
 */
public record CompiledGuidePageGadget(
        String name,
        String type,
        String title,
        String query,
        String x,
        String y,
        String series
) {
}
