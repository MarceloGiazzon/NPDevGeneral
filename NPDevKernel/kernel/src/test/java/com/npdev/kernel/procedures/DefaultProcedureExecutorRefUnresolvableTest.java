package com.npdev.kernel.procedures;

import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.GovernedTestGateways;
import com.npdev.kernel.concepts.GovernedTestGateways.ConceptSpec;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 12 P1.1 (item 1 / REG-100 X0-6): {@code resolve()} used to return {@code null} for a "$ref"
 * whose path could not be traversed -- indistinguishable from a legitimately-null value already in
 * state. That let a typo'd ref (e.g. {@code $item.quantidad} instead of {@code $item.quantidade})
 * write {@code null} into a real concept field with no error anywhere, while the SAME class's
 * {@code requireString} throws for a missing id ref. This suite proves the strict replacement,
 * {@code resolveSetValue}/{@code resolveStrict}, refuses every {@code resolveSetValue} consumer
 * (patchConcept.set, mapList.select, mapValue, computeValue's operands, return's valueRef) with a
 * named {@code REF_UNRESOLVABLE} failure -- and that an explicit null already bound in state (as
 * opposed to a key that was never bound) is still a legitimately resolved value, not a failure.
 *
 * <p>Uses a GOVERNED gateway (O5 / Move 11 W4) for every write-path case, per the REG-83/REG-89
 * lesson: an ungoverned test gateway is how those bugs shipped green.
 *
 * <p>Not covered here, deliberately: {@code patchConcept}'s {@code idRef} resolves via the LENIENT
 * {@code resolve()}, not {@code resolveSetValue} -- an unresolved/blank idRef with
 * {@code createIfMissing=true} means "nothing to look up yet, create new" by design (Move 5 Wave 1B),
 * not a typo, and is already proven by {@code DefaultProcedureExecutorCreateIfMissingTest
 * .patchConceptCreatesANewRecordWithGeneratedIdWhenCreateIfMissingIsTrueAndNothingMatches}.
 */
class DefaultProcedureExecutorRefUnresolvableTest {

    private static final ExecutionContext CTX = ExecutionContext.of("tenant-a", "actor-a");
    private static final EventBus NOOP_EVENT_BUS = event -> { };

    private static final java.util.function.Supplier<ConceptGateway> GOVERNED = () ->
            GovernedTestGateways.forConcepts(ConceptSpec.of("Lote", "quantidade"));

    private static DefaultProcedureExecutor newExecutor(ConceptGateway gateway) {
        return new DefaultProcedureExecutor(gateway, (call, state) -> CapabilityResult.success(null), NOOP_EVENT_BUS);
    }

    @Test
    void patchConceptSetWithATypoRefFailsInsteadOfWritingNull() {
        ConceptGateway gateway = GOVERNED.get();
        gateway.save(new ConceptWriteRequest("Lote", "L1", "tenant-a",
                Map.of("id", "L1", "quantidade", "1")), CTX);
        DefaultProcedureExecutor executor = newExecutor(gateway);
        ProcedureDefinition definition = new ProcedureDefinition(
                "PatchLote",
                List.of(ProcedureStep.patchConcept(
                        "patch-lote", "Lote", "$lotId",
                        // "quantidad" is a typo for "quantidade" -- state only carries the latter.
                        Map.of("quantidade", "$item.quantidad"), "patched"))
        );
        Map<String, Object> initial = new HashMap<>();
        initial.put("lotId", "L1");
        initial.put("item", Map.of("quantidade", 5));

        ProcedureExecutionResult result = executor.execute(definition, initial, CTX);

        assertFalse(result.ok(), "a typo'd $ref in patchConcept.set must fail, not write null");
        assertEquals("REF_UNRESOLVABLE", result.failureCode());
        assertTrue(result.failureMessage().contains("quantidad"),
                "the failure must name the unresolved ref: " + result.failureMessage());
        assertTrue(result.failureMessage().contains("patch-lote"),
                "the failure must name the step: " + result.failureMessage());
    }

