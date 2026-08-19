package com.npdev.kernel.concepts;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.GovernedTestGateways.ConceptSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4.3 (Roadmap Wave 1): proves {@link ConceptQueryFilterSupport#applyPredicate} -- the v2 sibling
 * of {@link ConceptQueryFilterSupport#applyWhere} -- evaluates OR-groups, IN, contains/startsWith,
 * is-null/is-not-null and a reference-path field correctly, in memory, over records already
 * fetched. See {@code QueryPredicateGrammar}'s own "PREDICATE GRAMMAR V2" section header for why
 * this evaluator has no live production caller yet.
 */
class ConceptQueryFilterSupportPredicateTest {

    private static final ExecutionContext CTX = ExecutionContext.of("tenant-a", "actor-a");

    private static List<ConceptRecord> rows() {
        return List.of(
                row("r1", "ACTIVE", "W1", 5, "widget-alpha", "sup-1"),
                row("r2", "ACTIVE", "W2", 9, "gadget-beta", null),
                row("r3", "CLOSED", "W1", 5, "widget-gamma", "sup-2"),
                row("r4", "CLOSED", "W2", 9, "thing-delta", "sup-1"));
    }

    private static ConceptRecord row(String id, String status, String warehouseId, int qty, String name, String supplierId) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("status", status);
        data.put("warehouseId", warehouseId);
        data.put("qty", qty);
        data.put("name", name);
        data.put("supplierId", supplierId);
        return new ConceptRecord("Order", id, "t1", data);
    }

    private static List<String> ids(List<ConceptRecord> records) {
        return records.stream().map(ConceptRecord::id).toList();
    }

    @Test
    @DisplayName("blank where returns every row, unchanged -- same contract as applyWhere")
    void blankWhereIsNoFilter() {
        assertEquals(ids(rows()), ids(ConceptQueryFilterSupport.applyPredicate(rows(), null)));
    }

    @Test
    @DisplayName("OR-groups: a row matching EITHER branch is included")
    void orGroupsUnionTheBranches() {
        assertEquals(List.of("r1", "r3"),
                ids(ConceptQueryFilterSupport.applyPredicate(rows(), "warehouseId == 'W1' || qty == 999")));
        assertEquals(List.of("r1", "r2", "r4"),
                ids(ConceptQueryFilterSupport.applyPredicate(rows(), "status == 'ACTIVE' || warehouseId == 'W2'")));
    }

    @Test
    @DisplayName("AND binds tighter than OR, with no parens needed")
    void andBindsTighterThanOr() {
        // (status == 'CLOSED' && warehouseId == 'W2') || status == 'ACTIVE' -> r4, r1, r2
        assertEquals(List.of("r1", "r2", "r4"),
                ids(ConceptQueryFilterSupport.applyPredicate(rows(),
                        "status == 'CLOSED' && warehouseId == 'W2' || status == 'ACTIVE'")));
    }

    @Test
    @DisplayName("IN: matches any value in the list")
    void inMatchesAnyListedValue() {
        assertEquals(List.of("r1", "r3"),
                ids(ConceptQueryFilterSupport.applyPredicate(rows(), "name in ('widget-alpha', 'widget-gamma', 'nope')")));
    }

    @Test
    @DisplayName("contains / startsWith: case-insensitive substring / prefix")
    void containsAndStartsWith() {
        assertEquals(List.of("r1", "r3"),
                ids(ConceptQueryFilterSupport.applyPredicate(rows(), "name contains 'WIDGET'")));
        assertEquals(List.of("r2"),
                ids(ConceptQueryFilterSupport.applyPredicate(rows(), "name startsWith 'gadget'")));
    }

    @Test
    @DisplayName("is-null / is-not-null")
    void isNullAndIsNotNull() {
        assertEquals(List.of("r2"), ids(ConceptQueryFilterSupport.applyPredicate(rows(), "supplierId is null")));
        assertEquals(List.of("r1", "r3", "r4"),
                ids(ConceptQueryFilterSupport.applyPredicate(rows(), "supplierId is not null")));
    }

    @Test
    @DisplayName("a reference-path clause without a resolver is refused, not silently treated as absent")
    void referencePathWithoutResolverThrows() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> ConceptQueryFilterSupport.applyPredicate(rows(), "supplier.country == 'UY'", "Order", null));
        assertTrue(thrown.getMessage().contains("ReferenceHopResolver"), thrown.getMessage());
    }

    @Test
    @DisplayName("a reference-path clause resolves through the supplied hop resolver, one hop")
    void referencePathResolvesOneHop() {
        Map<String, ConceptRecord> suppliers = Map.of(
                "sup-1", supplierRow("sup-1", "UY"),
                "sup-2", supplierRow("sup-2", "AR"));
        ConceptQueryFilterSupport.ReferenceHopResolver resolver = (fromConcept, fromRecord, referenceField) -> {
            if (!"Order".equals(fromConcept) || !"supplierId".equals(referenceField)) {
                return Optional.empty();
            }
            Object supplierId = fromRecord.data().get("supplierId");
            ConceptRecord supplier = supplierId == null ? null : suppliers.get(String.valueOf(supplierId));
            return supplier == null ? Optional.empty()
                    : Optional.of(new ConceptQueryFilterSupport.ReferenceHopResolver.ResolvedHop("Supplier", supplier));
        };
        // r1/r4 -> sup-1 -> UY; r3 -> sup-2 -> AR; r2 has no supplier at all (dangling/null hop).
        assertEquals(List.of("r1", "r4"),
                ids(ConceptQueryFilterSupport.applyPredicate(rows(), "supplierId.country == 'UY'", "Order", resolver)));
    }

    @Test
    @DisplayName("a dangling/null reference hop makes the clause false -- INNER JOIN semantics, not an error")
    void danglingReferenceHopExcludesTheRowRatherThanThrowing() {
        ConceptQueryFilterSupport.ReferenceHopResolver noSuppliersResolver =
                (fromConcept, fromRecord, referenceField) -> Optional.empty();
        assertEquals(List.of(),
                ids(ConceptQueryFilterSupport.applyPredicate(rows(), "supplierId.country == 'UY'", "Order", noSuppliersResolver)));
    }

    private static ConceptRecord supplierRow(String id, String country) {
        return new ConceptRecord("Supplier", id, "t1", Map.of("country", country));
    }

    @Test
    @DisplayName("real end-to-end proof: seeded rows through a governed gateway, filtered by an OR + IN predicate")
    void realGatewayDataFilteredByOrAndIn() {
        ConceptGateway gateway = GovernedTestGateways.forConcepts(ConceptSpec.of("Item", "status", "sku"));
        gateway.save(new ConceptWriteRequest("Item", "i-1", "tenant-a", Map.of("id", "i-1", "status", "ACTIVE", "sku", "A1")), CTX);
        gateway.save(new ConceptWriteRequest("Item", "i-2", "tenant-a", Map.of("id", "i-2", "status", "CLOSED", "sku", "B2")), CTX);
        gateway.save(new ConceptWriteRequest("Item", "i-3", "tenant-a", Map.of("id", "i-3", "status", "PENDING", "sku", "A3")), CTX);

        List<ConceptRecord> all = gateway.list(new ConceptListRequest("Item", "tenant-a", null, null), CTX);
        List<ConceptRecord> filtered = ConceptQueryFilterSupport.applyPredicate(all, "status == 'ACTIVE' || sku in ('A3')");

        assertEquals(List.of("i-1", "i-3"), ids(filtered));
    }
}
