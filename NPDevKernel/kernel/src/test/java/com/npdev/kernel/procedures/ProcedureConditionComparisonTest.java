package com.npdev.kernel.procedures;

import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.GovernedTestGateways;
import com.npdev.kernel.concepts.GovernedTestGateways.ConceptSpec;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-96 (Wave 0.6). A procedure's {@code condition}/{@code if} step could only ask "is this
 * reference truthy" -- never "does it equal {@code 'Concluido'}". That is why
 * {@code aggregate.onCommit} could not guard an event emission on a lifecycle transition (M11,
 * docs/MOVE3_G2_CHECKLISTS.md), and why {@code SyncOcupacaoProcedure}'s own description records
 * having to push an equality test into Java: <i>"procedures' own 'if' step has no
 * comparison-expression grammar (conditionRef is a bare resolve+truthy check, not '==')"</i>.
 *
 * <p>The RED half is {@link #bareRefIsStillTruthinessOnly_theShapeThatForcedJava}: it shows what the
 * old grammar could express, and why that was not enough. Everything else is the GREEN.
 */
class ProcedureConditionComparisonTest {

    private static final CapabilityDispatcher NOOP = (call, state) -> CapabilityResult.success(null);
    private static final EventBus NOOP_BUS = event -> { };
    private static final ExecutionContext CTX = ExecutionContext.of("t1", "actor");

    private static DefaultProcedureExecutor executor() {
        ConceptGateway gateway = GovernedTestGateways.forConcepts(ConceptSpec.of("Movimento", "situacao"));
        return new DefaultProcedureExecutor(gateway, NOOP, NOOP_BUS);
    }

    /** Runs `if <condition> then assign taken='then' else assign taken='else'` and returns which ran. */
    private static String branchTaken(String condition, Map<String, Object> input) {
        ProcedureDefinition definition = new ProcedureDefinition("Branch", List.of(
                ProcedureStep.ifThenElse("branch", condition,
                        List.of(ProcedureStep.mapValue("t", "then", "taken")),
                        List.of(ProcedureStep.mapValue("e", "else", "taken")))));
        ProcedureExecutionResult result = executor().execute(definition, input, CTX);
        assertTrue(result.ok(), result.failureMessage());
        return String.valueOf(result.state().get("taken"));
    }

    @Test
    @DisplayName("REG-96's own example: emit ONLY when the record reached 'Concluido' -- was inexpressible")
    void equalityAgainstALifecycleStateNowDecidesTheBranch() {
        assertEquals("then", branchTaken("$input.situacao == 'Concluido'", Map.of("situacao", "Concluido")));
        assertEquals("else", branchTaken("$input.situacao == 'Concluido'", Map.of("situacao", "Pendente")));
        assertEquals("else", branchTaken("$input.situacao == 'Concluido'", Map.of("situacao", "Cancelado")));
    }

    @Test
    @DisplayName("FORM P3-T3: a condition is a BOOLEAN expression -- a bare ref is fine for a boolean, refused for a string")
    void bareRefMustBeBoolean() {
        // The old grammar tested any bare ref for truthiness: a non-empty string was "true" for all four
        // of Movimento's states, which is why == was needed (REG-96). Now the condition is the one
        // expression language, strict and boolean: a bare boolean ref is a legal condition, anything
        // else is written as a comparison.
        assertEquals("then", branchTaken("$input.found", Map.of("found", true)));
        assertEquals("else", branchTaken("$input.found", Map.of("found", false)));
        assertEquals("then", branchTaken("$input.situacao != null", Map.of("situacao", "Concluido")));
        assertEquals("else", branchTaken("length($input.situacao) > 0", Map.of("situacao", "")));
        ProcedureDefinition definition = new ProcedureDefinition("Branch", List.of(
                ProcedureStep.ifThenElse("branch", "$input.situacao",
                        List.of(ProcedureStep.mapValue("t", "then", "taken")),
                        List.of(ProcedureStep.mapValue("e", "else", "taken")))));
        ProcedureExecutionResult result = executor().execute(definition, Map.of("situacao", "Concluido"), CTX);
        assertFalse(result.ok(), "a string is not a boolean condition");
        assertEquals("CONDITION_UNSUPPORTED", result.failureCode());
    }

    @Test
    @DisplayName("!= and the ordered comparisons, using the same grammar visibleWhen carries")
    void otherOperators() {
        assertEquals("then", branchTaken("$input.situacao != 'Concluido'", Map.of("situacao", "Pendente")));
        assertEquals("else", branchTaken("$input.situacao != 'Concluido'", Map.of("situacao", "Concluido")));
        assertEquals("then", branchTaken("$input.qty > 5", Map.of("qty", 9)));
        assertEquals("else", branchTaken("$input.qty > 5", Map.of("qty", 5)));
        assertEquals("then", branchTaken("$input.qty >= 5", Map.of("qty", 5)),
                ">= must not be mis-read as > -- operators are matched longest-first");
        assertEquals("then", branchTaken("$input.qty <= 5", Map.of("qty", 5)));
        assertEquals("else", branchTaken("$input.qty < 5", Map.of("qty", 5)));
    }

    @Test
    @DisplayName("both sides may be refs -- the comparison SyncOcupacaoProcedure had to do in Java")
    void refToRefComparison() {
        assertEquals("then", branchTaken("$input.a == $input.b", Map.of("a", "X", "b", "X")));
        assertEquals("else", branchTaken("$input.a == $input.b", Map.of("a", "X", "b", "Y")));
    }

    @Test
    @DisplayName("an ordered comparison against an ABSENT value is false, not an error -- a state answer, not a parse failure")
    void absentOperandIsFalseNotAnError() {
        assertEquals("else", branchTaken("$input.qty > 5", Map.of()));
    }

    @Test
    @DisplayName("a malformed condition is REFUSED, never silently taken as the else-branch (X0's rule)")
    void malformedConditionFailsTheStep() {
        for (String condition : new String[]{"$input.situacao == ", "== 'Concluido'", "$input.qty > abc"}) {
            ProcedureDefinition definition = new ProcedureDefinition("Branch", List.of(
                    ProcedureStep.ifThenElse("branch", condition,
                            List.of(ProcedureStep.mapValue("t", "then", "taken")),
                            List.of(ProcedureStep.mapValue("e", "else", "taken")))));
            ProcedureExecutionResult result = executor().execute(definition, Map.of("situacao", "X"), CTX);
            assertFalse(result.ok(), "must refuse rather than branch: " + condition);
            assertEquals("CONDITION_UNSUPPORTED", result.failureCode());
            // ProcedureStep normalizes (trims) conditionRef on construction, so compare trimmed.
            assertTrue(result.failureMessage().contains(condition.trim()), result.failureMessage());
        }
    }

    @Test
    @DisplayName("a literal containing an operator token is not torn apart")
    void operatorInsideAQuotedLiteral() {
        assertEquals("then", branchTaken("$input.note == 'a>b'", Map.of("note", "a>b")));
    }
}