    @Test
    void mapListSelectWithATypoRefFails() {
        ConceptGateway gateway = GOVERNED.get();
        DefaultProcedureExecutor executor = newExecutor(gateway);
        ProcedureDefinition definition = new ProcedureDefinition(
                "MapRows",
                List.of(ProcedureStep.mapList(
                        "map-rows", "$rows", "row",
                        Map.of("total", "$row.totall"), "mapped"))
        );
        Map<String, Object> initial = new HashMap<>();
        initial.put("rows", List.of(Map.of("total", 10)));

        ProcedureExecutionResult result = executor.execute(definition, initial, CTX);

        assertFalse(result.ok(), "a typo'd $ref in mapList.select must fail, not produce a null field");
        assertEquals("REF_UNRESOLVABLE", result.failureCode());
        assertTrue(result.failureMessage().contains("totall"), result.failureMessage());
    }

    @Test
    void mapValueWithAnUnboundTopLevelRefFails() {
        ConceptGateway gateway = GOVERNED.get();
        DefaultProcedureExecutor executor = newExecutor(gateway);
        ProcedureDefinition definition = new ProcedureDefinition(
                "MapMissing",
                List.of(ProcedureStep.mapValue("map-missing", "$doesNotExist", "out"))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), CTX);

        assertFalse(result.ok(), "a $ref naming a key never bound in state must fail");
        assertEquals("REF_UNRESOLVABLE", result.failureCode());
        assertNull(result.state().get("out"));
    }

    @Test
    void returnValueWithATypoRefFails() {
        ConceptGateway gateway = GOVERNED.get();
        DefaultProcedureExecutor executor = newExecutor(gateway);
        ProcedureDefinition definition = new ProcedureDefinition(
                "ReturnMissing",
                List.of(ProcedureStep.returnValue("return-missing", "$outcomme"))
        );
        Map<String, Object> initial = new HashMap<>();
        initial.put("outcome", "done");

        ProcedureExecutionResult result = executor.execute(definition, initial, CTX);

        assertFalse(result.ok(), "a typo'd returnRef must fail, not silently return null");
        assertEquals("REF_UNRESOLVABLE", result.failureCode());
    }

    @Test
    void computeValueWithAnUnresolvedOperandFailsAsRefUnresolvableNotInvalidOperand() {
        ConceptGateway gateway = GOVERNED.get();
        DefaultProcedureExecutor executor = newExecutor(gateway);
        ProcedureDefinition definition = new ProcedureDefinition(
                "ComputeMissing",
                List.of(ProcedureStep.computeValue("compute-missing", "add", "$missingLeft", 1, "sum"))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), CTX);

        assertFalse(result.ok());
        assertEquals("REF_UNRESOLVABLE", result.failureCode(),
                "an unresolvable operand ref is refused before arithmetic ever sees a null operand");
    }

    /**
     * The control: a key EXPLICITLY bound to {@code null} in state (as opposed to a key that was
     * never bound at all) is still a legitimately resolved value. This is the distinction X0-6's fix
     * turns on -- {@code containsKey}, not a null check -- and it must keep working, or the fix would
     * just trade one silent defect (typo -> null) for a new one (real null -> spurious failure).
     */
    @Test
    void mapValueResolvesAnExplicitNullAlreadyInStateWithoutFailing() {
        ConceptGateway gateway = GOVERNED.get();
        DefaultProcedureExecutor executor = newExecutor(gateway);
        ProcedureDefinition definition = new ProcedureDefinition(
                "MapExplicitNull",
                List.of(ProcedureStep.mapValue("map-null", "$explicitlyNull", "out"))
        );
        Map<String, Object> initial = new HashMap<>();
        initial.put("explicitlyNull", null);

        ProcedureExecutionResult result = executor.execute(definition, initial, CTX);

        assertTrue(result.ok(), "an explicit null already bound in state must resolve, not fail: " + result.failureMessage());
        assertTrue(result.state().containsKey("out"));
        assertNull(result.state().get("out"));
    }

}
