package com.npdev.kernel.procedures;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.GovernedTestGateways;
import com.npdev.kernel.concepts.GovernedTestGateways.ConceptSpec;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FORM P3-T5c (a): the flow {@code set} step evaluates ONE expression strictly. */
class DefaultProcedureExecutorEvaluateTest {

    private static final ExecutionContext CTX = ExecutionContext.of("tenant-a", "actor-a");
    private static final EventBus NOOP_BUS = event -> { };

    private static ProcedureExecutionResult run(Map<String, Object> input, ProcedureStep... steps) {
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(GovernedTestGateways.forConcepts(ConceptSpec.of("X", "a")));
        return executor.execute(new ProcedureDefinition("E", List.of(steps)), input, CTX);
    }

    @Test
    void arithmeticOverRefsAndQuotedLiterals() {
        ProcedureExecutionResult result = run(Map.of("price", 3, "qty", 4),
                ProcedureStep.evaluate("a", "$input.price * $input.qty", "total"),
                ProcedureStep.evaluate("b", "'hello'", "greeting"),
                ProcedureStep.evaluate("c", "$total > 10 && $greeting == 'hello'", "big"));
        assertTrue(result.ok(), result.failureCode());
        assertEquals(12L, result.state().get("total"));
        assertEquals("hello", result.state().get("greeting"));
        assertEquals(true, result.state().get("big"));
    }

    @Test
    void aTypoInARefFailsTheStepInsteadOfBecomingAString() {
        ProcedureExecutionResult result = run(Map.of("price", 3),
                ProcedureStep.evaluate("a", "$input.prise + 1", "total"));
        assertFalse(result.ok());
        assertEquals("REF_UNRESOLVABLE", result.failureCode());
    }

    @Test
    void aBareNameIsRefusedSoAnUnquotedLiteralCannotPassAsText() {
        ProcedureExecutionResult result = run(Map.of(), ProcedureStep.evaluate("a", "helper-says-hi", "answer"));
        assertFalse(result.ok());
        assertEquals("EXPRESSION_INVALID", result.failureCode());
    }

    @Test
    void divisionByZeroAndSyntaxErrorsAreNamedFailures() {
        assertEquals("EXPRESSION_INVALID",
                run(Map.of(), ProcedureStep.evaluate("a", "1 / 0", "x")).failureCode());
        assertEquals("EXPRESSION_INVALID",
                run(Map.of(), ProcedureStep.evaluate("a", "1 +", "x")).failureCode());
    }

    @Test
    void buildObjectMakesOneObjectFromLiteralsAndRefs() {
        java.util.Map<String, Object> select = new java.util.LinkedHashMap<>();
        select.put("sku", "$input.sku");
        select.put("source", "import");
        select.put("cleared", null);
        ProcedureExecutionResult result = run(Map.of("sku", "A-1"), ProcedureStep.buildObject("b", select, "row"));
        assertTrue(result.ok(), result.failureCode());
        Map<?, ?> row = (Map<?, ?>) result.state().get("row");
        assertEquals("A-1", row.get("sku"));
        assertEquals("import", row.get("source"));
        assertTrue(row.containsKey("cleared"));
        assertEquals("REF_UNRESOLVABLE", run(Map.of(), ProcedureStep.buildObject("b", Map.of("x", "$input.nope"), "row")).failureCode());
    }

    @Test
    void aRecordThatCrossedAFlowStepIsReadByItsFieldsLikeAConceptRecord() {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("conceptName", "Ride");
        row.put("id", "r-1");
        row.put("rowVersion", 3);
        row.put("data", Map.of("status", "ASSIGNED", "id", "r-1"));
        ProcedureExecutionResult result = run(Map.of("ride", row),
                ProcedureStep.evaluate("a", "$ride.status == 'ASSIGNED'", "assigned"),
                ProcedureStep.evaluate("b", "$ride.rowVersion", "version"),
                ProcedureStep.evaluate("c", "$ride.data.status", "viaData"));
        assertTrue(result.ok(), result.failureCode());
        assertEquals(true, result.state().get("assigned"));
        assertEquals(3, result.state().get("version"));
        assertEquals("ASSIGNED", result.state().get("viaData"));
    }
}
