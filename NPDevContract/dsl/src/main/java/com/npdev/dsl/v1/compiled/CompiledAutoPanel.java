package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/**
 * Compiled form of a declared AutoPanel (ADR-0005). Surface override blocks are
 * nullable: a null block means "derive entirely from the concept" at expansion.
 * See {@link com.npdev.dsl.v1.ast.AutoPanelAst}.
 */
public record CompiledAutoPanel(
        String name,
        String concept,
        String aggregate,
        String route,
        List<String> surfaces,
        CompiledAutoPanelSurface selection,
        CompiledAutoPanelSurface detail,
        CompiledAutoPanelSurface transaction,
        CompiledAutoPanelSurface prompt,
        Map<String, Object> metadata
) {
    public CompiledAutoPanel {
        surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
