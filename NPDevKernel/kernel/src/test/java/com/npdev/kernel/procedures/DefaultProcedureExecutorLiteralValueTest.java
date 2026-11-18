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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-86: {@code mapValue}/{@code return} used to force their {@code value} through a String
 * ref-only path (see the deleted always-a-state-path {@code resolve()} call) -- a literal array or
 * object could never survive that, only a {@code $ref} into procedure state could. This is the same
 * shape the pre-{@code patchConcept.set} literal-constant gap had; the fix reuses {@code
 * DefaultProcedureExecutor#resolveSetValue}'s literal-vs-{@code $ref} convention instead.
 */
class DefaultProcedureExecutorLiteralValueTest {

    private static final ExecutionContext CTX = ExecutionContext.of("tenant-a", "actor-a");
    private static final EventBus NOOP_EVENT_BUS = event -> { };

    private static DefaultProcedureExecutor newExecutor() {
        ConceptGateway gateway = new DefaultConceptGateway(new InMemoryConceptStore());
        return new DefaultProcedureExecutor(gateway);
    }

    @Test
    void aLiteralArrayPassesThroughUnchanged() {
        ProcedureExecutionResult result = newExecutor().execute(new ProcedureDefinition(
                "Literal", List.of(ProcedureStep.buildObject("b", Map.of("list", List.of("a", "b", "c")), "return"))), Map.of(), CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(Map.of("list", List.of("a", "b", "c")), result.state().get("return"));
    }

    @Test
    void aLiteralObjectPassesThroughUnchanged() {
        ProcedureExecutionResult result = newExecutor().execute(new ProcedureDefinition(
                "LiteralObject", List.of(ProcedureStep.buildObject("b", Map.of("summary", Map.of("createdCount", 3L)), "return"))), Map.of(), CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(Map.of("summary", Map.of("createdCount", 3L)), result.state().get("return"));
    }

    @Test
    void dollarRefsStillResolveAgainstState() {
        ProcedureExecutionResult result = newExecutor().execute(new ProcedureDefinition(
                "Ref", List.of(ProcedureStep.buildObject("b", Map.of("items", "$items"), "return"))),
                Map.of("items", List.of(1, 2, 3)), CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(Map.of("items", List.of(1, 2, 3)), result.state().get("return"));
    }

    @Test
    void doubleDollarEscapesToALiteralStringStartingWithDollar() {
        ProcedureExecutionResult result = newExecutor().execute(new ProcedureDefinition(
                "Escaped", List.of(ProcedureStep.buildObject("b", Map.of("v", "$$notARef"), "return"))), Map.of(), CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(Map.of("v", "$notARef"), result.state().get("return"));
    }
}
