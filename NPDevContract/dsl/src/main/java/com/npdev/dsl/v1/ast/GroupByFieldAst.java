package com.npdev.dsl.v1.ast;

/**
 * Move 10 B1 (LC-B1, MOVE10_AI_LOWCODE_PLAN Part B): one {@code query.groupBy[]} entry -- either a
 * plain field grouping ({@code bucket} null) or a date/datetime field bucketed to a closed
 * granularity ({@code day}/{@code week}/{@code month}/{@code quarter}/{@code year}). The DSL layer
 * accepts either the bare-string authoring shape ({@code "warehouseId"}) or the object shape
 * ({@code {"field": "completedAt", "bucket": "month"}}) -- {@link com.npdev.dsl.v1.parser.JsonModelParser}
 * normalizes both into this one AST shape.
 */
public record GroupByFieldAst(String field, String bucket) {
}
