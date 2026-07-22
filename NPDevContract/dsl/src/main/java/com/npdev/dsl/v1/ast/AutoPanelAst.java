package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

/**
 * A declared AutoPanel: a slim, concept- or aggregate-bound pattern the generator
 * expands into a coordinated set of wired surfaces (Selection / Detail / Transaction
 * / Prompt), reading defaults from the bound concept. The middle authoring tier
 * between "default" and a free {@code panel}. See ADR-0005.
 *
 * <p>Exactly one of {@code concept} / {@code aggregate} is bound. Surface override
 * blocks are optional; {@code surfaces} (when set) selects which surfaces to emit.
 */
public record AutoPanelAst(
        String name,
        String concept,
        String aggregate,
        String route,
        List<String> surfaces,
        AutoPanelSurfaceAst selection,
        AutoPanelSurfaceAst detail,
        AutoPanelSurfaceAst transaction,
        AutoPanelSurfaceAst prompt,
        Map<String, Object> metadata
) {
    public AutoPanelAst {
        surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
