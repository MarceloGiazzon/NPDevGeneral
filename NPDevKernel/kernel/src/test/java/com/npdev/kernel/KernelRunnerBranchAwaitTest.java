package com.npdev.kernel;

import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** An awaitEvent inside a branch arm must park the flow (WAITING_EVENT), not be skipped. */
class KernelRunnerBranchAwaitTest {

    @Test
    void awaitInsideTakenBranchParksTheFlow() {
        EventBus eventBus = envelope -> {
        };
        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "BranchAwaitFlow",
                        "Ride",
                        List.of(
                                FlowStepDefinition.branch(
                                        "offered",
                                        "$input.go == true",
                                        List.of(FlowStepDefinition.awaitEvent(
                                                "wait-answer", "RideOfferAnswered", "$a1", false, Map.of("rideId", "$input.rideId"))
                                                .withTimeout(20L, List.of(FlowStepDefinition.map("expire", "$input", "$x1")))),
                                        List.of()
                                ),
                                FlowStepDefinition.returnValue("return-done", "$input.go")
                        )
                ));

        KernelRunner runner = new KernelRunner(
                eventBus,
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> CapabilityResult.success(null)
        );
        ExecutionResult result = runner.execute("BranchAwaitFlow", Map.of("correlationId", "corr-1", "go", true));

        assertEquals(ExecutionStatus.WAITING_EVENT, result.getStatus());
        assertEquals("wait-answer", result.getAwaitedStepName());
    }

    @Test
    void procedureOutputHoldingAConceptRecordResolvesLikeItsPersistedJsonShape() {
        // A callProcedure output is flow state; a branch on $m1.data.status must see the same Map shape
        // before and after the flow parks and reloads (it only did after).
        Object plain = CallProcedureStep.plainTree(Map.of("ride", new com.npdev.kernel.concepts.ConceptRecord(
                "Ride", "r1", "dev", Map.of("status", "ASSIGNED"))));
        Map<String, Object> state = new java.util.HashMap<>();
        state.put("m1", ((Map<?, ?>) plain).get("ride"));
        assertEquals(true, KernelRunner.evaluateCondition("$m1.data.status == 'ASSIGNED'", state, Map.of()));
        assertEquals(true, KernelRunner.evaluateCondition("$m1.id == 'r1'", state, Map.of()));
    }
}
