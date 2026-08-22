package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFieldAccess;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R5.5 (roadmap Wave 1, 2026-08-19): server-enforced field-level access, exercised through
 * {@link DefaultConceptGateway#governedBy(com.npdev.kernel.ports.ConceptStore, CompiledModel)} --
 * the SAME governed policy a real generated app runs (see that method's own javadoc, and
 * {@code DefaultConceptGatewayRowAuthzRaceTest#ticketModel} for the row-level precedent this test
 * mirrors one rung down). Proves the DONE-WHEN at the gateway layer: a non-manager write on a
 * manager-write field is rejected, and a denied field never leaks its value on read -- through a
 * {@link CompiledModel} built with real {@link CompiledFieldAccess}, not a hand-built
 * {@code ConfiguredConceptGatewaySemanticPolicy.FieldDefinition} that would bypass the DSL
 * compiled-model shape entirely.
 */
class DefaultConceptGatewayFieldAccessTest {

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
                                new CompiledFieldAccess(
                                        "$user.actorId == 'manager-1'",
                                        "$user.actorId == 'manager-1'"
                                )
                        )
                ),
                List.of(), List.of(), null, null, null, null, List.of(), null
        );
        return new CompiledModel("test", "1.0.0", "1.0.0", Map.of(payroll.getName(), payroll));
    }

    private static ExecutionContext manager() {
        return new ExecutionContext("tenant-a", "manager-1", Map.of(), Set.of("MANAGER"));
    }

    private static ExecutionContext nonManager() {
        return new ExecutionContext("tenant-a", "operator-a", Map.of(), Set.of("USER"));
    }

    @Test
    void nonManagerWriteOnManagerWriteFieldIsRejected() {
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(), payrollModel());
        gateway.save(
                new ConceptWriteRequest("Payroll", "emp-1", "tenant-a",
                        Map.of("id", "emp-1", "employeeName", "Ana", "salary", 1000)),
                manager()
        );

        ConceptGatewayAccessDeniedException exception = assertThrows(
                ConceptGatewayAccessDeniedException.class,
                () -> gateway.save(
                        new ConceptWriteRequest("Payroll", "emp-1", "tenant-a",
                                Map.of("id", "emp-1", "employeeName", "Ana", "salary", 9000)),
                        nonManager()
                )
        );
        assertEquals("FIELD_SCOPE_DENIED", exception.code());

        // Deny must reject the WHOLE write, not silently drop just the denied field -- the stored
        // salary is still the manager's original value, not partially updated.
        ConceptRecord persisted = gateway.read(new ConceptReadRequest("Payroll", "emp-1", "tenant-a"), manager())
                .orElseThrow();
        assertEquals(1000, persisted.data().get("salary"));
    }

    @Test
    void deniedReadFieldIsOmittedNotMaskedForNonManager() {
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(), payrollModel());
        gateway.save(
                new ConceptWriteRequest("Payroll", "emp-1", "tenant-a",
                        Map.of("id", "emp-1", "employeeName", "Ana", "salary", 1000)),
                manager()
        );

        ConceptRecord nonManagerView = gateway.read(new ConceptReadRequest("Payroll", "emp-1", "tenant-a"), nonManager())
                .orElseThrow();
        ConceptRecord managerView = gateway.read(new ConceptReadRequest("Payroll", "emp-1", "tenant-a"), manager())
                .orElseThrow();

        assertFalse(nonManagerView.data().containsKey("salary"),
                "a denied field must be ABSENT from the response, not present as null -- absence is "
                        + "the only shape that never confirms whether a value exists");
        assertEquals("Ana", nonManagerView.data().get("employeeName"));
        assertEquals(1000, managerView.data().get("salary"));
    }

    @Test
    void managerCanChangeTheManagerWriteField() {
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(), payrollModel());
        gateway.save(
                new ConceptWriteRequest("Payroll", "emp-1", "tenant-a",
                        Map.of("id", "emp-1", "employeeName", "Ana", "salary", 1000)),
                manager()
        );

        ConceptRecord updated = gateway.save(
                new ConceptWriteRequest("Payroll", "emp-1", "tenant-a",
                        Map.of("id", "emp-1", "employeeName", "Ana", "salary", 1200)),
                manager()
        );

        assertEquals(1200, updated.data().get("salary"));
    }
}
