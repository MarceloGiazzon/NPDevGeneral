package com.npdev.dsl.v1.ast;

import java.util.List;

/**
 * An owned (or referenced) child collection within an {@link AggregateAst}.
 *
 * <p>Collections are recursive: a collection may itself declare nested
 * {@code collections}, which is what lets an aggregate describe a
 * master-detail-detail composition tree (e.g. Expedicao &#8835; itens &#8835;
 * {origens, destinos}) of arbitrary depth.
 *
 * <p>REG-301 (2026-10-23): {@code via} was retired -- it was accepted and compiled but read by nothing;
 * {@code childField} is the child's link to its parent. ENF-1 (2026-10-24): {@code metadata} was retired the
 * same way -- no runtime surface ever read or served it, and no model declared it.
 */
public record AggregateCollectionAst(
        String name,
        String concept,
        String childField,
        String ownership,
        String orderBy,
        List<AggregateCollectionAst> collections,
        // Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): see AggregateCollectionLookupFieldAst's
        // javadoc -- read-only, query-sourced fields attached to every row of this collection at
        // load time.
        List<AggregateCollectionLookupFieldAst> lookupFields
) {
    public AggregateCollectionAst {
        collections = collections == null ? List.of() : List.copyOf(collections);
        lookupFields = lookupFields == null ? List.of() : List.copyOf(lookupFields);
    }

    /** Shape without lookupFields, kept so existing call sites keep compiling unchanged with an
     *  empty lookupFields list. */
    public AggregateCollectionAst(
            String name,
            String concept,
            String childField,
            String ownership,
            String orderBy,
            List<AggregateCollectionAst> collections
    ) {
        this(name, concept, childField, ownership, orderBy, collections, List.of());
    }
}
