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
        String onCommit,
        Map<String, Object> metadata,
        String onValidate,
        // R4.4 (Roadmap Wave 1 2026-08-19): declarative cross-collection invariants, evaluated
        // against the whole aggregate draft tree pre-commit -- see AggregateInvariantAst's javadoc.
        List<AggregateInvariantAst> invariants
) {
    public AggregateAst {
        collections = collections == null ? List.of() : List.copyOf(collections);
        onCommit = onCommit == null || onCommit.isBlank() ? null : onCommit.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        onValidate = onValidate == null || onValidate.isBlank() ? null : onValidate.trim();
        invariants = invariants == null ? List.of() : List.copyOf(invariants);
    }

    /** Pre-R4.4 6-arg shape, kept so existing call sites (e.g. hand-built test fixtures outside
     *  this module) that construct an aggregate with no invariants keep compiling unchanged. */
    public AggregateAst(
            String name,
            String root,
            List<AggregateCollectionAst> collections,
            String onCommit,
            Map<String, Object> metadata,
            String onValidate
    ) {
        this(name, root, collections, onCommit, metadata, onValidate, List.of());
    }
}
