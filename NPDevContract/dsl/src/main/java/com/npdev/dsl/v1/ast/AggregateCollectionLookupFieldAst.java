package com.npdev.dsl.v1.ast;

/**
 * Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): a read-only field attached to every row of an
 * {@link AggregateCollectionAst} at load time, sourced from a declared {@link QueryAst}'s results
 * rather than persisted on the row's own concept -- e.g. "the currently-available quantity at this
 * row's location," used to bound an editable field (checklist H1/H2).
 *
 * <p>Deliberately NOT a per-row parameterized query: nothing in the platform's query-execution path
 * ({@code ConceptQueryPredicateCompiler.compileToConceptQueryFilters(query.where())}, the same one
 * a procedure's {@code runQuery} step uses) substitutes a runtime parameter into a query's {@code
 * where} clause -- {@code QueryAst.parameters()} is declared but never bound anywhere. Building
 * real parameter binding to unblock one lookup field would reach into a component every query
 * consumer depends on. Instead: the named query runs ONCE (unparameterized) when the collection
 * loads, and its rows are joined to the collection's rows in memory by {@code joinField}, taking
 * {@code valueField} as the attached value -- see {@code AggregateRuntime.loadCollection}.
 *
 * <p>The attached value is excluded from the commit payload the same way {@code __children} and
 * declared child-collection keys already are (see {@code AggregateRuntime.scalarFields}) -- it is
 * never persisted, only ever loaded.
 */
public record AggregateCollectionLookupFieldAst(
        String name,
        String query,
        String joinField,
        String valueField
) {
    public AggregateCollectionLookupFieldAst {
        name = name == null ? null : name.trim();
        query = query == null ? null : query.trim();
        joinField = joinField == null ? null : joinField.trim();
        valueField = valueField == null ? null : valueField.trim();
    }
}
