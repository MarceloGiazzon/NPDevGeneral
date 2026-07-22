package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/**
 * Compiled form of an aggregate child collection (recursive). See
 * {@link com.npdev.dsl.v1.ast.AggregateCollectionAst}.
 */
public record CompiledAggregateCollection(
        String name,
        String concept,
        String via,
        String childField,
        String ownership,
        String orderBy,
        List<CompiledAggregateCollection> collections,
        Map<String, Object> metadata
) {
    public CompiledAggregateCollection {
        collections = collections == null ? List.of() : List.copyOf(collections);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
