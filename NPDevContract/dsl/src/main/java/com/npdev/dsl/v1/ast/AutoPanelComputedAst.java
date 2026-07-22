package com.npdev.dsl.v1.ast;

/**
 * A declared computed column on an AutoPanel surface: a derived column {@code col}
 * whose value is a client-evaluated expression {@code expr} over the row's fields
 * (Tier-A reactivity, ADR-0004 §L3). E.g. {@code col:"total", expr:"pos*cxPad + cxAvulsas"}.
 */
public record AutoPanelComputedAst(String col, String expr) {
}
