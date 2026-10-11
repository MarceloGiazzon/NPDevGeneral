package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.expr.ComputedExpression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Friction #5 (Pigmentampas "beers to drink" = cells needed minus caps owned): adds a declared
 * query's {@code lookups[]} and {@code computed[]} columns to its already-grouped rows.
 *
 * <p>A lookup is a second aggregate over ANOTHER concept, grouped by the lookup fields named in
 * {@code on} and run through the caller-supplied {@code runner} -- in a generated app that is
 * {@link ConceptGateway#aggregate}, so the lookup concept's tenant scope and row-level
 * {@code access.read} apply exactly as they do to any aggregate. Its value is merged into each base
 * row whose {@code on} groupBy values match; a row with no match gets 0 for {@code count}/{@code sum}
 * (nothing owned is zero owned) and null for {@code avg}/{@code min}/{@code max}.
 *
 * <p>Computed columns are then evaluated in declared order with {@link ComputedExpression} over
 * the row itself, so each may name groupBy, aggregate, lookup and earlier computed columns. The
 * caller applies {@code having}/sort/limit AFTER this step
 * ({@link ConceptAggregateEngine#applyHavingSortAndLimit}), which is what lets
 * {@code having: "toDrink > 0"} and {@code orderBy: ["toDrink desc"]} name the new columns.
 */
public final class ConceptAggregateLookups {

    private ConceptAggregateLookups() {
    }

    /** One {@code lookups[]} entry, with its {@code where} already compiled to filters. */
    public record Lookup(
            String outputName,
            String conceptName,
            String fn,
            String field,
            Map<String, String> on,
            List<ConceptQuery.Filter> filters
    ) {
        public Lookup {
            on = on == null ? Map.of() : new LinkedHashMap<>(on);
            filters = filters == null ? List.of() : List.copyOf(filters);
        }
    }

    /** One {@code computed[]} entry. */
    public record Computed(String outputName, String expression) {
    }

    public static List<Map<String, Object>> apply(
            List<Map<String, Object>> rows,
            List<Lookup> lookups,
            List<Computed> computed,
            BiFunction<String, ConceptAggregateQuery, ConceptAggregateResult> runner
    ) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(new LinkedHashMap<>(row));
        }
        for (Lookup lookup : lookups) {
            mergeLookup(out, lookup, runner);
        }
        for (Computed column : computed) {
            for (Map<String, Object> row : out) {
                row.put(column.outputName(), ComputedExpression.evaluate(column.expression(), row));
            }
        }
        return out;
    }

    private static void mergeLookup(
            List<Map<String, Object>> rows,
            Lookup lookup,
            BiFunction<String, ConceptAggregateQuery, ConceptAggregateResult> runner
    ) {
        List<String> lookupKeys = new ArrayList<>(lookup.on().keySet());
        List<String> baseKeys = new ArrayList<>(lookup.on().values());
        List<ConceptAggregateQuery.GroupByField> groupBy = new ArrayList<>();
        for (String key : lookupKeys) {
            groupBy.add(new ConceptAggregateQuery.GroupByField(key, null));
        }
        ConceptAggregateQuery query = new ConceptAggregateQuery(
                lookup.filters(),
                groupBy,
                List.of(new ConceptAggregateQuery.AggregateFunction(lookup.outputName(), lookup.fn(), lookup.field())),
                List.of(), List.of(), null);
        ConceptAggregateResult result = runner.apply(lookup.conceptName(), query);

        Map<List<String>, Object> valuesByKey = new HashMap<>();
        for (Map<String, Object> lookupRow : result.rows()) {
            valuesByKey.put(keyOf(lookupRow, lookupKeys), lookupRow.get(lookup.outputName()));
        }
        String fn = lookup.fn() == null ? "" : lookup.fn().trim().toLowerCase(Locale.ROOT);
        Object missing = "count".equals(fn) || "sum".equals(fn) ? 0L : null;
        for (Map<String, Object> row : rows) {
            Object value = valuesByKey.get(keyOf(row, baseKeys));
            row.put(lookup.outputName(), value == null ? missing : value);
        }
    }

    /** Keys compare as strings: a reference id may come back as a UUID from one store and a String
     *  from another, and the two sides of a lookup can be read through different column types. */
    private static List<String> keyOf(Map<String, Object> row, List<String> fields) {
        List<String> key = new ArrayList<>(fields.size());
        for (String field : fields) {
            Object value = row.get(field);
            key.add(value == null ? null : String.valueOf(value));
        }
        return key;
    }
}
