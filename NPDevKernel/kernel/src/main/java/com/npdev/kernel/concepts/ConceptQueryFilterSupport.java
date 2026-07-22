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
 * <p>Deliberately scoped to the same single-field {@code field == literal} / {@code field != literal}
 * shape every query in practice declares, not a general expression evaluator. A clause outside this
 * shape is left unenforced (rows pass through unfiltered) rather than failing the whole call.
 */
public final class ConceptQueryFilterSupport {

    private ConceptQueryFilterSupport() {
    }

    public static List<ConceptRecord> applyWhere(List<ConceptRecord> records, String where) {
        if (where == null || where.isBlank()) {
            return records;
        }
        String trimmed = where.trim();
        boolean negate;
        int opIndex = trimmed.indexOf("!=");
        if (opIndex >= 0) {
            negate = true;
        } else {
            opIndex = trimmed.indexOf("==");
            negate = false;
            if (opIndex < 0) {
                return records;
            }
        }
        String field = trimmed.substring(0, opIndex).trim();
        String literalText = trimmed.substring(opIndex + 2).trim();
        if (field.isEmpty()) {
            return records;
        }
        Object literal = parseWhereLiteral(literalText);
        List<ConceptRecord> filtered = new ArrayList<>();
        for (ConceptRecord record : records) {
            Object fieldValue = record.data() == null ? null : record.data().get(field);
            boolean equal = Objects.equals(normalizeForCompare(fieldValue), normalizeForCompare(literal));
            if (equal != negate) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    /**
     * Applies a query's declared {@code orderBy} (list of field names, each optionally suffixed
     * {@code " desc"}/{@code " asc"}, default ascending) as a stable multi-field sort.
     */
    public static List<ConceptRecord> applyOrderBy(List<ConceptRecord> records, List<String> orderBy) {
        if (orderBy == null || orderBy.isEmpty()) {
            return records;
        }
        List<ConceptRecord> sorted = new ArrayList<>(records);
        Comparator<ConceptRecord> comparator = null;
        for (String spec : orderBy) {
            if (spec == null || spec.isBlank()) {
                continue;
            }
            String trimmed = spec.trim();
            boolean descending = false;
            String field = trimmed;
            int spaceIndex = trimmed.lastIndexOf(' ');
            if (spaceIndex > 0) {
                String direction = trimmed.substring(spaceIndex + 1).trim();
                if ("desc".equalsIgnoreCase(direction) || "descending".equalsIgnoreCase(direction)) {
                    descending = true;
                    field = trimmed.substring(0, spaceIndex).trim();
                } else if ("asc".equalsIgnoreCase(direction) || "ascending".equalsIgnoreCase(direction)) {
                    field = trimmed.substring(0, spaceIndex).trim();
                }
            }
            if (field.isEmpty()) {
                continue;
            }
            String fieldName = field;
            Comparator<ConceptRecord> fieldComparator = (left, right) ->
                    compareOrderableValues(fieldValue(left, fieldName), fieldValue(right, fieldName));
            if (descending) {
                fieldComparator = fieldComparator.reversed();
            }
            comparator = comparator == null ? fieldComparator : comparator.thenComparing(fieldComparator);
        }
        if (comparator != null) {
            sorted.sort(comparator);
        }
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

    private static Object parseWhereLiteral(String text) {
        if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
            return text.substring(1, text.length() - 1);
        }
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return text;
        }
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
