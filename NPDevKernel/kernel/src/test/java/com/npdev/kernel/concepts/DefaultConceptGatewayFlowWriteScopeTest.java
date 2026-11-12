package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledConceptAccess;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-338: {@link DefaultConceptGateway#authorizeFlowWrite} is the row-level {@code access.write}
 * gate a flow's createConcept / updateConcept step runs as the flow's actor. It must refuse exactly
 * what {@link DefaultConceptGateway#save} refuses for scope, and persist nothing either way.
 */
class DefaultConceptGatewayFlowWriteScopeTest {

    private static CompiledModel model() {
        CompiledConcept ticket = new CompiledConcept(
                "Ticket", "Ticket", "tickets",
                List.of(
                        new CompiledField("id", "string", "String", true, true, false),
                        new CompiledField("ownerId", "string", "String", false, true, false),
                        new CompiledField("status", "string", "String", false, true, false)
                ),
                List.of(), List.of(), null, null, null, null, List.of(),
                new CompiledConceptAccess(null, "ownerId == $user.id")
        );
        CompiledConcept open = new CompiledConcept(
                "Note", "Note", "notes",
                List.of(new CompiledField("id", "string", "String", true, true, false)),
                List.of(), List.of(), null, null, null, null, List.of(),
                new CompiledConceptAccess(null, null)
        );
        return new CompiledModel("test", "1.0.0", "1.0.0", Map.of(ticket.getName(), ticket, open.getName(), open));
    }

    private static final ExecutionContext ALICE = ExecutionContext.of("tenant-a", "alice");
    private static final ExecutionContext BOB = ExecutionContext.of("tenant-a", "bob");

    @Test
    void createNamingAnotherOwnerIsRefusedAndNothingIsStored() {
        InMemoryConceptStore store = new InMemoryConceptStore();
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(store, model());

        ConceptGatewayAccessDeniedException denied = assertThrows(ConceptGatewayAccessDeniedException.class,
                () -> gateway.authorizeFlowWrite("Ticket", null, Map.of("ownerId", "alice"), false, BOB));

        assertEquals("ROW_SCOPE_DENIED", denied.code());
        assertTrue(store.findAll("tenant-a", "Ticket").isEmpty(), "the check persists nothing");
    }

    @Test
    void createForTheActorsOwnRowIsAllowed() {
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(), model());

        assertDoesNotThrow(() -> gateway.authorizeFlowWrite("Ticket", null, Map.of("ownerId", "alice"), false, ALICE));
    }

    @Test
    void updateOfARowTheActorDoesNotOwnIsRefused() {
        InMemoryConceptStore store = new InMemoryConceptStore();
        store.save(new ConceptRecord("Ticket", "T1", "tenant-a", Map.of("ownerId", "alice", "status", "Open")));
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(store, model());

        assertThrows(ConceptGatewayAccessDeniedException.class,
                () -> gateway.authorizeFlowWrite("Ticket", "T1", Map.of("status", "Closed"), true, BOB));
        assertDoesNotThrow(() -> gateway.authorizeFlowWrite("Ticket", "T1", Map.of("status", "Closed"), true, ALICE));
        assertEquals("Open", store.findById("tenant-a", "Ticket", "T1").orElseThrow().data().get("status"),
                "the check persists nothing");
    }

    @Test
    void updateThatHandsTheRowToSomeoneElseIsRefused() {
        InMemoryConceptStore store = new InMemoryConceptStore();
        store.save(new ConceptRecord("Ticket", "T1", "tenant-a", Map.of("ownerId", "alice", "status", "Open")));
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(store, model());

        assertThrows(ConceptGatewayAccessDeniedException.class,
                () -> gateway.authorizeFlowWrite("Ticket", "T1", Map.of("ownerId", "bob"), true, ALICE),
                "SEC-16: a caller may not rewrite a row they own so it falls outside their scope");
    }

    @Test
    void updateOfAMissingRowChecksNothingSoTheStepReportsItMissing() {
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(), model());

        assertDoesNotThrow(() -> gateway.authorizeFlowWrite("Ticket", "NOPE", Map.of("status", "x"), true, BOB));
        assertDoesNotThrow(() -> gateway.authorizeFlowWrite("Ticket", null, Map.of("status", "x"), true, BOB));
    }

    @Test
    void conceptWithoutAWriteRuleIsNeverRefused() {
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(), model());

        assertDoesNotThrow(() -> gateway.authorizeFlowWrite("Note", null, Map.of("body", "x"), false, BOB));
    }

    @Test
    void anotherTenantsContextCannotReachARowOfThisTenant() {
        InMemoryConceptStore store = new InMemoryConceptStore();
        store.save(new ConceptRecord("Ticket", "T1", "tenant-a", Map.of("ownerId", "alice")));
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(store, model());
        ExecutionContext otherTenantAlice = ExecutionContext.of("tenant-b", "alice");

        // tenant-b's lookup of T1 finds nothing, so there is no row to scope-check; the step's own
        // update path then reports it missing -- the same outcome as REST.
        assertDoesNotThrow(() ->
                gateway.authorizeFlowWrite("Ticket", "T1", Map.of("status", "x"), true, otherTenantAlice));
    }
}
