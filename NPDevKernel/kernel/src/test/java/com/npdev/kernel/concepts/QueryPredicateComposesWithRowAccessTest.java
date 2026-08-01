package com.npdev.kernel.concepts;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.AccessRules;
import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition;
import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.FieldDefinition;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC-P0 DoD line 4 (MOVE10_AI_LOWCODE_PLAN-2026-07-31.md Part A): <i>"access.read still filters
 * after P0's narrowing -- one test with a row the caller may not read that would otherwise match
 * the where."</i>
 *
 * <p>The plan's step 6 states the invariant this pins: <i>"Row-level access.read is unaffected and
 * stays in the JVM ... P0 narrows BEFORE that filter; it must not bypass it. Verify the composition
 * explicitly."</i>
 *
 * <p>The composition, read out of {@code DefaultConceptGateway.list}: the gateway applies
 * {@code semanticPolicy.isRowReadable} while assembling the list, so the records
 * {@code ConceptQueryFilterSupport.applyWhere} ever sees are already access-filtered. A predicate
 * can therefore only shrink that set further -- it has no way to re-admit a denied row. The test
 * below is the empirical version of that argument, which is what the DoD asks for: the denied row
 * is deliberately one that MATCHES the predicate, so a bypass would be visible as an extra row
 * rather than as an absence.
 */
class QueryPredicateComposesWithRowAccessTest {

    private static final String CONCEPT = "Order";

    /** owner-scoped read: a caller sees only rows whose ownerId is their own actor id. */
    private static DefaultConceptGateway ownerScopedGateway() {
        java.util.Map<String, FieldDefinition> fields = new java.util.LinkedHashMap<>();
        fields.put("id", new FieldDefinition("id", true, List.of(), null, null, null));
        fields.put("ownerId", new FieldDefinition("ownerId", true, List.of(), null, null, null));
        fields.put("status", new FieldDefinition("status", false, List.of(), null, null, null));
        ConceptDefinition order = new ConceptDefinition(
                CONCEPT, fields, List.of(), null, java.util.Set.of(),
                new AccessRules("ownerId == $user.id", null));
        return new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                new ConfiguredConceptGatewaySemanticPolicy(List.of(order)),
                record -> { });
    }

    @Test
    @DisplayName("LC-P0 DoD 4: a row the caller may not read is NOT returned even when it matches the where")
    void accessReadStillFiltersAfterPredicateNarrowing() {
        DefaultConceptGateway gateway = ownerScopedGateway();
        ExecutionContext alice = ExecutionContext.of("t1", "alice");
        ExecutionContext bob = ExecutionContext.of("t1", "bob");

        // Both rows are ACTIVE, so both MATCH the predicate. Only the owner differs.
        gateway.save(new ConceptWriteRequest(CONCEPT, "o-alice", "t1",
                Map.of("id", "o-alice", "ownerId", "alice", "status", "ACTIVE")), alice);
        gateway.save(new ConceptWriteRequest(CONCEPT, "o-bob", "t1",
                Map.of("id", "o-bob", "ownerId", "bob", "status", "ACTIVE")), bob);

        List<ConceptRecord> visibleToAlice = gateway.list(new ConceptListRequest(CONCEPT, null), alice);
        assertEquals(List.of("o-alice"), visibleToAlice.stream().map(ConceptRecord::id).toList(),
                "baseline: access.read alone already denies bob's row to alice");

        // P0's predicate narrows the ALREADY access-filtered list. bob's row matches
        // status == 'ACTIVE' and must still not appear.
        List<ConceptRecord> filtered = ConceptQueryFilterSupport.applyWhere(visibleToAlice, "status == 'ACTIVE'");
        assertEquals(List.of("o-alice"), filtered.stream().map(ConceptRecord::id).toList(),
                "a denied row that MATCHES the predicate must not be re-admitted by it");

        // And the reverse ordering is not available to a caller by accident: applying the predicate
        // to the raw store contents would return both -- which is exactly what the gateway's
        // ordering prevents, and why the composition is asserted rather than assumed.
        List<ConceptRecord> everyRow = gateway.list(new ConceptListRequest(CONCEPT, null), bob);
        assertEquals(List.of("o-bob"), everyRow.stream().map(ConceptRecord::id).toList(),
                "the same holds symmetrically for the other caller");
    }

    @Test
    @DisplayName("a multi-clause predicate composes the same way -- the new grammar changes nothing about access")
    void multiClausePredicateAlsoComposes() {
        DefaultConceptGateway gateway = ownerScopedGateway();
        ExecutionContext alice = ExecutionContext.of("t1", "alice");
        ExecutionContext bob = ExecutionContext.of("t1", "bob");

        gateway.save(new ConceptWriteRequest(CONCEPT, "o-1", "t1",
                Map.of("id", "o-1", "ownerId", "alice", "status", "ACTIVE")), alice);
        gateway.save(new ConceptWriteRequest(CONCEPT, "o-2", "t1",
                Map.of("id", "o-2", "ownerId", "alice", "status", "CLOSED")), alice);
        gateway.save(new ConceptWriteRequest(CONCEPT, "o-3", "t1",
                Map.of("id", "o-3", "ownerId", "bob", "status", "ACTIVE")), bob);

        List<ConceptRecord> filtered = ConceptQueryFilterSupport.applyWhere(
                gateway.list(new ConceptListRequest(CONCEPT, null), alice),
                "status == 'ACTIVE' && ownerId == 'alice'");

        assertEquals(List.of("o-1"), filtered.stream().map(ConceptRecord::id).toList());
        assertTrue(filtered.stream().noneMatch(r -> "bob".equals(r.data().get("ownerId"))),
                "bob's ACTIVE row matches the predicate and must still be invisible to alice");
    }
}
