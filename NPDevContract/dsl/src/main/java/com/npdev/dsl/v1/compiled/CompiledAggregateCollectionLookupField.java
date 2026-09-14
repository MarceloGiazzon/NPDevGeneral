package com.npdev.dsl.v1.compiled;

/**
 * Compiled form of a query-sourced per-row lookup field. See
 * {@link com.npdev.dsl.v1.ast.AggregateCollectionLookupFieldAst} (Session 1,
 * NPDEV_MEGA_ROADMAP.md 2026-09-14).
 */
public record CompiledAggregateCollectionLookupField(
        String name,
        String query,
        String joinField,
        String valueField
) {
    public CompiledAggregateCollectionLookupField {
        name = name == null || name.isBlank() ? null : name.trim();
        query = query == null || query.isBlank() ? null : query.trim();
        joinField = joinField == null || joinField.isBlank() ? null : joinField.trim();
        valueField = valueField == null || valueField.isBlank() ? null : valueField.trim();
    }
}
