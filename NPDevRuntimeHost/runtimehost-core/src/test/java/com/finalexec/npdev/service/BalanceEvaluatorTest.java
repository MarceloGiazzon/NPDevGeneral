package com.finalexec.npdev.service;

import com.npdev.dsl.v1.compiled.CompiledAggregateBalance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): the grouped, role-partitioned balance evaluator
 *  shared by AggregateRuntime's commit-time gate and its on-demand checkBalances path. */
class BalanceEvaluatorTest {

    private static CompiledAggregateBalance balance(List<String> groupBy, String message) {
        return new CompiledAggregateBalance(
                "OrigemDestinoBalance", "items.positions", groupBy,
                "role", "Origem", "Destino", "quantity", message);
    }

    private static Map<String, Object> position(String sku, String validity, String role, int quantity) {
        return Map.of("sku", sku, "validity", validity, "role", role, "quantity", quantity);
    }

    private static Map<String, Object> draftWithPositions(List<Map<String, Object>> positions) {
        return Map.of("items", List.of(Map.of("positions", positions)));
    }

    @Test
    void balancedGroupReportsZeroDeltaAndNoMessage() {
        var draft = draftWithPositions(List.of(
                position("SKU1", "2026-01-01", "Origem", 10),
                position("SKU1", "2026-01-01", "Destino", 10)));

        List<BalanceEvaluator.GroupResult> results = BalanceEvaluator.evaluate(
                balance(List.of("sku", "validity"), null), draft);

        assertEquals(1, results.size());
        assertTrue(results.get(0).balanced());
        assertEquals(0, BigDecimal.ZERO.compareTo(results.get(0).delta()));
        assertNull(results.get(0).message());
    }

    @Test
    void unbalancedGroupReportsDeltaAndSubstitutesMessageTokens() {
        var draft = draftWithPositions(List.of(
                position("SKU1", "2026-01-01", "Origem", 10),
                position("SKU1", "2026-01-01", "Destino", 4)));

        List<BalanceEvaluator.GroupResult> results = BalanceEvaluator.evaluate(
                balance(List.of("sku", "validity"),
                        "Faltam {delta} unidade(s) de {sku} (validade {validity}) no destino"),
                draft);

        assertEquals(1, results.size());
        assertFalse(results.get(0).balanced());
        assertEquals(0, new BigDecimal("6").compareTo(results.get(0).delta()));
        assertEquals("Faltam 6 unidade(s) de SKU1 (validade 2026-01-01) no destino",
                results.get(0).message());
    }

    @Test
    void rowsArePartitionedIntoOneGroupPerDistinctGroupByCombination() {
        var draft = draftWithPositions(List.of(
                position("SKU1", "2026-01-01", "Origem", 5),
                position("SKU1", "2026-01-01", "Destino", 5),
                position("SKU2", "2026-01-01", "Origem", 3),
                position("SKU2", "2026-01-01", "Destino", 1)));

        List<BalanceEvaluator.GroupResult> results = BalanceEvaluator.evaluate(
                balance(List.of("sku", "validity"), null), draft);

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(BalanceEvaluator.GroupResult::balanced), "SKU1 group should balance");
        assertTrue(results.stream().anyMatch(r -> !r.balanced()), "SKU2 group should not balance");
    }

    @Test
    void aRoleMissingEntirelyFromAGroupCountsAsZeroOnThatSide() {
        var draft = draftWithPositions(List.of(position("SKU1", "2026-01-01", "Origem", 7)));

        List<BalanceEvaluator.GroupResult> results = BalanceEvaluator.evaluate(
                balance(List.of("sku", "validity"), null), draft);

        assertEquals(1, results.size());
        assertFalse(results.get(0).balanced());
        assertEquals(0, new BigDecimal("7").compareTo(results.get(0).leftTotal()));
        assertEquals(0, BigDecimal.ZERO.compareTo(results.get(0).rightTotal()));
    }

    @Test
    void noGroupByMeansOneImplicitGroupForTheWholeCollection() {
        var draft = draftWithPositions(List.of(
                position("SKU1", "2026-01-01", "Origem", 5),
                position("SKU2", "2026-01-01", "Destino", 5)));

        List<BalanceEvaluator.GroupResult> results = BalanceEvaluator.evaluate(balance(List.of(), null), draft);

        assertEquals(1, results.size(), "with no groupBy, every row belongs to the same single group");
        assertTrue(results.get(0).balanced());
    }

    @Test
    void emptyCollectionWithNoGroupByIsTriviallyBalanced() {
        List<BalanceEvaluator.GroupResult> results =
                BalanceEvaluator.evaluate(balance(List.of(), null), draftWithPositions(List.of()));

        assertEquals(1, results.size());
        assertTrue(results.get(0).balanced(), "an empty draft has nothing out of balance yet");
    }
}
