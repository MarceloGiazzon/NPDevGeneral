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

    private static CompiledModel payrollModel() {
        CompiledConcept payroll = new CompiledConcept(
                "Payroll", "Payroll", "payrolls",
                List.of(
                        new CompiledField("id", "string", "String", true, true, false),
                        new CompiledField("employeeName", "string", "String", false, false, false),
                        new CompiledField(
                                "salary", "int", "Integer", false, false, false,
                                List.of(), null, null, null, null, List.of(), null, null, null, null,
                                false, null,
                                new com.npdev.dsl.v1.compiled.CompiledFieldAccess(
                                        null, "$user.actorId == 'manager-1'"))
                ),
                List.of(), List.of(), null, null, null, null, List.of(), null
        );
        return new CompiledModel("test", "1.0.0", "1.0.0", Map.of(payroll.getName(), payroll));
    }

    private static final ExecutionContext MANAGER =
            new ExecutionContext("tenant-a", "manager-1", Map.of(), java.util.Set.of("MANAGER"));
    private static final ExecutionContext OPERATOR =
            new ExecutionContext("tenant-a", "operator-a", Map.of(), java.util.Set.of("USER"));

    @Test
    void createSettingAFieldTheActorMayNotWriteIsRefusedAndNothingIsStored() {
        InMemoryConceptStore store = new InMemoryConceptStore();
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(store, payrollModel());

        ConceptGatewayAccessDeniedException denied = assertThrows(ConceptGatewayAccessDeniedException.class,
                () -> gateway.authorizeFlowWrite("Payroll", null,
                        Map.of("employeeName", "Ana", "salary", 9000), false, OPERATOR));

        assertEquals("FIELD_SCOPE_DENIED", denied.code());
        assertTrue(store.findAll("tenant-a", "Payroll").isEmpty(), "the check persists nothing");
        assertDoesNotThrow(() -> gateway.authorizeFlowWrite("Payroll", null,
                Map.of("employeeName", "Ana", "salary", 9000), false, MANAGER));
        assertDoesNotThrow(() -> gateway.authorizeFlowWrite("Payroll", null,
                Map.of("employeeName", "Ana"), false, OPERATOR), "a create that leaves the protected field alone is fine");
    }

    @Test
    void updateChangingAProtectedFieldIsRefusedButResendingItsStoredValueIsNot() {
        InMemoryConceptStore store = new InMemoryConceptStore();
        store.save(new ConceptRecord("Payroll", "P1", "tenant-a", Map.of("employeeName", "Ana", "salary", 1000)));
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(store, payrollModel());

        assertThrows(ConceptGatewayAccessDeniedException.class, () -> gateway.authorizeFlowWrite(
                "Payroll", "P1", Map.of("salary", 9000), true, OPERATOR));
        assertDoesNotThrow(() -> gateway.authorizeFlowWrite(
                "Payroll", "P1", Map.of("employeeName", "Bia", "salary", 1000), true, OPERATOR),
                "REST ignores an unchanged protected field, and so does the flow check");
        assertDoesNotThrow(() -> gateway.authorizeFlowWrite(
                "Payroll", "P1", Map.of("salary", 9000), true, MANAGER));
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
