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
        CONTAINS("contains");

        private final String token;

        Operator(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    /** A single {@code field <op> value} predicate; all filters on a query are AND-combined. */
    public record Filter(String field, Operator operator, Object value) {
        public Filter {
            field = requireField(field);
            operator = Objects.requireNonNull(operator, "operator");
        }

        public static Filter eq(String field, Object value) {
            return new Filter(field, Operator.EQ, value);
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
