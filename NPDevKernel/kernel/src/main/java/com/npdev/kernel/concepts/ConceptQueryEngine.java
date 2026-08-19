package com.npdev.kernel.concepts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * LNCH-5: the in-memory reference evaluation of a {@link ConceptQuery} -- the contract the JDBC
 * adapters implement natively in SQL. It is the {@code *-inproc} adapter family's implementation of
 * the same query port, so dev mode stays behaviourally identical to the production database path.
 *
 * <p>Filters are AND-combined single-field comparisons; sorts apply as a stable left-to-right
 * ordering; {@code total} counts all filtered rows and the page window ({@code offset}/{@code limit})
 * is applied last. Values compare numerically when both sides are numbers, else lexicographically,
 * matching the JDBC comparison semantics for the field types NPDev models.
 */
public final class ConceptQueryEngine {

    private ConceptQueryEngine() {
    }

    public static ConceptPage apply(List<ConceptRecord> records, ConceptQuery query) {
        ConceptQuery effective = query == null ? ConceptQuery.firstPage() : query;
        List<ConceptRecord> filtered = new ArrayList<>();
        for (ConceptRecord record : records == null ? List.<ConceptRecord>of() : records) {
            if (matchesAll(record, effective.filters())) {
                filtered.add(record);
            }
        }
        sort(filtered, effective.sorts());
        long total = filtered.size();
        int from = Math.min(effective.offset(), filtered.size());
        int to = Math.min(from + effective.limit(), filtered.size());
        List<ConceptRecord> page = new ArrayList<>(filtered.subList(from, to));
        return ConceptPage.of(page, total, from);
    }

    /**
     * Package-visible (not private) so {@link ConceptAggregateEngine} reuses the SAME filter
     * evaluation for an aggregate query's pre-grouping {@code where}, rather than forking it.
     *
     * <p>R4.3 (Roadmap Wave 1): recognises the single {@link ConceptQuery.Operator#OR_GROUPS}
     * marker shape (see that constant's own javadoc) as an OR-of-AND, so this in-memory adapter
     * stays behaviourally at parity with {@code JdbcBusinessConceptStore}'s SQL rendering for every
     * shape it can actually evaluate -- see {@link #matches} for the one shape it CANNOT
     * (a reference-path field), which it refuses rather than silently mis-evaluating.
     */
    static boolean matchesAll(ConceptRecord record, List<ConceptQuery.Filter> filters) {
        if (filters.isEmpty()) {
            return true;
        }
        if (filters.size() == 1 && filters.get(0).operator() == ConceptQuery.Operator.OR_GROUPS) {
            @SuppressWarnings("unchecked")
            List<List<ConceptQuery.Filter>> groups = (List<List<ConceptQuery.Filter>>) filters.get(0).value();
            for (List<ConceptQuery.Filter> group : groups) {
                if (matchesAllFlat(record, group)) {
                    return true;
                }
            }
            return false;
        }
        return matchesAllFlat(record, filters);
    }

    private static boolean matchesAllFlat(ConceptRecord record, List<ConceptQuery.Filter> filters) {
        for (ConceptQuery.Filter filter : filters) {
            if (!matches(record, filter)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(ConceptRecord record, ConceptQuery.Filter filter) {
        if (filter.field() != null && (filter.field().indexOf('.') >= 0 || filter.field().contains("::"))) {
            // R4.3: only JdbcBusinessConceptStore resolves a reference-path field, via a real SQL
            // JOIN (registerJoinChain). This in-memory adapter has no access to another concept's
            // records from here, so silently treating a dotted field as an ordinary (always-absent)
            // key would be exactly the X0 "unenforced filter" trap -- refused loudly instead.
            throw new UnsupportedOperationException(
                    "ConceptQueryEngine (the in-memory adapter) does not resolve reference-path filter "
                            + "field '" + filter.field() + "' -- only JdbcBusinessConceptStore joins one "
                            + "(via registerJoinChain). Refused rather than silently evaluating it as absent.");
        }
        Object actual = record.data() == null ? null : record.data().get(filter.field());
        Object expected = filter.value();
        return switch (filter.operator()) {
            case EQ -> valuesEqual(actual, expected);
            case NEQ -> !valuesEqual(actual, expected);
            case LT -> compare(actual, expected) < 0;
            case LTE -> compare(actual, expected) <= 0;
            case GT -> compare(actual, expected) > 0;
            case GTE -> compare(actual, expected) >= 0;
            case CONTAINS -> actual != null && expected != null
                    && String.valueOf(actual).toLowerCase(java.util.Locale.ROOT)
                            .contains(String.valueOf(expected).toLowerCase(java.util.Locale.ROOT));
            // R4.3: mirrors ConceptQueryFilterSupport#matchesPredicateClause's v2 semantics exactly,
            // so the in-memory and JDBC adapters agree on every shape this evaluator can reach.
            case STARTS_WITH -> actual != null && expected != null
                    && String.valueOf(actual).toLowerCase(java.util.Locale.ROOT)
                            .startsWith(String.valueOf(expected).toLowerCase(java.util.Locale.ROOT));
            case IN -> actual != null && expected instanceof List<?> candidates
                    && candidates.stream().anyMatch(candidate -> valuesEqual(actual, candidate));
            case IS_NULL -> actual == null;
            case IS_NOT_NULL -> actual != null;
            case OR_GROUPS -> throw new IllegalArgumentException(
                    "OR_GROUPS may only appear as the sole top-level filter, not nested inside a clause group");
        };
    }

    private static void sort(List<ConceptRecord> records, List<ConceptQuery.Sort> sorts) {
        Comparator<ConceptRecord> comparator = null;
        for (ConceptQuery.Sort sort : sorts) {
            String field = sort.field();
            Comparator<ConceptRecord> byField = (left, right) ->
                    compare(fieldValue(left, field), fieldValue(right, field));
            if (sort.descending()) {
                byField = byField.reversed();
            }
            comparator = comparator == null ? byField : comparator.thenComparing(byField);
        }
        if (comparator != null) {
            records.sort(comparator);
        }
    }

    private static Object fieldValue(ConceptRecord record, String field) {
        return record.data() == null ? null : record.data().get(field);
    }

    /** Package-visible for {@link ConceptAggregateEngine}'s own HAVING-clause evaluation. */
    static boolean valuesEqual(Object actual, Object expected) {
        return Objects.equals(normalize(actual), normalize(expected));
    }

    /**
     * Nulls sort last (ascending): a null field is treated as greater than any present value, so
     * {@code < x} never matches a null and ascending sorts push nulls to the end -- matching the
     * ConceptQueryFilterSupport orderBy behaviour this generalizes.
     *
     * <p>Package-visible so {@link ConceptAggregateEngine} sorts/compares aggregate output values
     * with the SAME numeric-vs-lexicographic semantics, rather than a second, potentially-diverging
     * comparison rule.
     */
    static int compare(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        BigDecimal leftNumber = asNumber(left);
        BigDecimal rightNumber = asNumber(right);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static Object normalize(Object value) {
        BigDecimal number = asNumber(value);
        if (number != null) {
            return number.stripTrailingZeros();
        }
        return value == null ? null : String.valueOf(value);
    }

    /** Package-visible for {@link ConceptAggregateEngine}'s own sum/avg accumulation. */
    static BigDecimal asNumber(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
