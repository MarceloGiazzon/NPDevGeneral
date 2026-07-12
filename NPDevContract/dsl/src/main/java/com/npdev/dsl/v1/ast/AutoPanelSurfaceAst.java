package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

/**
 * Optional per-surface overrides for an {@link AutoPanelAst}. Any unset value is
 * derived from the bound concept during expansion (see ADR-0005). Not every field
 * is meaningful for every surface (e.g. {@code labelField} is a Prompt concern);
 * the expander reads what it needs and ignores the rest.
 */
public record AutoPanelSurfaceAst(
        List<String> filters,
        List<String> columns,
        List<String> fields,
        List<AutoPanelComputedAst> computed,
        String labelField,
        Map<String, Object> metadata
) {
    public AutoPanelSurfaceAst {
        filters = filters == null ? List.of() : List.copyOf(filters);
        columns = columns == null ? List.of() : List.copyOf(columns);
        fields = fields == null ? List.of() : List.copyOf(fields);
        computed = computed == null ? List.of() : List.copyOf(computed);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
