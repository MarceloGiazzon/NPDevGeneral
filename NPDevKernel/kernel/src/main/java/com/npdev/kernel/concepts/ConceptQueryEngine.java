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

    /** Package-visible (not private) so {@link ConceptAggregateEngine} reuses the SAME filter
     *  evaluation for an aggregate query's pre-grouping {@code where}, rather than forking it. */
    static boolean matchesAll(ConceptRecord record, List<ConceptQuery.Filter> filters) {
        for (ConceptQuery.Filter filter : filters) {
            if (!matches(record, filter)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(ConceptRecord record, ConceptQuery.Filter filter) {
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
