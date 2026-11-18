package com.npdev.kernel.procedures;

import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.GovernedTestGateways;
import com.npdev.kernel.concepts.GovernedTestGateways.ConceptSpec;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultProcedureExecutorTest {

    @Test
    void failsDeterministicallyWhenTheLoopLimitIsExceeded() {
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                GovernedTestGateways.forConcepts(GovernedTestGateways.ConceptSpec.of("Item", "name")),
                new ProcedureExecutionLimits(100, 10, 1),
                Map.of(),
                null
        );
        ProcedureDefinition definition = new ProcedureDefinition(
                "Loop",
                List.of(ProcedureStep.mapList("limited-loop", "$items", "item", Map.of("v", "$item"), "out"))
        );

        ProcedureExecutionResult result = executor.execute(
                definition,
                Map.of("items", List.of("one", "two")),
                ExecutionContext.of("tenant-a", "actor-a")
        );

        assertFalse(result.ok());
        assertEquals("PROCEDURE_LOOP_LIMIT_EXCEEDED", result.failureCode());
    }

    @Test
    void stopsAtTheFirstFailingStepAndKeepsTheStepResultsSoFar() {
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                GovernedTestGateways.forConcepts(GovernedTestGateways.ConceptSpec.of("Item", "name")));
        ProcedureDefinition definition = new ProcedureDefinition(
                "TwoSteps",
                List.of(
                        ProcedureStep.evaluate("first", "1 + 1", "a"),
                        ProcedureStep.evaluate("second", "$missing", "b"),
                        ProcedureStep.evaluate("third", "3", "c"))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), ExecutionContext.of("tenant-a", "actor-a"));

        assertFalse(result.ok());
        assertEquals("REF_UNRESOLVABLE", result.failureCode());
        assertEquals(2, result.steps().size());
    }
}
