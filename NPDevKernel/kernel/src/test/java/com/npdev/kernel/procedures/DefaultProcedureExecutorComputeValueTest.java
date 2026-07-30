package com.npdev.kernel.procedures;

import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, final item / REG-78): procedures had no arithmetic
 * primitive -- {@code patchConcept}'s {@code set} can copy or overwrite a value, never compute one
 * as a function of the current value (e.g. {@code newQuantidade = existing.quantidade + delta}),
 * the exact gap blocking WmsOffice's SyncOcupacaoProcedure find-or-increment semantics.
 * {@code computeValue}'s {@code left}/{@code right} resolve via the SAME literal-vs-{@code $ref}
 * convention {@code patchConcept}'s {@code set} and {@code mapList}'s {@code select} already use.
 */
class DefaultProcedureExecutorComputeValueTest {

    private static final ExecutionContext CTX = ExecutionContext.of("tenant-a", "actor-a");
    private static final EventBus NOOP_EVENT_BUS = event -> { };

    private static DefaultProcedureExecutor newExecutor() {
        ConceptGateway gateway = new DefaultConceptGateway(new InMemoryConceptStore());
        return new DefaultProcedureExecutor(gateway, (call, state) -> CapabilityResult.success(null), NOOP_EVENT_BUS);
    }

    @Test
    void addsARefOperandToALiteralDelta() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "IncrementQuantity",
                List.of(
                        ProcedureStep.computeValue("increment", "add", "$existing.quantidade", 5, "newQuantidade"),
                        ProcedureStep.returnValue("return-new", "$newQuantidade")
                )
        );

        ProcedureExecutionResult result = executor.execute(
                definition, Map.of("existing", Map.of("quantidade", 10)), CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(15L, result.state().get("newQuantidade"), "10 (ref) + 5 (literal) = 15, as a whole-number Long");
    }

    @Test
    void subtractsARefOperandFromAnotherRef() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "DecrementQuantity",
                List.of(ProcedureStep.computeValue(
                        "decrement", "subtract", "$existing.quantidade", "$delta", "newQuantidade"))
        );

        ProcedureExecutionResult result = executor.execute(
                definition, Map.of("existing", Map.of("quantidade", 10), "delta", 3), CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(7L, result.state().get("newQuantidade"));
    }

    @Test
    void twoLiteralOperandsWithNoDollarPrefixComputeDirectly() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "AddTwoLiterals",
                List.of(ProcedureStep.computeValue("add-literals", "add", 2, 3, "sum"))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(5L, result.state().get("sum"), "plain values are literals, same as patchConcept's set");
    }

    @Test
    void aFractionalResultIsNormalizedToADouble() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "AddFraction",
                List.of(ProcedureStep.computeValue("add-fraction", "add", "2.5", "1.5", "sum"))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(4L, result.state().get("sum"), "2.5+1.5 has no fractional remainder, so it normalizes to a whole Long");
    }

    @Test
    void aTrulyFractionalResultStaysADouble() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "AddTrueFraction",
                List.of(ProcedureStep.computeValue("add-fraction", "add", "2.5", "1.25", "sum"))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(3.75d, result.state().get("sum"));
    }

    @Test
    void nonNumericOperandFailsWithAClearCode() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "AddGarbage",
                List.of(ProcedureStep.computeValue("add-garbage", "add", "not-a-number", 1, "sum"))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), CTX);

        assertFalse(result.ok(), "a non-numeric operand must fail, not silently coerce to zero");
        assertEquals("COMPUTE_VALUE_INVALID_OPERAND", result.failureCode());
    }

    @Test
    void unknownOperatorFailsWithAClearCode() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "MultiplyUnsupported",
                List.of(ProcedureStep.computeValue("multiply", "multiply", 2, 3, "product"))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), CTX);

        assertFalse(result.ok(), "only add/subtract are supported today");
        assertEquals("COMPUTE_VALUE_UNKNOWN_OPERATOR", result.failureCode());
    }
}
