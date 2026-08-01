package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.compiled.CompiledProcedureParameter;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.GovernedTestGateways.ConceptSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 12 P1.4 (item 2 / REG-101): {@code queries[].parameters} declares a real feature -- a
 * {@code :name} bind placeholder in {@code where} -- that nothing substituted. REG-101's own corpus
 * witness is {@code pack-sample}'s {@code SalesByStore}:
 *
 * <pre>
 *   { "name": "SalesByStore", "concept": "Sale",
 *     "where": "storeId == :storeId",
 *     "parameters": [ { "name": "storeId", "type": "uuid", "required": true } ] }
 * </pre>
 *
 * which compared every {@code Sale.storeId} against the seven-character literal string
 * {@code ":storeId"} and therefore returned zero rows for its entire life, with no error anywhere.
 * This suite proves the fix at the layer the ledger item's fix shape (b) describes -- substitution
 * happens in {@link ConceptQueryPredicateCompiler#compile(String, List, Map)} -- with the real
 * corpus shape reproduced against a GOVERNED gateway (R4) and REAL seeded rows, so "returns correct
 * rows" is proven by an actual filtered query, not just by inspecting the constructed
 * {@link ConceptQuery.Filter}.
 */
class ConceptQueryPredicateCompilerParameterSubstitutionTest {

    private static final ExecutionContext CTX = ExecutionContext.of("tenant-a", "actor-a");
    private static final String WHERE = "storeId == :storeId";
    private static final List<CompiledProcedureParameter> SALES_BY_STORE_PARAMETERS =
            List.of(new CompiledProcedureParameter("storeId", "uuid", true, null, null));

    private static ConceptGateway seededSalesGateway() {
        ConceptGateway gateway = GovernedTestGateways.forConcepts(ConceptSpec.of("Sale", "storeId", "amount"));
        gateway.save(new ConceptWriteRequest("Sale", "s-1", "tenant-a",
                Map.of("id", "s-1", "storeId", "store-a", "amount", 100)), CTX);
        gateway.save(new ConceptWriteRequest("Sale", "s-2", "tenant-a",
                Map.of("id", "s-2", "storeId", "store-b", "amount", 200)), CTX);
        gateway.save(new ConceptWriteRequest("Sale", "s-3", "tenant-a",
                Map.of("id", "s-3", "storeId", "store-a", "amount", 300)), CTX);
        return gateway;
    }

    @Test
    void unresolvedWithoutBoundValuesRefusesJustLikeBeforeThisFix() {
        // The RED: before REG-101's fix, this returned an UNFILTERED list (LC-P0 changed that to a
        // refusal -- ":storeId" simply could not compile). The single-string overload still has no
        // bound values, so it must still refuse -- this is the control proving the fix did not widen
        // the no-substitution path into silently accepting an unbound placeholder.
        ConceptQueryPredicateCompiler.UnsupportedPredicateException thrown = assertThrows(
                ConceptQueryPredicateCompiler.UnsupportedPredicateException.class,
                () -> ConceptQueryPredicateCompiler.compile(WHERE));
        assertTrue(thrown.getMessage().contains("storeId"), thrown.getMessage());
    }

    @Test
    void boundParameterSubstitutesAndReturnsOnlyMatchingRows() {
        List<ConceptQuery.Filter> filters = ConceptQueryPredicateCompiler.compile(
                WHERE, SALES_BY_STORE_PARAMETERS, Map.of("storeId", "store-a"));
        assertEquals(List.of(new ConceptQuery.Filter("storeId", ConceptQuery.Operator.EQ, "store-a")), filters);

        // GREEN, proven against real data through the real gateway, not just the constructed filter:
        ConceptGateway gateway = seededSalesGateway();
        ConceptQuery query = new ConceptQuery(filters, List.of(), 0, 100);
        ConceptPage page = gateway.query(new ConceptQueryRequest("Sale", query), CTX);

        assertEquals(2, page.items().size(), "SalesByStore must return exactly store-a's rows: " + page.items());
        assertTrue(page.items().stream().allMatch(record -> "store-a".equals(record.data().get("storeId"))));
    }

    @Test
    void unboundDeclaredParameterFailsRatherThanDefaultingToNull() {
        ConceptQueryPredicateCompiler.UnsupportedPredicateException thrown = assertThrows(
                ConceptQueryPredicateCompiler.UnsupportedPredicateException.class,
                () -> ConceptQueryPredicateCompiler.compile(WHERE, SALES_BY_STORE_PARAMETERS, Map.of()));
        assertTrue(thrown.getMessage().contains(":storeId"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("not supplied a value"), thrown.getMessage());
    }

    @Test
    void placeholderNotDeclaredInParametersFails() {
        ConceptQueryPredicateCompiler.UnsupportedPredicateException thrown = assertThrows(
                ConceptQueryPredicateCompiler.UnsupportedPredicateException.class,
                () -> ConceptQueryPredicateCompiler.compile(WHERE, List.of(), Map.of("storeId", "store-a")));
        assertTrue(thrown.getMessage().contains("not declared"), thrown.getMessage());
    }
}
