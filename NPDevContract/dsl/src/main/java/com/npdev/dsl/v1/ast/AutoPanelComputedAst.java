package com.npdev.dsl.v1.ast;

/**
 * A declared computed column on an AutoPanel surface: a derived column {@code col}
 * whose value is a client-evaluated expression {@code expr} over the row's fields
 * (Tier-A reactivity). F6-5: on an aggregate-bound transaction, {@code collection} names the section
 * ({@code itens}), band ({@code itens.posicoes}) or sub-band whose rows the column is evaluated for, live in
 * the browser, as a read-only extra column. (ADR-0004 §L3). E.g. {@code col:"total", expr:"pos*cxPad + cxAvulsas"}.
 */
public record AutoPanelComputedAst(String col, String expr, String collection) {

    public AutoPanelComputedAst(String col, String expr) {
        this(col, expr, null);
    }
}
