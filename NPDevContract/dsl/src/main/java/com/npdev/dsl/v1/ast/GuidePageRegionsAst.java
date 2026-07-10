package com.npdev.dsl.v1.ast;

public record GuidePageRegionsAst(
        boolean top,
        GuidePageRegionAst left,
        GuidePageRegionAst right
) {
}
