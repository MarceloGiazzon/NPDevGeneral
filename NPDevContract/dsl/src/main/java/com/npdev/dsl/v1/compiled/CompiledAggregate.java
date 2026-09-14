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
        List<CompiledAggregateInvariant> invariants,
        String uid,
        // Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): grouped, role-partitioned balance rules --
        // see CompiledAggregateBalance's javadoc. Evaluated both as a commit-time gate
        // (AggregateRuntime.commit, alongside assertAggregateInvariants) and on demand, without
        // persisting, by a workbenchAction declaring checkBalances instead of procedure.
        List<CompiledAggregateBalance> balances
) {
    public CompiledAggregate {
        collections = collections == null ? List.of() : List.copyOf(collections);
        onCommit = onCommit == null || onCommit.isBlank() ? null : onCommit.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        onValidate = onValidate == null || onValidate.isBlank() ? null : onValidate.trim();
        invariants = invariants == null ? List.of() : List.copyOf(invariants);
        balances = balances == null ? List.of() : List.copyOf(balances);
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
        this(name, root, collections, onCommit, metadata, onValidate, List.of(), null, List.of());
    }

    /** Pre-P2.1 7-arg shape -- uid defaults to null (no stable identity declared). */
    public CompiledAggregate(
            String name,
            String root,
            List<CompiledAggregateCollection> collections,
            String onCommit,
            Map<String, Object> metadata,
            String onValidate,
            List<CompiledAggregateInvariant> invariants
    ) {
        this(name, root, collections, onCommit, metadata, onValidate, invariants, null, List.of());
    }

    /** Pre-Session-1 8-arg shape, kept so existing call sites keep compiling unchanged with an
     *  empty balances list. */
    public CompiledAggregate(
            String name,
            String root,
            List<CompiledAggregateCollection> collections,
            String onCommit,
            Map<String, Object> metadata,
            String onValidate,
            List<CompiledAggregateInvariant> invariants,
            String uid
    ) {
        this(name, root, collections, onCommit, metadata, onValidate, invariants, uid, List.of());
    }
}
