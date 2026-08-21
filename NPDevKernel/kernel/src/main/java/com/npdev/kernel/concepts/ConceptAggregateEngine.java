package com.npdev.kernel.concepts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Move 10 B1 (LC-B1, MOVE10_AI_LOWCODE_PLAN Part B): the in-memory reference evaluation of a
 * {@link ConceptAggregateQuery} -- the {@code *-inproc} adapter family's implementation of the
 * aggregate port, mirroring how {@link ConceptQueryEngine} is the in-memory reference for plain
 * queries. A database-backed store (see {@code JdbcBusinessConceptStore}) overrides the port method
 * this backs with a real SQL {@code GROUP BY}; this engine exists so dev mode (no database) and the
 * unit-level in-memory/JDBC parity test both have a real, correct, non-SQL implementation of the
 * SAME contract.
 *
 * <p><b>Bucket labels are computed in Java, not SQL, on BOTH sides of the port</b> (the JDBC store
 * truncates a date to its bucket boundary in SQL via {@code DATE_TRUNC}, portable across H2 2.x and
 * Postgres, then formats the truncated value through {@link #bucketLabel} exactly like this engine
 * does over the raw value) -- this is what guarantees the two engines produce byte-identical group
 * labels instead of two SQL dialects' own (and possibly diverging) date-formatting functions.
 */
public final class ConceptAggregateEngine {

    private ConceptAggregateEngine() {
    }

    public static ConceptAggregateResult apply(List<ConceptRecord> records, ConceptAggregateQuery query) {
        List<ConceptRecord> filtered = new ArrayList<>();
        for (ConceptRecord record : records == null ? List.<ConceptRecord>of() : records) {
            if (ConceptQueryEngine.matchesAll(record, query.filters())) {
                filtered.add(record);
            }
        }

        List<Map<String, Object>> rows;
        if (query.groupBy().isEmpty()) {
            // A pure aggregate with no groupBy still returns exactly one row (e.g. a single KPI
            // total) -- there is one implicit group containing every filtered record.
            rows = new ArrayList<>();
            rows.add(computeAggregates(filtered, query.aggregates()));
        } else {
            Map<List<Object>, List<ConceptRecord>> groups = new LinkedHashMap<>();
            for (ConceptRecord record : filtered) {
                List<Object> key = new ArrayList<>(query.groupBy().size());
                for (ConceptAggregateQuery.GroupByField groupByField : query.groupBy()) {
                    key.add(groupKeyValue(record, groupByField));
                }
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(record);
            }
            rows = new ArrayList<>(groups.size());
            for (Map.Entry<List<Object>, List<ConceptRecord>> entry : groups.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                List<Object> key = entry.getKey();
                for (int i = 0; i < query.groupBy().size(); i++) {
                    row.put(query.groupBy().get(i).field(), key.get(i));
                }
                row.putAll(computeAggregates(entry.getValue(), query.aggregates()));
                rows.add(row);
            }
        }

        rows = applyHavingSortAndLimit(rows, query.having(), query.sorts(), query.limit());
        return new ConceptAggregateResult(rows);
    }

    /**
     * Move 10 B1: the shared HAVING/sort/limit post-processing BOTH engines apply, in the SAME
     * order, over the SAME already-grouped rows -- public so {@code JdbcBusinessConceptStore} (a
     * different module/package) reuses it verbatim after its own SQL {@code GROUP BY} instead of
     * reimplementing HAVING/sort/limit a second time. Grouped result sets are small (bounded by the
     * cardinality of the groupBy combination, not raw row count), so doing this in Java rather than
     * pushing HAVING/LIMIT to SQL is a deliberate, cheap v1 simplification, not a performance risk.
     */
    public static List<Map<String, Object>> applyHavingSortAndLimit(
            List<Map<String, Object>> rows, List<ConceptQuery.Filter> having,
            List<ConceptQuery.Sort> sorts, Integer limit) {
        List<Map<String, Object>> result = applyHaving(rows, having);
        result = new ArrayList<>(result);
        sortRows(result, sorts);
        if (limit != null && limit > 0 && result.size() > limit) {
            result = new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    private static Object groupKeyValue(ConceptRecord record, ConceptAggregateQuery.GroupByField groupByField) {
        Object raw = record.data() == null ? null : record.data().get(groupByField.field());
        if (groupByField.bucket() == null || groupByField.bucket().isBlank()) {
            return raw;
        }
        LocalDate date = toLocalDate(raw);
        return date == null ? null : bucketLabel(date, groupByField.bucket());
    }

    private static Map<String, Object> computeAggregates(
            List<ConceptRecord> group, List<ConceptAggregateQuery.AggregateFunction> aggregates) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ConceptAggregateQuery.AggregateFunction aggregate : aggregates) {
            out.put(aggregate.outputName(), computeOne(group, aggregate));
        }
        return out;
    }

    private static Object computeOne(List<ConceptRecord> group, ConceptAggregateQuery.AggregateFunction aggregate) {
        String fn = aggregate.fn() == null ? "" : aggregate.fn().trim().toLowerCase(Locale.ROOT);
        if ("count".equals(fn)) {
            return (long) group.size();
        }
        List<BigDecimal> numbers = new ArrayList<>();
        for (ConceptRecord record : group) {
            Object raw = record.data() == null ? null : record.data().get(aggregate.field());
            BigDecimal number = ConceptQueryEngine.asNumber(raw);
            if (number != null) {
                numbers.add(number);
            }
        }
        return switch (fn) {
            case "sum" -> numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case "avg" -> numbers.isEmpty() ? null
                    : numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(numbers.size()), 10, java.math.RoundingMode.HALF_UP);
            case "min" -> numbers.stream().min(BigDecimal::compareTo).orElse(null);
            case "max" -> numbers.stream().max(BigDecimal::compareTo).orElse(null);
            default -> throw new IllegalArgumentException("Unsupported aggregate fn: " + aggregate.fn());
        };
    }

    private static List<Map<String, Object>> applyHaving(List<Map<String, Object>> rows, List<ConceptQuery.Filter> having) {
        if (having.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (matchesHaving(row, having)) {
                out.add(row);
            }
        }
        return out;
    }

    private static boolean matchesHaving(Map<String, Object> row, List<ConceptQuery.Filter> having) {
        for (ConceptQuery.Filter filter : having) {
            Object actual = row.get(filter.field());
            Object expected = filter.value();
            boolean matched = switch (filter.operator()) {
                case EQ -> ConceptQueryEngine.valuesEqual(actual, expected);
                case NEQ -> !ConceptQueryEngine.valuesEqual(actual, expected);
                case LT -> ConceptQueryEngine.compare(actual, expected) < 0;
                case LTE -> ConceptQueryEngine.compare(actual, expected) <= 0;
                case GT -> ConceptQueryEngine.compare(actual, expected) > 0;
                case GTE -> ConceptQueryEngine.compare(actual, expected) >= 0;
                case CONTAINS -> actual != null && expected != null
                        && String.valueOf(actual).toLowerCase(Locale.ROOT)
                                .contains(String.valueOf(expected).toLowerCase(Locale.ROOT));
                // R4.3 (Roadmap Wave 1): ConceptQuery.Operator grew four constants for the v2
                // predicate grammar. HAVING is compiled from `query.having()` via
                // ConceptQueryPredicateCompiler#compile (the v1, AND-only, OR_GROUPS-free grammar),
                // so none of these five are reachable from a declared query's `having` TODAY --
                // but the switch is exhaustive by design (no default arm), so every constant needs
                // a real answer here rather than a silent fallthrough. Semantics mirror
                // ConceptQueryFilterSupport#matchesPredicateClause exactly, for the day `having`
                // does grow v2 support.
                case STARTS_WITH -> actual != null && expected != null
                        && String.valueOf(actual).toLowerCase(Locale.ROOT)
                                .startsWith(String.valueOf(expected).toLowerCase(Locale.ROOT));
                case IN -> actual != null && expected instanceof List<?> candidates
                        && candidates.stream().anyMatch(candidate -> ConceptQueryEngine.valuesEqual(actual, candidate));
                case IS_NULL -> actual == null;
                case IS_NOT_NULL -> actual != null;
                case OR_GROUPS -> throw new IllegalArgumentException(
                        "OR_GROUPS is a marker filter (nested OR-of-AND groups) and cannot appear in a flat "
                                + "having[] list -- ConceptQueryPredicateCompiler#compile never produces one");
            };
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static void sortRows(List<Map<String, Object>> rows, List<ConceptQuery.Sort> sorts) {
        if (sorts.isEmpty()) {
            return;
        }
        Comparator<Map<String, Object>> comparator = null;
        for (ConceptQuery.Sort sort : sorts) {
            Comparator<Map<String, Object>> byField = (left, right) ->
                    ConceptQueryEngine.compare(left.get(sort.field()), right.get(sort.field()));
            if (sort.descending()) {
                byField = byField.reversed();
            }
            comparator = comparator == null ? byField : comparator.thenComparing(byField);
        }
        rows.sort(comparator);
    }

    /**
     * Accepts every date/datetime representation this platform's own read paths produce: an
     * ISO-8601 {@link String} (the JSON-over-REST shape every in-memory record's date field
     * arrives as), {@link LocalDate}/{@link LocalDateTime}/{@link OffsetDateTime}, and the JDBC
     * driver's own {@link java.sql.Date}/{@link java.sql.Timestamp} (defensive -- the in-memory
     * store never produces these, but a caller composing records by hand might).
     *
     * <p>Public so {@code JdbcBusinessConceptStore} converts a {@code DATE_TRUNC}'d JDBC column
     * value through the SAME parsing this engine uses over raw in-memory values.
     */
    public static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toLocalDate();
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp sqlTimestamp) {
            return sqlTimestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof java.time.Instant instant) {
            return instant.atZone(ZoneId.systemDefault()).toLocalDate();
        }
        if (value instanceof String text && !text.isBlank()) {
            String trimmed = text.trim();
            try {
                return LocalDate.parse(trimmed.length() >= 10 ? trimmed.substring(0, 10) : trimmed);
            } catch (java.time.format.DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * The v1 closed bucket vocabulary: {@code day}/{@code week}/{@code month}/{@code quarter}/
     * {@code year}. Week uses ISO-8601 week numbering (Monday start, week 1 = the week containing
     * the year's first Thursday) via {@link WeekFields#ISO} -- the same definition SQL
     * {@code DATE_TRUNC('week', ...)} uses on both H2 2.x and Postgres, which is what keeps this
     * label consistent with the JDBC adapter's own SQL-truncated-then-Java-formatted result.
     *
     * <p>Public for the same reason as {@link #toLocalDate}.
     */
    public static String bucketLabel(LocalDate date, String bucket) {
        return switch (bucket.trim().toLowerCase(Locale.ROOT)) {
            case "day" -> date.toString();
            case "week" -> {
                int isoWeek = date.get(WeekFields.ISO.weekOfWeekBasedYear());
                int isoWeekYear = date.get(WeekFields.ISO.weekBasedYear());
                yield String.format(Locale.ROOT, "%04d-W%02d", isoWeekYear, isoWeek);
            }
            case "month" -> String.format(Locale.ROOT, "%04d-%02d", date.getYear(), date.getMonthValue());
            case "quarter" -> String.format(
                    Locale.ROOT, "%04d-Q%d", date.getYear(), (date.getMonthValue() - 1) / 3 + 1);
            case "year" -> String.format(Locale.ROOT, "%04d", date.getYear());
            default -> throw new IllegalArgumentException("Unsupported groupBy bucket: " + bucket);
        };
    }
}
