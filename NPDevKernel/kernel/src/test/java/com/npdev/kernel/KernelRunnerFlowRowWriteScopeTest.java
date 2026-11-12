package com.npdev.kernel;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledConceptAccess;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * REG-338: a flow's createConcept / updateConcept step is checked against the concept's row-level
 * {@code access.write} rule as the flow's actor, before the persistence capability is ever called.
 */
final class KernelRunnerFlowRowWriteScopeTest {

    private static final ExecutionContext ALICE = ExecutionContext.of("default", "alice");
    private static final ExecutionContext BOB = ExecutionContext.of("default", "bob");

    private static CompiledModel ticketModel() {
        CompiledConcept ticket = new CompiledConcept(
                "Ticket", "Ticket", "tickets",
                List.of(
                        new CompiledField("id", "string", "String", true, true, false),
                        new CompiledField("ownerId", "string", "String", false, true, false)
                ),
                List.of(), List.of(), null, null, null, null, List.of(),
                new CompiledConceptAccess(null, "ownerId == $user.id")
        );
        return new CompiledModel("test", "1.0.0", "1.0.0", Map.of(ticket.getName(), ticket));
    }

    private static final class Rig {
        final AtomicInteger persisted = new AtomicInteger();
        final KernelRunner runner;

        Rig(String mode, ConceptGateway gateway) {
            FlowStepDefinition save = FlowStepDefinition
                    .capabilityCall("save", "persistence", "save", "$input", "$saved")
                    .withConceptWrite(new FlowStepDefinition.ConceptWrite(mode, List.of()));
            InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider().register(
                    new FlowDefinition("FileTicket", "Ticket",
                            List.of(save, FlowStepDefinition.returnValue("return", "$saved")), null, null));
            KernelRunner built = new KernelRunner(
                    event -> { },
                    (entityName, payload) -> List.of(),
                    flows,
                    (call, state) -> {
                        persisted.incrementAndGet();
                        return CapabilityResult.success(Map.of("id", "T1"));
                    },
                    com.npdev.kernel.ports.ExecutionTracer.NOOP,
                    null,
                    FlowInstanceStore.noop(),
                    CorrelationOwnershipStore.noop(),
                    CircuitBreakerStateStore.noop(),
                    BulkheadStore.noop(),
                    com.npdev.kernel.ports.IdempotencyStore.noop(),
                    JsonCodec.noop(),
                    (schema, payload) -> List.of()
            );
            this.runner = gateway == null ? built : built.withConceptGateway(gateway);
        }
    }

    @Test
    void createNamingAnotherOwnerIsRefusedAndThePersistenceCapabilityIsNeverCalled() {
        Rig rig = new Rig(FlowStepDefinition.ConceptWrite.CREATE,
                DefaultConceptGateway.governedBy(new InMemoryConceptStore(), ticketModel()));

        ExecutionResult result = rig.runner.execute("FileTicket", Map.of("ownerId", "alice"), BOB);

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertEquals("CAPABILITY_WRITE_SCOPE_DENIED", result.getCapabilityError().code());
        assertEquals(CapabilityErrorKind.AUTH, result.getCapabilityError().kind());
        assertEquals(0, rig.persisted.get(), "a refused filing plants nothing");
    }

    @Test
    void createForTheActorsOwnRowRuns() {
        Rig rig = new Rig(FlowStepDefinition.ConceptWrite.CREATE,
                DefaultConceptGateway.governedBy(new InMemoryConceptStore(), ticketModel()));

        ExecutionResult result = rig.runner.execute("FileTicket", Map.of("ownerId", "alice"), ALICE);

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals(1, rig.persisted.get());
    }

