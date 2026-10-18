package com.npdev.dsl.v1.compiled;

/** Compiled twin of {@code PanelSlotAst} (F6-1 layoutSlot). */
public record CompiledPanelSlot(String placement, String component, String label) {
}
