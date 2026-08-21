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
        String onValidate,
        // R4.4 (Roadmap Wave 1 2026-08-19): declarative cross-collection invariants -- see
        // CompiledAggregateInvariant's javadoc. Evaluated against the aggregate's draft tree
        // (root fields + every named collection) in the same pre-commit slot aggregate.onValidate
        // already runs in (AggregateRuntime.commitInternal, before the root upsert).
        List<CompiledAggregateInvariant> invariants
) {
    public CompiledAggregate {
        collections = collections == null ? List.of() : List.copyOf(collections);
        onCommit = onCommit == null || onCommit.isBlank() ? null : onCommit.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        onValidate = onValidate == null || onValidate.isBlank() ? null : onValidate.trim();
        invariants = invariants == null ? List.of() : List.copyOf(invariants);
    }

    /** Pre-R4.4 6-arg shape, kept so existing call sites outside this module (e.g.
     *  NPDevRuntimeHost's AggregateRuntimeTest, which hand-builds a CompiledAggregate directly)
     *  keep compiling unchanged with an empty invariants list. */
    public CompiledAggregate(
            String name,
            String root,
            List<CompiledAggregateCollection> collections,
            String onCommit,
            Map<String, Object> metadata,
            String onValidate
    ) {
        this(name, root, collections, onCommit, metadata, onValidate, List.of());
    }
}
