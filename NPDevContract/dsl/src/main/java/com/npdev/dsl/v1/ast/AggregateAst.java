package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

/**
 * A declared aggregate: a root concept plus a tree of owned/referenced child
 * collections. This is the model primitive an Aggregate Workbench edits as a
 * single unit (load / draft / validate / commit).
 *
 * <p>See ADR-0004 and docs/architecture/AGGREGATE_WORKBENCH_PLAN.md.
 */
public record AggregateAst(
        String name,
        String root,
        List<AggregateCollectionAst> collections,
        Map<String, Object> metadata
) {
    public AggregateAst {
        collections = collections == null ? List.of() : List.copyOf(collections);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
