package com.npdev.kernel;

import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.GovernedTestGateways;
import com.npdev.kernel.concepts.GovernedTestGateways.ConceptSpec;
import com.npdev.kernel.procedures.DefaultProcedureExecutor;
import com.npdev.kernel.procedures.ProcedureStep;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FORM P3-T5b: the merged step vocabulary's concept steps (set, readConcept, listConcepts, deleteConcept) and
 * callFlow run in the FLOW engine, by delegating to the procedure step implementations.
 */
class KernelRunnerFlowConceptStepsTest {

    private static final ExecutionContext CTX = ExecutionContext.of("tenant-a", "actor-a");

    private static ConceptGateway gatewayWithSibling() {
        ConceptGateway gateway = GovernedTestGateways.forConcepts(ConceptSpec.of("Sibling", "label", "quantity", "status"));
        gateway.save(new ConceptWriteRequest("Sibling", "S1", "tenant-a", Map.of("id", "S1", "label", "first")), CTX);
        gateway.save(new ConceptWriteRequest("Sibling", "S2", "tenant-a", Map.of("id", "S2", "label", "second")), CTX);
        return gateway;
    }

    private static KernelRunner runner(ConceptGateway gateway, InMemoryFlowDefinitionProvider flows) {
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(gateway);
        return new KernelRunner(event -> { }, (entityName, payload) -> List.of(), flows,
                (call, state) -> CapabilityResult.success(null)).withProcedureExecutor(executor);
    }

