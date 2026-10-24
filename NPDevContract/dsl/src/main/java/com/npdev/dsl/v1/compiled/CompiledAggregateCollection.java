package com.npdev.dsl.v1.compiled;

import java.util.List;

/**
 * Compiled form of an aggregate child collection (recursive). See
 * {@link com.npdev.dsl.v1.ast.AggregateCollectionAst}. ENF-1 (2026-10-24): {@code metadata} retired -- read by nothing.
 */
public record CompiledAggregateCollection(
        String name,
        String concept,
        String childField,
        String ownership,
        String orderBy,
        List<CompiledAggregateCollection> collections,
        // Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): see CompiledAggregateCollectionLookupField.
        List<CompiledAggregateCollectionLookupField> lookupFields
) {
    public CompiledAggregateCollection {
        collections = collections == null ? List.of() : List.copyOf(collections);
        lookupFields = lookupFields == null ? List.of() : List.copyOf(lookupFields);
    }

    /** Shape without lookupFields, kept so existing call sites keep compiling unchanged with an
     *  empty lookupFields list. */
    public CompiledAggregateCollection(
            String name,
            String concept,
            String childField,
            String ownership,
            String orderBy,
            List<CompiledAggregateCollection> collections
    ) {
        this(name, concept, childField, ownership, orderBy, collections, List.of());
    }
}
