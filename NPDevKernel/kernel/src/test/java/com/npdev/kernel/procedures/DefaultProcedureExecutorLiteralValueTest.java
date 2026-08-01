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
        return new DefaultProcedureExecutor(gateway, (call, state) -> CapabilityResult.success(null), NOOP_EVENT_BUS);
    }

    @Test
    void returnAcceptsALiteralArrayWithNoCapabilityRoundTrip() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "ReturnLiteralList",
                List.of(ProcedureStep.returnValue("return-literal-list", List.of("a", "b", "c")))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(List.of("a", "b", "c"), result.state().get("return"));
    }

    @Test
    void mapValueAcceptsALiteralObject() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "MapLiteralObject",
                List.of(
                        ProcedureStep.mapValue("map-literal", Map.of("createdCount", 3L), "summary"),
                        ProcedureStep.returnValue("return-summary", "$summary")
                )
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(Map.of("createdCount", 3L), result.state().get("return"));
    }

    @Test
    void dollarRefsStillResolveAgainstState() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "ReturnRef",
                List.of(ProcedureStep.returnValue("return-ref", "$items"))
        );

        ProcedureExecutionResult result = executor.execute(
                definition, Map.of("items", List.of(1, 2, 3)), CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals(List.of(1, 2, 3), result.state().get("return"));
    }

    @Test
    void doubleDollarEscapesToALiteralStringStartingWithDollar() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "ReturnEscaped",
                List.of(ProcedureStep.returnValue("return-escaped", "$$notARef"))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals("$notARef", result.state().get("return"));
    }
}
