package com.finalexec.npdev.service;

import com.npdev.dsl.v1.compiled.CompiledAggregate;
import com.npdev.dsl.v1.compiled.CompiledAggregateBalance;
import com.npdev.dsl.v1.compiled.CompiledAggregateCollection;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): AggregateRuntime's two balance-checking paths --
 *  the commit-time hard gate (assertAggregateBalances) and the on-demand, non-persisting
 *  checkBalances affordance. Mirrors the depth-2 fixture shape AggregateRuntimeTest (the other
 *  module) already uses for load(), scaled down to just what a balance rule needs. */
class AggregateRuntimeBalanceTest {

    private static CompiledAggregateBalance balance() {
        return new CompiledAggregateBalance(
                "OrigemDestinoBalance", "positions", List.of("sku"),
                "role", "Origem", "Destino", "quantity", null);
    }

    private static CompiledModel modelWithBalance() {
        CompiledAggregate aggregate = new CompiledAggregate(
                "StockTransfer", "StockTransfer",
                List.of(new CompiledAggregateCollection(
                        "positions", "StockTransferPosition", null, "stockTransferId", "owned",
                        null, List.of(), Map.of(), List.of())),
                null, Map.of(), null, List.of(), null, List.of(balance()));
        return new CompiledModel(
                "test.balance", "1.0.0", "1.0", Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(aggregate));
    }

    /** save() throws -- a test using this gateway fails loudly if AggregateRuntime ever writes. */
    private static ConceptGateway neverWritesGateway() {
        return new ConceptGateway() {
            @Override
            public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
                return Optional.empty();
            }

            @Override
            public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
                return List.of();
            }

            @Override
            public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
                throw new AssertionError("must not write: " + request);
            }

            @Override
            public void delete(ConceptReadRequest request, ExecutionContext context) {
                throw new AssertionError("must not delete: " + request);
            }
        };
    }

    private static Map<String, Object> unbalancedDraft() {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("id", "T1");
        draft.put("positions", List.of(
                Map.of("sku", "SKU1", "role", "Origem", "quantity", 10),
                Map.of("sku", "SKU1", "role", "Destino", "quantity", 4)));
        return draft;
    }

    @Test
    void commitThrowsNamingTheViolationAndNeverWrites() {
        AggregateRuntime runtime = new AggregateRuntime(modelWithBalance(), neverWritesGateway());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> runtime.commit("StockTransfer", unbalancedDraft(), ExecutionContext.anonymous()));

        assertTrue(ex.getMessage().contains("balance"), "should name the violated balance: " + ex.getMessage());
    }

    @Test
    void commitSucceedsWhenEveryGroupBalances() {
        AggregateRuntime runtime = new AggregateRuntime(modelWithBalance(), neverWritesGateway());
        Map<String, Object> balancedDraft = new LinkedHashMap<>();
        balancedDraft.put("id", "T1");
        balancedDraft.put("positions", List.of(
                Map.of("id", "P1", "sku", "SKU1", "role", "Origem", "quantity", 10),
                Map.of("id", "P2", "sku", "SKU1", "role", "Destino", "quantity", 10)));

        // A balanced draft must clear assertAggregateBalances and proceed to the write path --
        // neverWritesGateway then throws on the FIRST actual write, proving the gate did not
        // (wrongly) block it. Any other exception would be a real regression in this test's setup.
        assertThrows(AssertionError.class,
                () -> runtime.commit("StockTransfer", balancedDraft, ExecutionContext.anonymous()));
    }

    @Test
    void checkBalancesNeverWritesAndReturnsAPerGroupReportWithoutMutatingTheDraftsOwnFields() {
        AggregateRuntime runtime = new AggregateRuntime(modelWithBalance(), neverWritesGateway());

        Map<String, Object> result =
                runtime.checkBalances("StockTransfer", List.of("OrigemDestinoBalance"), unbalancedDraft());

        assertEquals("T1", result.get("id"), "the draft's own fields pass through untouched");
        assertTrue(result.containsKey("__balances"));
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) result.get("__balances");
        @SuppressWarnings("unchecked")
        Map<String, Object> rule = (Map<String, Object>) report.get("OrigemDestinoBalance");
        assertEquals(false, rule.get("balanced"));
    }

    @Test
    void checkBalancesSkipsAnUnresolvableRuleNameRatherThanThrowing() {
        AggregateRuntime runtime = new AggregateRuntime(modelWithBalance(), neverWritesGateway());

        Map<String, Object> result =
                runtime.checkBalances("StockTransfer", List.of("NoSuchRule"), unbalancedDraft());

        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) result.get("__balances");
        assertFalse(report.containsKey("NoSuchRule"));
    }
}
