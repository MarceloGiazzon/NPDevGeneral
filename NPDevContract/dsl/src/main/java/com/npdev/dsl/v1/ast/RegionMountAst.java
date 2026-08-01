package com.npdev.dsl.v1.ast;

/**
 * Move 6 Move D (docs/MOVE6_TYPED_SURFACE_PLAN.md §5): how an addressable region of the Aggregate
 * Workbench transaction surface is filled. {@code render} is {@code "generated"} (the platform
 * default; a region absent from {@code transaction.regions} is implicitly this) or {@code
 * "component"}, which mounts an app-owned JS component (named by {@code component}, registered via
 * {@code window.npdev.regions.register}) in place of the generated grid/header for that region.
 * Region ADDRESSES themselves (e.g. {@code "itens"}, {@code "itens.posicoes"}) are derived from the
 * aggregate's own composition tree, never authored -- this record only carries the fill choice for
 * one already-derived address.
 */
public record RegionMountAst(String render, String component) {
    public RegionMountAst {
        render = render == null || render.isBlank() ? "generated" : render.trim();
    }
}
