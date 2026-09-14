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
        Map<String, Object> metadata,
        // Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): see CompiledAggregateCollectionLookupField.
        List<CompiledAggregateCollectionLookupField> lookupFields
) {
    public CompiledAggregateCollection {
        collections = collections == null ? List.of() : List.copyOf(collections);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        lookupFields = lookupFields == null ? List.of() : List.copyOf(lookupFields);
    }

    /** Pre-Session-1 8-arg shape, kept so existing call sites keep compiling unchanged with an
     *  empty lookupFields list. */
    public CompiledAggregateCollection(
            String name,
            String concept,
            String via,
            String childField,
            String ownership,
            String orderBy,
            List<CompiledAggregateCollection> collections,
            Map<String, Object> metadata
    ) {
        this(name, concept, via, childField, ownership, orderBy, collections, metadata, List.of());
    }
}
