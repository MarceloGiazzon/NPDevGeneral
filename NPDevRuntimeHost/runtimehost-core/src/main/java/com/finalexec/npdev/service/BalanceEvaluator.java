package com.finalexec.npdev.service;

import com.npdev.dsl.v1.compiled.CompiledAggregateBalance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): evaluates a declared {@link CompiledAggregateBalance}
 * against an aggregate draft tree -- partitions the rows found at {@code balance.collection()} (a
 * dotted path, possibly depth-2, flattened across every intermediate row) into groups by {@code
 * groupBy}, sums {@code quantityField} per side of {@code discriminatorField}, and reports the
 * per-group delta. A pure function over {@code Map}/{@code List} -- no expression parsing, since
 * grouping and per-group message templating are group-object semantics {@code ComputedExpression}
 * (the CEL-shaped engine {@code invariants[]} uses) has no concept of today.
 *
 * <p>Reused by both {@link AggregateRuntime}'s commit-time hard gate and its on-demand,
 * non-persisting {@code checkBalances} path -- same evaluation, different caller reaction to an
 * unbalanced result (one throws, the other reports).
 */
final class BalanceEvaluator {

    private BalanceEvaluator() {
    }

    record GroupResult(
            Map<String, Object> groupKey,
            BigDecimal leftTotal,
            BigDecimal rightTotal,
            BigDecimal delta,
            boolean balanced,
            String message
    ) {
    }

    static List<GroupResult> evaluate(CompiledAggregateBalance balance, Map<String, Object> draft) {
        List<Map<String, Object>> rows = resolveRows(draft, balance.collection());
        Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            List<Object> key = new ArrayList<>();
            for (String field : balance.groupBy()) {
                key.add(row.get(field));
            }
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        if (rows.isEmpty() && balance.groupBy().isEmpty()) {
            // Nothing to balance yet (e.g. a brand-new draft with no rows at all) -- an empty
            // collection is trivially balanced, not a violation.
            groups.put(List.of(), List.of());
        }
        List<GroupResult> out = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Map<String, Object>>> entry : groups.entrySet()) {
            BigDecimal left = BigDecimal.ZERO;
            BigDecimal right = BigDecimal.ZERO;
            for (Map<String, Object> row : entry.getValue()) {
                Object side = row.get(balance.discriminatorField());
                BigDecimal quantity = toBigDecimal(row.get(balance.quantityField()));
                if (equalsIgnoreCase(side, balance.leftValue())) {
                    left = left.add(quantity);
                } else if (equalsIgnoreCase(side, balance.rightValue())) {
                    right = right.add(quantity);
                }
            }
            BigDecimal delta = left.subtract(right);
            boolean balanced = delta.compareTo(BigDecimal.ZERO) == 0;
            Map<String, Object> groupKey = zip(balance.groupBy(), entry.getKey());
            out.add(new GroupResult(groupKey, left, right, delta, balanced,
                    balanced ? null : composeMessage(balance, groupKey, delta)));
        }
        return out;
    }

    /** Walks a dotted collection path (e.g. "itens.posicoes") from the draft root, flattening
     *  across every intermediate row -- "itens.posicoes" returns every position across every item,
     *  not grouped by item, matching how WmsOffice's own origemTotal/destinoTotal derived fields
     *  already flatten the same shape. A one-segment path ("posicoes") is a top-level collection. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resolveRows(Map<String, Object> draft, String collectionPath) {
        List<Map<String, Object>> current = List.of(draft == null ? Map.of() : draft);
        if (collectionPath == null || collectionPath.isBlank()) {
            return List.of();
        }
        for (String segment : collectionPath.split("\\.")) {
            List<Map<String, Object>> next = new ArrayList<>();
            for (Map<String, Object> parent : current) {
                Object value = parent.get(segment);
                if (value instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            next.add((Map<String, Object>) map);
                        }
                    }
                }
            }
            current = next;
        }
        return current;
    }

    private static Map<String, Object> zip(List<String> names, List<Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < names.size() && i < values.size(); i++) {
            out.put(names.get(i), values.get(i));
        }
        return out;
    }

    private static boolean equalsIgnoreCase(Object value, String expected) {
        return value != null && expected != null
                && String.valueOf(value).trim().equalsIgnoreCase(expected.trim());
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException notNumeric) {
            return BigDecimal.ZERO;
        }
    }

    /** {@code {delta}} plus every groupBy field name are substitution tokens in an author-supplied
     *  {@code message} template; with none declared, composes a message from the rule name, group
     *  key and delta so a violation is always readable without requiring the author to write one. */
    private static String composeMessage(
            CompiledAggregateBalance balance, Map<String, Object> groupKey, BigDecimal delta) {
        String magnitude = delta.abs().stripTrailingZeros().toPlainString();
        String template = balance.message();
        if (template == null) {
            StringBuilder fallback = new StringBuilder(balance.name()).append(": out of balance by ")
                    .append(magnitude);
            if (!groupKey.isEmpty()) {
                fallback.append(" for ").append(groupKey);
            }
            return fallback.toString();
        }
        String out = template.replace("{delta}", magnitude);
        for (Map.Entry<String, Object> entry : groupKey.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return out;
    }
}
