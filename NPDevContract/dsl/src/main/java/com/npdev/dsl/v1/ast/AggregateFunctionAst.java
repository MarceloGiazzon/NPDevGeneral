package com.npdev.dsl.v1.ast;

/**
 * Move 10 B1 (LC-B1): one {@code query.aggregates[]} entry -- {@code name} is the output column
 * this aggregate is bound as (what a chart/table binds {@code y}/etc. to, see LC-B2), {@code fn} is
 * the closed v1 function set ({@code count}/{@code sum}/{@code avg}/{@code min}/{@code max}), and
 * {@code field} is the concept field the function applies to ({@code null} for {@code count}, which
 * counts rows and needs no field).
 */
public record AggregateFunctionAst(String name, String fn, String field) {
}
