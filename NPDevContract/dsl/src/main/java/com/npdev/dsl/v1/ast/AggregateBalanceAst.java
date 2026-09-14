package com.npdev.dsl.v1.ast;

import java.util.List;

/**
 * Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): a declarative, grouped cross-collection balance
 * rule on an {@link AggregateAst} -- partitions the rows of one (possibly depth-2) collection into
 * groups by {@code groupBy}, splits each group into two sides by {@code discriminatorField} (a
 * field on each row equal to either {@code leftValue} or {@code rightValue}), and requires the two
 * sides' {@code quantityField} sums to be equal per group. Modeled after the shape WmsOffice's real
 * {@code Movimento} aggregate already computes by hand today via {@code filter(papel=='Origem')} /
 * {@code filter(papel=='Destino')} derived fields over one {@code posicoes} collection.
 *
 * <p>Deliberately a dedicated structured construct rather than an extension of {@link
 * AggregateInvariantAst}'s expression grammar: per-group results (and a per-group business-language
 * shortfall message) need group semantics {@code ComputedExpression} has no concept of today, and
 * evaluation is reused both as a hard commit-time gate ({@code AggregateRuntime.commit}) and as an
 * on-demand, non-persisting check (a {@code workbenchAction} with {@code checkBalances} set).
 *
 * <p>{@code collection} is a dotted path into the aggregate's own composition tree (e.g.
 * {@code "itens.posicoes"}, matching the same address format {@code PanelValidation}'s
 * {@code derivedAddresses} already produces for {@code visibleWhen}/{@code regions}).
 */
public record AggregateBalanceAst(
        String name,
        String collection,
        List<String> groupBy,
        String discriminatorField,
        String leftValue,
        String rightValue,
        String quantityField,
        String message
) {
    public AggregateBalanceAst {
        name = name == null ? null : name.trim();
        collection = collection == null ? null : collection.trim();
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        discriminatorField = discriminatorField == null ? null : discriminatorField.trim();
        leftValue = leftValue == null ? null : leftValue.trim();
        rightValue = rightValue == null ? null : rightValue.trim();
        quantityField = quantityField == null ? null : quantityField.trim();
        message = message == null || message.isBlank() ? null : message.trim();
    }
}
