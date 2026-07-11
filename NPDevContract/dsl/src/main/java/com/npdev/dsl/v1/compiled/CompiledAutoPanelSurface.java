package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/** Compiled per-surface overrides for a {@link CompiledAutoPanel}. See {@link com.npdev.dsl.v1.ast.AutoPanelSurfaceAst}. */
public record CompiledAutoPanelSurface(
        List<String> filters,
        List<String> columns,
        List<String> fields,
        String labelField,
        Map<String, Object> metadata
) {
    public CompiledAutoPanelSurface {
        filters = filters == null ? List.of() : List.copyOf(filters);
        columns = columns == null ? List.of() : List.copyOf(columns);
        fields = fields == null ? List.of() : List.copyOf(fields);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
