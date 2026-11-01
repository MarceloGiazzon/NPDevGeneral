package com.npdev.kernel.concepts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * REG-318(6): a grouped query returns a {@code reference} group key as the raw id, which a person cannot
 * read. This adds, beside each such key, a {@code <field>Display} column holding the target row's display
 * field (the key itself stays the id, so a drill-down {@code field:eq:id} still matches).
 *
 * <p>Done once over the finished rows, above the store, so the in-memory and the JDBC aggregate path
 * behave identically. The caller-supplied {@code reader} is {@link ConceptGateway#read} in a generated
 * app, so the target concept's permission and row-level {@code access.read} apply: a target the caller
 * may not read gets a null label, never a leaked one.
 */
public final class ConceptAggregateReferenceLabels {

    private ConceptAggregateReferenceLabels() {
    }

    /** A groupBy field that names a reference: the row key, and the target concept + field that label it. */
    public record Spec(String rowKey, String targetConcept, String displayField) {
    }

    /** @return the output key added for {@code rowKey}. */
    public static String displayKey(String rowKey) {
        return rowKey + "Display";
    }

    /**
     * @param reader {@code (targetConcept, id) -> the record's data}, or null when the row is missing or unreadable
     */
    public static List<Map<String, Object>> apply(
            List<Map<String, Object>> rows,
            List<Spec> specs,
            BiFunction<String, String, Map<String, Object>> reader
    ) {
        if (specs.isEmpty() || rows.isEmpty()) {
            return rows;
        }
        Map<String, Map<String, Object>> cache = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> enriched = new LinkedHashMap<>(row);
            for (Spec spec : specs) {
                Object id = row.get(spec.rowKey());
                String outKey = displayKey(spec.rowKey());
                if (id == null || enriched.containsKey(outKey)) {
                    continue;
                }
                Map<String, Object> target = cache.computeIfAbsent(
                        spec.targetConcept() + "\u0000" + id,
                        key -> {
                            Map<String, Object> data = reader.apply(spec.targetConcept(), String.valueOf(id));
                            return data == null ? Map.of() : data;
                        });
                enriched.put(outKey, target.get(spec.displayField()));
            }
            out.add(enriched);
        }
        return out;
    }
}
