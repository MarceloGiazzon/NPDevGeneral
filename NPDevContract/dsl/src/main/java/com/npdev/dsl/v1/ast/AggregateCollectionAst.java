package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

/**
 * An owned (or referenced) child collection within an {@link AggregateAst}.
 *
 * <p>Collections are recursive: a collection may itself declare nested
 * {@code collections}, which is what lets an aggregate describe a
 * master-detail-detail composition tree (e.g. Expedicao &#8835; itens &#8835;
 * {origens, destinos}) of arbitrary depth.
 */
public record AggregateCollectionAst(
        String name,
        String concept,
        String via,
        String childField,
        String ownership,
        String orderBy,
        List<AggregateCollectionAst> collections,
        Map<String, Object> metadata
) {
    public AggregateCollectionAst {
        collections = collections == null ? List.of() : List.copyOf(collections);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