    @Test
    void updateOfARowTheActorDoesNotOwnIsRefused() {
        InMemoryConceptStore store = new InMemoryConceptStore();
        store.save(new ConceptRecord("Ticket", "T1", "default", Map.of("ownerId", "alice")));
        Rig rig = new Rig(FlowStepDefinition.ConceptWrite.UPDATE, DefaultConceptGateway.governedBy(store, ticketModel()));

        ExecutionResult refused = rig.runner.execute("FileTicket", Map.of("id", "T1", "note", "x"), BOB);
        assertEquals("CAPABILITY_WRITE_SCOPE_DENIED", refused.getCapabilityError().code());
        assertEquals(0, rig.persisted.get());

        ExecutionResult allowed = rig.runner.execute("FileTicket", Map.of("id", "T1", "note", "x"), ALICE);
        assertEquals(ExecutionStatus.OK, allowed.getStatus());
        assertEquals(1, rig.persisted.get());
    }

    @Test
    void createSettingAProtectedFieldIsRefusedAsAFieldWriteDenialBeforeThePersistenceCapabilityIsCalled() {
        CompiledConcept account = new CompiledConcept(
                "Ticket", "Ticket", "tickets",
                List.of(
                        new CompiledField("id", "string", "String", true, true, false),
                        new CompiledField("ownerId", "string", "String", false, true, false),
                        new CompiledField(
                                "priority", "string", "String", false, false, false,
                                List.of(), null, null, null, null, List.of(), null, null, null, null,
                                false, null,
                                new com.npdev.dsl.v1.compiled.CompiledFieldAccess(null, "$user.actorId == 'boss'"))
                ),
                List.of(), List.of(), null, null, null, null, List.of(), null
        );
        Rig rig = new Rig(FlowStepDefinition.ConceptWrite.CREATE, DefaultConceptGateway.governedBy(
                new InMemoryConceptStore(),
                new CompiledModel("test", "1.0.0", "1.0.0", Map.of(account.getName(), account))));

        ExecutionResult refused = rig.runner.execute("FileTicket", Map.of("ownerId", "alice", "priority", "URGENT"), ALICE);

        assertEquals("CAPABILITY_WRITE_SCOPE_DENIED", refused.getCapabilityError().code());
        assertEquals("FIELD_SCOPE_DENIED", refused.getCapabilityError().details().get("gatewayCode"));
        assertEquals(0, rig.persisted.get());
        assertEquals(ExecutionStatus.OK,
                rig.runner.execute("FileTicket", Map.of("ownerId", "alice"), ALICE).getStatus());
    }

    @Test
    void flowWithoutAConceptGatewayKeepsItsPreviousBehaviour() {
        Rig rig = new Rig(FlowStepDefinition.ConceptWrite.CREATE, null);

        ExecutionResult result = rig.runner.execute("FileTicket", Map.of("ownerId", "alice"), BOB);

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals(1, rig.persisted.get());
    }

    @Test
    void platformPrincipalIsNotRowChecked() {
        Rig rig = new Rig(FlowStepDefinition.ConceptWrite.CREATE,
                DefaultConceptGateway.governedBy(new InMemoryConceptStore(), ticketModel()));

        ExecutionResult result = rig.runner.execute(
                "FileTicket", Map.of("ownerId", "alice"), ExecutionContext.system("default"));

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals(1, rig.persisted.get(), "scheduler / seeding rules scope callers, not the platform");
    }

    @Test
    void aStepAfterAResumeIsCheckedAsTheOriginalActorNotAsTheTrustedResumeRole() {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("actorId", "bob");
        FlowActor.stamp(state, ExecutionContext.of("default", "bob").withRoles(Set.of("USER", "employee")));
        ExecutionContext resumed = ExecutionContext.resuming("default", "bob");

        ExecutionContext checked = FlowActor.writeCheckContext(state, resumed);

        assertNotNull(checked);
        assertEquals("bob", checked.actorId());
        assertEquals(Set.of("USER", "EMPLOYEE"), checked.roles(), "the resume role ADMIN must not widen the check");
    }

    @Test
    void platformFlowResumedLaterIsStillNotChecked() {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("actorId", "system:scheduler");
        FlowActor.stamp(state, ExecutionContext.system("default"));

        assertNull(FlowActor.writeCheckContext(state, ExecutionContext.resuming("default", "system:scheduler")));
    }

    @Test
    void instanceThatPredatesTheActorSnapshotIsNotChecked() {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("actorId", "bob");

        assertNull(FlowActor.writeCheckContext(state, ExecutionContext.resuming("default", "bob")));
    }
}
