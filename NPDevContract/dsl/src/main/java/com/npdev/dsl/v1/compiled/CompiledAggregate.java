package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/**
 * Compiled form of a declared aggregate (root concept + composition tree).
 * See {@link com.npdev.dsl.v1.ast.AggregateAst} and ADR-0004.
 */
public record CompiledAggregate(
        String name,
        String root,
        List<CompiledAggregateCollection> collections,
        String onCommit,
        Map<String, Object> metadata,
        String onValidate
) {
    public CompiledAggregate {
        collections = collections == null ? List.of() : List.copyOf(collections);
        onCommit = onCommit == null || onCommit.isBlank() ? null : onCommit.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        onValidate = onValidate == null || onValidate.isBlank() ? null : onValidate.trim();
    }
}
