package com.npdev.kernel.concepts;

import java.util.List;

/**
 * Move 10 B1 (LC-B1, MOVE10_AI_LOWCODE_PLAN Part B): the adapter-neutral aggregate-query contract,
 * extending {@link ConceptQuery}'s own pattern rather than forking it ("One contract at the port",
 * the plan's own "How" item 1). {@code filters} apply BEFORE grouping (the query's {@code where}),
 * {@code having} applies AFTER aggregation, over the aggregate output names -- the SAME predicate
 * grammar {@link ConceptQueryPredicateCompiler} already compiles {@code where} with, just evaluated
 * post-fetch against the (small, already-computed) aggregate rows rather than pushed to SQL HAVING
 * (having a total of {@code Integer.MAX_VALUE}-scale HAVING groups is not a realistic concern the
 * way an un-aggregated table scan is).
 *
 * <p>{@code limit} here bounds the number of GROUPED OUTPUT ROWS, a materially different thing from
 * {@link ConceptQuery#limit()} (which bounds raw concept rows) -- an aggregate query over a
 * million-row table may still return only a handful of grouped rows.
 */
public record ConceptAggregateQuery(
        List<ConceptQuery.Filter> filters,
        List<GroupByField> groupBy,
        List<AggregateFunction> aggregates,
        List<ConceptQuery.Filter> having,
        List<ConceptQuery.Sort> sorts,
        Integer limit
) {
    public ConceptAggregateQuery {
        filters = filters == null ? List.of() : List.copyOf(filters);
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        aggregates = aggregates == null ? List.of() : List.copyOf(aggregates);
        having = having == null ? List.of() : List.copyOf(having);
        sorts = sorts == null ? List.of() : List.copyOf(sorts);
    }

    /** One {@code groupBy[]} entry -- {@code bucket} null for a plain field grouping, else one of
     *  the closed {@code day}/{@code week}/{@code month}/{@code quarter}/{@code year} granularities. */
    public record GroupByField(String field, String bucket) {
    }

    /** One {@code aggregates[]} entry -- {@code outputName} is the column a chart/table/having
     *  clause binds to; {@code field} is null for {@code count}, which needs none. */
    public record AggregateFunction(String outputName, String fn, String field) {
    }
}
