package com.npdev.dsl.v1.ast;

/**
 * F6-1 layoutSlot (ADR-0004 L5): one hand-authored region inside a generated panel. {@code component}
 * names an app-owned {@code web/regions/<component>.js}; {@code placement} is {@code above}, {@code below}
 * or {@code replace} relative to the generated body; {@code label} is an optional heading.
 */
public record PanelSlotAst(String placement, String component, String label) {
}
