package com.npdev.kernel.concepts;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * LNCH-5: the adapter-neutral query contract for a tenant-scoped concept list. It carries a filter
 * tree (AND-combined single-field comparisons -- the same {@code ==}/{@code !=}/comparison shapes the
 * legacy {@link ConceptQueryFilterSupport} understood, extended with ordered comparisons), sort keys,
 * and an offset/limit page window. A {@link ConceptStore} implements it once at the port: the JDBC
 * adapters compile it to parameterized SQL (LIMIT/OFFSET pushed to the database), while the in-memory
 * adapter evaluates the identical contract over the tenant's records via {@link ConceptQueryEngine}.
 *
 * <p>The point is the contract, not the engine: a generated grid that filters/sorts/pages a 100k-row
 * concept must not stream the whole table through the JVM and browser, so the store -- not a
 * post-fetch {@code .stream().filter()} -- is responsible for narrowing the result set.
 */
public record ConceptQuery(
        List<Filter> filters,
        List<Sort> sorts,
        int offset,
        int limit
) {
    /** Default page size when a caller does not specify a limit. */
    public static final int DEFAULT_LIMIT = 50;
    /** Hard ceiling so a single page can never request an unbounded scan. */
    public static final int MAX_LIMIT = 1000;

    public ConceptQuery {
        filters = filters == null ? List.of() : List.copyOf(filters);
        sorts = sorts == null ? List.of() : List.copyOf(sorts);
        if (offset < 0) {
            offset = 0;
        }
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        } else if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
    }

    /** First page of {@link #DEFAULT_LIMIT} rows, no filter, no sort. */
    public static ConceptQuery firstPage() {
        return new ConceptQuery(List.of(), List.of(), 0, DEFAULT_LIMIT);
    }

    public enum Operator {
        EQ("=="), NEQ("!="), LT("<"), LTE("<="), GT(">"), GTE(">="),
        /** Case-insensitive substring match -- SQL {@code LIKE '%value%'} on the JDBC adapters. */
        CONTAINS("contains"),
        /** R4.3 (Roadmap Wave 1): case-insensitive prefix match -- SQL {@code LIKE 'value%'} on the
         *  JDBC adapters, same case-folding convention {@link #CONTAINS} already uses. */
        STARTS_WITH("startsWith"),
        /** R4.3: {@code field IN (v1, v2, ...)}. {@link Filter#value()} carries a non-empty
         *  {@code List<Object>} -- each element is bound as its own parameter (never spliced),
         *  via {@code SqlDialect#inPlaceholders}. */
        IN("in"),
        /** R4.3: {@code field IS NULL}. {@link Filter#value()} is unused (conventionally {@code null}). */
        IS_NULL("is null"),
        /** R4.3: {@code field IS NOT NULL}. {@link Filter#value()} is unused (conventionally {@code null}). */
        IS_NOT_NULL("is not null"),
        /**
         * R4.3: NOT a real per-field comparison -- a MARKER that carries this query's whole
         * OR-of-AND predicate ({@code QueryPredicateGrammar}'s v2 grammar) without changing
         * {@link ConceptQuery}'s own record shape, which generated code across every existing
         * FinalApp constructs positionally
         * ({@code business-concept-crud-controller.mustache}: {@code new ConceptQuery(filters,
         * sorts, offset, limit)}) -- widening that constructor's arity would break every already-
         * generated app's compiled call site, which this feature must not do.
         *
         * <p>When present, {@link Filter#value()} is a {@code List<List<Filter>>}: the OUTER list's
         * groups are OR-combined, each INNER list's clauses are AND-combined -- exactly the shape
         * {@code QueryPredicateGrammar#parseGroups} parses and
         * {@code ConceptQueryPredicateCompiler#compileToConceptQueryFilters} produces. A query built
         * this way carries NO other filter alongside the marker -- {@link Filter#orGroups} is the
         * only supported constructor, and a store is entitled to assume the marker is alone in
         * {@link ConceptQuery#filters()} when present, exactly as
         * {@code JdbcBusinessConceptStore} does.
         */
        OR_GROUPS("__or_groups__");

        private final String token;

        Operator(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    /**
     * A single predicate entry. For every operator except {@link Operator#OR_GROUPS}, {@code field}
     * names the column to compare -- either a plain field name (unchanged since LNCH-5) or, as of
     * R4.3, a reference-path join string in {@code com.npdev.dsl.v1.query.GroupByJoinGrammar}'s own
     * dotted/context-qualified shape ({@code "lote.produtoId"}, {@code "inventory::lote.produtoId"}),
     * bounded at the same {@code GroupByJoinGrammar#MAX_JOIN_HOPS} hops a {@code groupBy} path is --
     * resolved by a store via the identical join machinery a {@code groupBy} join already uses (see
     * {@code JdbcBusinessConceptStore#registerJoinChain}), so a predicate join and a {@code groupBy}
     * join to the same reference chain are deduplicated into one SQL {@code JOIN}.
     *
     * <p>All filters in a {@link ConceptQuery#filters()} list are AND-combined, UNLESS the list is
     * exactly one {@link Operator#OR_GROUPS} marker (see that constant's own javadoc), which is the
     * only way to express a top-level OR.
     */
    public record Filter(String field, Operator operator, Object value) {
        public Filter {
            field = requireField(field);
            operator = Objects.requireNonNull(operator, "operator");
        }

        public static Filter eq(String field, Object value) {
            return new Filter(field, Operator.EQ, value);
        }

        /** R4.3: {@code field startsWith value} (case-insensitive prefix match). */
        public static Filter startsWith(String field, Object value) {
            return new Filter(field, Operator.STARTS_WITH, value);
        }

        /** R4.3: {@code field in (values...)}. {@code values} must be non-empty -- an empty IN list
         *  is a caller bug refused here rather than silently rendered as "no rows match". */
        public static Filter in(String field, List<Object> values) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("Filter.in(" + field + ") requires at least one value");
            }
            return new Filter(field, Operator.IN, List.copyOf(values));
        }

        /** R4.3: {@code field is null}. */
        public static Filter isNull(String field) {
            return new Filter(field, Operator.IS_NULL, null);
        }

        /** R4.3: {@code field is not null}. */
        public static Filter isNotNull(String field) {
            return new Filter(field, Operator.IS_NOT_NULL, null);
        }

        /**
         * R4.3: wraps {@code groups} (OR-of-AND clause groups; each inner list is AND-combined, the
         * outer list is OR-combined) as the sole entry of a {@link ConceptQuery}'s {@code filters}
         * list -- see {@link Operator#OR_GROUPS}'s own javadoc for why this exists instead of a
         * wider {@link ConceptQuery} record shape.
         *
         * @throws IllegalArgumentException if {@code groups} is null/empty, or any group is empty
         */
        public static Filter orGroups(List<List<Filter>> groups) {
            if (groups == null || groups.isEmpty()) {
                throw new IllegalArgumentException("Filter.orGroups requires at least one group");
            }
            List<List<Filter>> copy = new java.util.ArrayList<>();
            for (List<Filter> group : groups) {
                if (group == null || group.isEmpty()) {
                    throw new IllegalArgumentException("Filter.orGroups: every group must have at least one clause");
                }
                copy.add(List.copyOf(group));
            }
            return new Filter("__or_groups__", Operator.OR_GROUPS, List.copyOf(copy));
        }
    }

    /** A single sort key; multiple sorts apply as a stable, left-to-right ordering. */
    public record Sort(String field, boolean descending) {
        public Sort {
            field = requireField(field);
        }

        public static Sort asc(String field) {
            return new Sort(field, false);
        }

        public static Sort desc(String field) {
            return new Sort(field, true);
        }
    }

    private static String requireField(String field) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("query field must be non-blank");
        }
        return field.trim();
    }

    /** Lower-cased field name, for case-insensitive whitelist lookups in an adapter. */
    static String normalizeField(String field) {
        return field == null ? "" : field.trim().toLowerCase(Locale.ROOT);
    }
}
