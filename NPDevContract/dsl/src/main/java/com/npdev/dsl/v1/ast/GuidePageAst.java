package com.npdev.dsl.v1.ast;

import java.util.List;

public record GuidePageAst(
        String name,
        boolean isDefault,
        GuidePageRegionsAst regions,
        GuidePageThemeAst theme,
        List<GuidePageGadgetAst> gadgets
) {
    public GuidePageAst {
        gadgets = gadgets == null ? List.of() : List.copyOf(gadgets);
    }
}
