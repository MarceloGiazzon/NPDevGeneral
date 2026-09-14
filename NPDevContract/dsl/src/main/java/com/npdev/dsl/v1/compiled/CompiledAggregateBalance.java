package com.npdev.dsl.v1.compiled;

import java.util.List;

/**
 * Compiled form of a declared grouped balance rule. See
 * {@link com.npdev.dsl.v1.ast.AggregateBalanceAst} (Session 1, NPDEV_MEGA_ROADMAP.md 2026-09-14).
 */
public record CompiledAggregateBalance(
        String name,
        String collection,
        List<String> groupBy,
        String discriminatorField,
        String leftValue,
        String rightValue,
        String quantityField,
        String message
) {
    public CompiledAggregateBalance {
        name = name == null || name.isBlank() ? null : name.trim();
        collection = collection == null || collection.isBlank() ? null : collection.trim();
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        discriminatorField = discriminatorField == null || discriminatorField.isBlank()
                ? null : discriminatorField.trim();
        leftValue = leftValue == null || leftValue.isBlank() ? null : leftValue.trim();
        rightValue = rightValue == null || rightValue.isBlank() ? null : rightValue.trim();
        quantityField = quantityField == null || quantityField.isBlank() ? null : quantityField.trim();
        message = message == null || message.isBlank() ? null : message.trim();
    }
}