    @Test
    void readConceptBindsTheRecordAsAPlainMapAndLaterStepsReadIt() {
        ConceptGateway gateway = gatewayWithSibling();
        InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "ReadFlow", "Sibling", List.of(
                FlowStepDefinition.procedureStep("read", ProcedureStep.readConcept(
                        "read", "Sibling", "input.siblingId", "sibling"), "sibling"),
                FlowStepDefinition.returnValue("ret", "sibling.data.label"))));

        ExecutionResult result = runner(gateway, flows).execute("ReadFlow", Map.of("siblingId", "S2"), CTX);

        assertEquals(ExecutionStatus.OK, result.getStatus(), String.valueOf(result.getError()));
        assertEquals("second", result.getOutput());
    }

    @Test
    void readConceptOfAMissingRecordFailsTheFlowWithTheProcedureErrorCode() {
        InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "ReadFlow", "Sibling", List.of(
                FlowStepDefinition.procedureStep("read", ProcedureStep.readConcept(
                        "read", "Sibling", "input.siblingId", "sibling"), "sibling"))));

        ExecutionResult result = runner(gatewayWithSibling(), flows).execute("ReadFlow", Map.of("siblingId", "NOPE"), CTX);

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getError().contains("CONCEPT_NOT_FOUND"), "unexpected error: " + result.getError());
    }

    @Test
    void listConceptsBindsEveryRecord() {
        InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "ListFlow", "Sibling", List.of(
                FlowStepDefinition.procedureStep("list", ProcedureStep.listConcepts("list", "Sibling", "all"), "all"),
                FlowStepDefinition.returnValue("ret", "all"))));

        ExecutionResult result = runner(gatewayWithSibling(), flows).execute("ListFlow", Map.of(), CTX);

        assertEquals(ExecutionStatus.OK, result.getStatus(), String.valueOf(result.getError()));
        assertEquals(2, ((List<?>) result.getOutput()).size());
    }

    @Test
    void deleteConceptRemovesTheRecord() {
        ConceptGateway gateway = gatewayWithSibling();
        InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "DeleteFlow", "Sibling", List.of(
                FlowStepDefinition.procedureStep("delete", ProcedureStep.deleteConcept(
                        "delete", "Sibling", "input.siblingId"), null))));

        ExecutionResult result = runner(gateway, flows).execute("DeleteFlow", Map.of("siblingId", "S1"), CTX);

        assertEquals(ExecutionStatus.OK, result.getStatus(), String.valueOf(result.getError()));
        assertTrue(gateway.read(new ConceptReadRequest("Sibling", "S1", null), CTX).isEmpty());
        assertTrue(gateway.read(new ConceptReadRequest("Sibling", "S2", null), CTX).isPresent());
    }

    @Test
    void setBindsALiteralAndAStrictRef() {
        InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "SetFlow", "Sibling", List.of(
                FlowStepDefinition.procedureStep("lit", ProcedureStep.evaluate("lit", "'fixed'", "a"), "a"),
                FlowStepDefinition.procedureStep("ref", ProcedureStep.evaluate("ref", "$input.who", "b"), "b"),
                FlowStepDefinition.returnValue("ret", "b"))));

        ExecutionResult result = runner(gatewayWithSibling(), flows).execute("SetFlow", Map.of("who", "ana"), CTX);

        assertEquals(ExecutionStatus.OK, result.getStatus(), String.valueOf(result.getError()));
        assertEquals("ana", result.getOutput());
    }

    @Test
    void conceptStepWithoutAProcedureExecutorFailsWithANamedError() {
        InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider().register(new FlowDefinition(
                "ListFlow", "Sibling", List.of(
                FlowStepDefinition.procedureStep("list", ProcedureStep.listConcepts("list", "Sibling", "all"), "all"))));
        KernelRunner bare = new KernelRunner(event -> { }, (entityName, payload) -> List.of(), flows,
                (call, state) -> CapabilityResult.success(null));

        ExecutionResult result = bare.execute("ListFlow", Map.of(), CTX);

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getError().contains("NO_PROCEDURE_RUNNER"), "unexpected error: " + result.getError());
    }

    @Test
    void callFlowRunsTheCalleeAndBindsItsOutput() {
        InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition("Callee", "Sibling", List.of(
                        FlowStepDefinition.returnValue("ret", "input.greeting"))))
                .register(new FlowDefinition("Caller", "Sibling", List.of(
                        FlowStepDefinition.callFlow("call", "Callee", "input", "answer"),
                        FlowStepDefinition.returnValue("ret", "answer"))));

        ExecutionResult result = runner(gatewayWithSibling(), flows).execute("Caller", Map.of("greeting", "hello"), CTX);

        assertEquals(ExecutionStatus.OK, result.getStatus(), String.valueOf(result.getError()));
        assertEquals("hello", result.getOutput());
    }

    @Test
    void callFlowOfAnUnknownFlowFailsTheCallingStep() {
        InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition("Caller", "Sibling", List.of(
                        FlowStepDefinition.callFlow("call", "Ghost", "input", "answer"))));

        ExecutionResult result = runner(gatewayWithSibling(), flows).execute("Caller", Map.of(), CTX);

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getError().contains("CALL_FLOW_FAILED") && result.getError().contains("Ghost"),
                "unexpected error: " + result.getError());
    }

    @Test
    void aFlowThatCallsItselfFailsWithANamedDepthErrorInsteadOfOverflowing() {
        InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition("Loop", "Sibling", List.of(
                        FlowStepDefinition.callFlow("again", "Loop", "input", "x"))));

        ExecutionResult result = runner(gatewayWithSibling(), flows).execute("Loop", Map.of(), CTX);

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getError().contains("CALL_FLOW"), "unexpected error: " + result.getError());
    }

    @Test
    void runAsSystemElevatesEveryStepButNotAFlowThatDoesNotAskForIt() {
        java.util.List<ExecutionContext> seen = new java.util.ArrayList<>();
        com.npdev.kernel.procedures.ProcedureExecutor spy = (definition, input, context) -> {
            seen.add(context);
            return com.npdev.kernel.procedures.ProcedureExecutionResult.success(Map.of("all", List.of()), List.of());
        };
        ProcedureStep list = ProcedureStep.listConcepts("list", "Sibling", "all");
        List<FlowStepDefinition> steps = List.of(FlowStepDefinition.procedureStep("list", list, "all"));
        InMemoryFlowDefinitionProvider flows = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition("Elevated", "Sibling", steps, null, null, true))
                .register(new FlowDefinition("AsCaller", "Sibling", steps));
        KernelRunner runner = new KernelRunner(event -> { }, (entityName, payload) -> List.of(), flows,
                (call, state) -> CapabilityResult.success(null)).withProcedureExecutor(spy);

        assertEquals(ExecutionStatus.OK, runner.execute("Elevated", Map.of(), CTX).getStatus());
        assertEquals(ExecutionStatus.OK, runner.execute("AsCaller", Map.of(), CTX).getStatus());

        assertTrue(ExecutionContext.isSystem(seen.get(0)), "runAs system: the step runs as the system principal");
        assertEquals("tenant-a", seen.get(0).tenantId(), "the tenant never changes");
        assertEquals(CTX, seen.get(1), "a flow that does not ask for it keeps the caller's context");
        assertEquals(null, com.npdev.kernel.procedures.ProcedureElevation.onBehalfOf(), "the elevation is undone afterwards");
    }
}
