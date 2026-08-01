package com.npdev.kernel;

import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.concepts.GovernedTestGateways;
import com.npdev.kernel.concepts.GovernedTestGateways.ConceptSpec;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.procedures.DefaultProcedureExecutor;
import com.npdev.kernel.procedures.ProcedureDefinition;
import com.npdev.kernel.procedures.ProcedureStep;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): proves a FLOW can now reach patchConcept
 * through the new {@code callProcedure} step -- the exact asymmetry REG-77 named ("procedure ->
 * procedure CALL_PROCEDURE exists; flow -> procedure DOES NOT EXIST"). Before this, a flow like
 * {@code AtivarCrossDocking} could never invoke a procedure at all, so it could never reach
 * patchConcept's read-modify-write -- only procedure-bound panel actions (Concluir/Cancelar) could.
 */
class KernelRunnerCallProcedureTest {

    @Test
    void flowCallProcedureReachesPatchConceptAndPreservesUnrelatedFields() {
        ConceptGateway gateway = GovernedTestGateways.forConcepts(ConceptSpec.of("Sibling", "label", "quantity", "status"));
        ExecutionContext ctx = ExecutionContext.of("tenant-a", "actor-a");
        gateway.save(new ConceptWriteRequest("Sibling", "S1", "tenant-a",
                Map.of("id", "S1", "flag", "false", "other", "kept")), ctx);

        DefaultProcedureExecutor procedureExecutor = new DefaultProcedureExecutor(
                gateway,
                (call, state) -> CapabilityResult.success(null),
                event -> { }
        );
        ProcedureDefinition definition = new ProcedureDefinition(
                "SetSiblingFlagProcedure",
                List.of(
                        ProcedureStep.patchConcept("patch-sibling", "Sibling", "$input.siblingId",
                                Map.of("flag", "true"), "patched"),
                        ProcedureStep.returnValue("return-patched", "$patched")
                )
        );
        Map<String, ProcedureDefinition> registry = Map.of("SetSiblingFlagProcedure", definition);

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "ActivateFlow",
                        "Sibling",
                        List.of(
                                FlowStepDefinition.callProcedure(
                                        "call-set-flag", "SetSiblingFlagProcedure", "input", "result"),
                                FlowStepDefinition.returnValue("return-result", "result")
                        )
                ));

        KernelRunner runner = new KernelRunner(
                event -> { },
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> CapabilityResult.success(null)
        ).withProcedureExecutor(procedureExecutor, registry);

        ExecutionResult result = runner.execute("ActivateFlow", Map.of("siblingId", "S1"), ctx);

        assertEquals(ExecutionStatus.OK, result.getStatus(), "flow with a callProcedure step must complete OK: " + result.getError());
        assertEquals("true", gateway.read(new ConceptReadRequest("Sibling", "S1", null), ctx).get().data().get("flag"),
                "callProcedure's patchConcept must have flipped the sibling's flag");
        assertEquals("kept", gateway.read(new ConceptReadRequest("Sibling", "S1", null), ctx).get().data().get("other"),
                "patchConcept must preserve fields it does not name in set");
    }

    @Test
    void flowCallProcedureFailsCleanlyWhenNoProcedureExecutorIsWired() {
        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "ActivateFlow",
                        "Sibling",
                        List.of(FlowStepDefinition.callProcedure(
                                "call-set-flag", "SetSiblingFlagProcedure", "input", "result"))
                ));

        // Deliberately no .withProcedureExecutor(...) -- proves the null-tolerant path fails with a
        // clear, named error instead of an NPE, matching AggregateRuntime's own optional-dependency
        // precedent for a ProcedureRunner it hasn't been given either.
        KernelRunner runner = new KernelRunner(
                event -> { },
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> CapabilityResult.success(null)
        );

        ExecutionResult result = runner.execute("ActivateFlow", Map.of("siblingId", "S1"));

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getError().contains("NO_PROCEDURE_RUNNER"), "unexpected error: " + result.getError());
    }

    @Test
    void flowCallProcedureFailsCleanlyWhenProcedureNameIsUnknown() {
        DefaultProcedureExecutor procedureExecutor = new DefaultProcedureExecutor(
                GovernedTestGateways.forConcepts(ConceptSpec.of("Sibling", "label", "quantity", "status")),
                (call, state) -> CapabilityResult.success(null),
                event -> { }
        );

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "ActivateFlow",
                        "Sibling",
                        List.of(FlowStepDefinition.callProcedure(
                                "call-set-flag", "NoSuchProcedure", "input", "result"))
                ));

        KernelRunner runner = new KernelRunner(
                event -> { },
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> CapabilityResult.success(null)
        ).withProcedureExecutor(procedureExecutor, Map.of());

        ExecutionResult result = runner.execute("ActivateFlow", Map.of("siblingId", "S1"));

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getError().contains("PROCEDURE_NOT_FOUND"), "unexpected error: " + result.getError());
    }
}
