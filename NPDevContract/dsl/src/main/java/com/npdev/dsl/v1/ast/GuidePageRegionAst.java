package com.npdev.dsl.v1.ast;

public record GuidePageRegionAst(
        boolean enabled,
        boolean collapsible,
        boolean defaultCollapsed,
        int width
) {
}
