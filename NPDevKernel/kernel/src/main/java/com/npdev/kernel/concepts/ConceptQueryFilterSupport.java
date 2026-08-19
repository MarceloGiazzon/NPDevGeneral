package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.query.GroupByJoinGrammar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
            // R4.3 (Roadmap Wave 1): ConceptQuery.Operator grew four constants for the v2 predicate
            // grammar (applyPredicate below, not this v1 applyWhere). ConceptQueryPredicateCompiler
            // #compile (the v1 grammar this method's filters always come from) never produces one of
            // these, but the switch is exhaustive by design -- semantics mirror #matchesPredicateClause
            // below exactly, for a caller that hands applyWhere a v2-shaped filter directly.
            case STARTS_WITH -> fieldValue != null && expected != null
                    && String.valueOf(fieldValue).toLowerCase(java.util.Locale.ROOT)
                    .startsWith(String.valueOf(expected).toLowerCase(java.util.Locale.ROOT));
            case IN -> fieldValue != null && expected instanceof List<?> candidates
                    && candidates.stream().anyMatch(candidate ->
                            Objects.equals(normalizeForCompare(fieldValue), normalizeForCompare(candidate)));
            case IS_NULL -> fieldValue == null;
            case IS_NOT_NULL -> fieldValue != null;
            case OR_GROUPS -> throw new IllegalArgumentException(
                    "OR_GROUPS may only appear as the sole top-level filter, not nested inside a clause group");
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

    // ============================================================================================
    // R4.3 (Roadmap Wave 1): applyPredicate -- the v2 sibling of applyWhere above, evaluating
    // QueryPredicateGrammar's full grammar (OR-groups, IN, contains/startsWith, is-null, and a
    // reference-path left side) in memory. See QueryPredicateGrammar's own "PREDICATE GRAMMAR V2"
    // section header for why this is a separate method rather than a change to applyWhere, and why
    // it is not (yet) reachable from any live request path in a generated app (production concepts
    // run npdev.storage.mode=jdbc; this evaluator has no SQL pushdown counterpart yet -- that needs
    // changes in NPDevRuntimeHost, outside this change's owned surface).
    // ============================================================================================

    /**
     * Resolves ONE reference-field hop for a joined predicate field: given the concept the CURRENT
     * record belongs to and one of its declared reference fields, returns the record that field
     * points at -- or empty when the field is null or the reference is dangling, both of which make
     * the whole clause evaluate to "no value" (the same INNER-JOIN semantics
     * {@code InMemoryConceptStore}'s groupBy join pre-materialization already uses, so a reference
     * path used in a predicate and one used in {@code groupBy} agree on the same row set).
     */
    @FunctionalInterface
    public interface ReferenceHopResolver {
        Optional<ResolvedHop> resolve(String fromConcept, ConceptRecord fromRecord, String referenceField);

        /** The record a hop resolved to, together with ITS OWN concept name -- needed so the NEXT
         *  hop (if any) knows which concept's reference fields it is walking. */
        record ResolvedHop(String conceptName, ConceptRecord record) {
        }
    }

    /**
     * {@link #applyPredicate(List, String, String, ReferenceHopResolver)} with no reference
     * resolver -- correct for any {@code where} that names no reference-path field. A predicate
     * that DOES name one throws (X0: refuse rather than silently treat a joined field as absent).
     */
    public static List<ConceptRecord> applyPredicate(List<ConceptRecord> records, String where) {
        return applyPredicate(records, where, null, null);
    }

    /**
     * @param baseConceptName the concept {@code records} belong to -- only needed (and may be null)
     *                        when {@code where} names at least one reference-path field, to resolve
     *                        its first hop
     * @throws ConceptQueryPredicateCompiler.UnsupportedPredicateException when {@code where} is
     *         outside the v2 grammar -- deliberately, so an unenforced filter can never silently
     *         return rows the author asked to exclude (same X0 contract as {@link #applyWhere})
     * @throws IllegalStateException when {@code where} names a reference-path field but
     *         {@code referenceHopResolver} is null -- refused rather than silently treating the
     *         joined value as absent
     */
    public static List<ConceptRecord> applyPredicate(
            List<ConceptRecord> records, String where, String baseConceptName, ReferenceHopResolver referenceHopResolver) {
        List<List<ConceptQueryPredicateCompiler.ResolvedClause>> groups =
                ConceptQueryPredicateCompiler.compilePredicate(where);
        if (groups.isEmpty()) {
            return records;
        }
        List<ConceptRecord> filtered = new ArrayList<>();
        for (ConceptRecord record : records) {
            if (matchesAnyGroup(record, baseConceptName, groups, referenceHopResolver)) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    private static boolean matchesAnyGroup(
            ConceptRecord record, String baseConceptName,
            List<List<ConceptQueryPredicateCompiler.ResolvedClause>> groups, ReferenceHopResolver resolver) {
        for (List<ConceptQueryPredicateCompiler.ResolvedClause> group : groups) {
            if (matchesAllInGroup(record, baseConceptName, group, resolver)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAllInGroup(
            ConceptRecord record, String baseConceptName,
            List<ConceptQueryPredicateCompiler.ResolvedClause> clauses, ReferenceHopResolver resolver) {
        for (ConceptQueryPredicateCompiler.ResolvedClause clause : clauses) {
            if (!matchesPredicateClause(record, baseConceptName, clause, resolver)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesPredicateClause(
            ConceptRecord record, String baseConceptName,
            ConceptQueryPredicateCompiler.ResolvedClause clause, ReferenceHopResolver resolver) {
        Object fieldValue = resolveFieldValue(record, baseConceptName, clause.path(), resolver);
        Object expected = clause.value();
        return switch (clause.operator()) {
            case EQ -> Objects.equals(normalizeForCompare(fieldValue), normalizeForCompare(expected));
            case NEQ -> !Objects.equals(normalizeForCompare(fieldValue), normalizeForCompare(expected));
            case CONTAINS -> fieldValue != null && expected != null
                    && String.valueOf(fieldValue).toLowerCase(java.util.Locale.ROOT)
                    .contains(String.valueOf(expected).toLowerCase(java.util.Locale.ROOT));
            case STARTS_WITH -> fieldValue != null && expected != null
                    && String.valueOf(fieldValue).toLowerCase(java.util.Locale.ROOT)
                    .startsWith(String.valueOf(expected).toLowerCase(java.util.Locale.ROOT));
            case LT -> bothPresent(fieldValue, expected) && compareOrderableValues(fieldValue, expected) < 0;
            case LTE -> bothPresent(fieldValue, expected) && compareOrderableValues(fieldValue, expected) <= 0;
            case GT -> bothPresent(fieldValue, expected) && compareOrderableValues(fieldValue, expected) > 0;
            case GTE -> bothPresent(fieldValue, expected) && compareOrderableValues(fieldValue, expected) >= 0;
            case IN -> fieldValue != null && ((List<Object>) expected).stream()
                    .anyMatch(candidate -> Objects.equals(normalizeForCompare(fieldValue), normalizeForCompare(candidate)));
            case IS_NULL -> fieldValue == null;
            case IS_NOT_NULL -> fieldValue != null;
        };
    }

    private static Object resolveFieldValue(
            ConceptRecord record, String baseConceptName, GroupByJoinGrammar.Target path, ReferenceHopResolver resolver) {
        if (path instanceof GroupByJoinGrammar.Target.Direct direct) {
            return fieldValue(record, direct.field());
        }
        GroupByJoinGrammar.Target.Join join = (GroupByJoinGrammar.Target.Join) path;
        if (resolver == null) {
            throw new IllegalStateException(
                    "predicate names a reference-path field (" + String.join(".", join.referenceFields())
                            + "." + join.targetField() + ") but no ReferenceHopResolver was supplied to "
                            + "applyPredicate -- this evaluator cannot resolve a joined value without one "
                            + "(X0: refuse rather than silently treat the field as absent)");
        }
        String currentConcept = baseConceptName;
        ConceptRecord current = record;
        for (String referenceField : join.referenceFields()) {
            Optional<ReferenceHopResolver.ResolvedHop> next = resolver.resolve(currentConcept, current, referenceField);
            if (next.isEmpty()) {
                return null; // dangling/null reference -- INNER JOIN semantics: no value at this hop.
            }
            current = next.get().record();
            currentConcept = next.get().conceptName();
        }
        return fieldValue(current, join.targetField());
    }
}
