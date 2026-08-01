package com.npdev.kernel.concepts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * LIFT-QUERY-P1: the shared {@code where}/{@code orderBy} post-filter for a {@code CompiledQuery},
 * applied to records already fetched from a {@link ConceptGateway}. Originally duplicated between
 * {@code PanelRuntime} (declared-Panel dataSources) and {@code DefaultProcedureExecutor}'s
 * {@code runQuery} step (which previously ignored {@code where} entirely and returned every row) --
 * consolidated here so both share one implementation.
 *
 * <p><b>LC-P0 (2026-07-31): this class no longer parses anything.</b> {@link #applyWhere} now
 * compiles {@code where} with {@link ConceptQueryPredicateCompiler} -- the same grammar
 * {@link ConceptQuery} declares, which the JDBC adapters already push to SQL -- and evaluates the
 * resulting AND-combined filters in memory. Two things change for callers:
 *
 * <ul>
 *   <li><b>Multi-clause predicates work.</b> {@code a == 'x' && b > 3} is now evaluated as written.</li>
 *   <li><b>An uncompilable predicate THROWS</b> ({@link ConceptQueryPredicateCompiler.UnsupportedPredicateException})
 *       instead of silently returning a default answer.</li>
 * </ul>
 *
 * <p>Its previous hand-rolled {@code indexOf("==")} scan had three distinct silent failure modes --
 * every row, zero rows, and an inverted every-row -- all reproduced in
 * {@code ConceptQueryFilterSupportRedTest}. The old javadoc claimed only the first
 * ("a clause outside this shape is left unenforced (rows pass through unfiltered)"), which is why
 * the other two went unnoticed.
 *
 * <p><b>This is the correctness half of LC-P0, not the scale half.</b> Evaluation is still in the
 * JVM over records already fetched; routing the two callers through {@code ConceptGateway.query} so
 * the store narrows (WHERE/LIMIT pushed to SQL) is the remaining step. Answers are now right;
 * a declared Panel over a very large concept is still a memory event.
 */
public final class ConceptQueryFilterSupport {

    private ConceptQueryFilterSupport() {
    }

    /**
     * @throws ConceptQueryPredicateCompiler.UnsupportedPredicateException when {@code where} is
     *         outside the supported grammar -- deliberately, so an unenforced filter can never
     *         silently return rows the author asked to exclude
     */
    public static List<ConceptRecord> applyWhere(List<ConceptRecord> records, String where) {
        List<ConceptQuery.Filter> filters = ConceptQueryPredicateCompiler.compile(where);
        if (filters.isEmpty()) {
            return records;
        }
        List<ConceptRecord> filtered = new ArrayList<>();
        for (ConceptRecord record : records) {
            if (matchesAll(record, filters)) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    /** AND-combined, matching {@link ConceptQuery}'s own contract. */
    private static boolean matchesAll(ConceptRecord record, List<ConceptQuery.Filter> filters) {
        for (ConceptQuery.Filter filter : filters) {
            if (!matches(record, filter)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(ConceptRecord record, ConceptQuery.Filter filter) {
        Object fieldValue = record.data() == null ? null : record.data().get(filter.field());
        Object expected = filter.value();
        return switch (filter.operator()) {
            case EQ -> Objects.equals(normalizeForCompare(fieldValue), normalizeForCompare(expected));
            case NEQ -> !Objects.equals(normalizeForCompare(fieldValue), normalizeForCompare(expected));
            case CONTAINS -> fieldValue != null && expected != null
                    && String.valueOf(fieldValue).toLowerCase(java.util.Locale.ROOT)
                    .contains(String.valueOf(expected).toLowerCase(java.util.Locale.ROOT));
            // An ordered comparison against a missing/incomparable value is FALSE, not an error:
            // the row genuinely does not satisfy "qty > 5" when it has no qty. That is a row-level
            // answer, not an uncompilable predicate -- the distinction this whole change is about.
            case LT -> compareOrderableValues(fieldValue, expected) < 0 && bothPresent(fieldValue, expected);
            case LTE -> compareOrderableValues(fieldValue, expected) <= 0 && bothPresent(fieldValue, expected);
            case GT -> compareOrderableValues(fieldValue, expected) > 0 && bothPresent(fieldValue, expected);
            case GTE -> compareOrderableValues(fieldValue, expected) >= 0 && bothPresent(fieldValue, expected);
        };
    }

    private static boolean bothPresent(Object left, Object right) {
        return left != null && right != null;
    }

    /**
     * Applies a query's declared {@code orderBy} (list of field names, each optionally suffixed
     * {@code " desc"}/{@code " asc"}, default ascending) as a stable multi-field sort. The spec
     * grammar itself is parsed once, in {@link ConceptQueryPredicateCompiler#compileOrderBy} -- kept
     * here only as the in-memory {@link Comparator} evaluation of the resulting {@link ConceptQuery.Sort}s.
     */
    public static List<ConceptRecord> applyOrderBy(List<ConceptRecord> records, List<String> orderBy) {
        List<ConceptQuery.Sort> sorts = ConceptQueryPredicateCompiler.compileOrderBy(orderBy);
        if (sorts.isEmpty()) {
            return records;
        }
        List<ConceptRecord> sorted = new ArrayList<>(records);
        Comparator<ConceptRecord> comparator = null;
        for (ConceptQuery.Sort sort : sorts) {
            String fieldName = sort.field();
            Comparator<ConceptRecord> fieldComparator = (left, right) ->
                    compareOrderableValues(fieldValue(left, fieldName), fieldValue(right, fieldName));
            if (sort.descending()) {
                fieldComparator = fieldComparator.reversed();
            }
            comparator = comparator == null ? fieldComparator : comparator.thenComparing(fieldComparator);
        }
        sorted.sort(comparator);
        return sorted;
    }

    /** Soft cap on result size so a query never hands an unbounded list to a caller (LIFT-QUERY-P1). */
    public static List<ConceptRecord> applyLimit(List<ConceptRecord> records, Integer limit, int defaultCap) {
        int cap = limit != null && limit > 0 ? limit : defaultCap;
        return records.size() > cap ? records.subList(0, cap) : records;
    }

    private static Object fieldValue(ConceptRecord record, String field) {
        return record.data() == null ? null : record.data().get(field);
    }

    private static int compareOrderableValues(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static Object normalizeForCompare(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            return String.valueOf(value);
        }
        return null;
    }
}
