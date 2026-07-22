package com.npdev.dsl.v1.compiled;

import java.util.List;

public record CompiledGuidePage(
        String name,
        boolean isDefault,
        CompiledGuidePageRegions regions,
        CompiledGuidePageTheme theme,
        List<CompiledGuidePageGadget> gadgets
) {
    public CompiledGuidePage {
        gadgets = gadgets == null ? List.of() : List.copyOf(gadgets);
    }
}
