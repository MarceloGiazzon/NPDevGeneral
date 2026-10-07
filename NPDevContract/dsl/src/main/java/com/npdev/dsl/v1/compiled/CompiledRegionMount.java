package com.npdev.dsl.v1.compiled;

/** Compiled form of {@link com.npdev.dsl.v1.ast.RegionMountAst}; {@code cellGrid} is non-null only for render "cellGrid". */
public record CompiledRegionMount(String render, String component, CompiledCellGrid cellGrid) {
    public CompiledRegionMount(String render, String component) {
        this(render, component, null);
    }
}
