package com.npdev.kernel.procedures;

import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.GovernedTestGateways;
import com.npdev.kernel.concepts.GovernedTestGateways.ConceptSpec;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1B): the create-with-generated-id half REG-77
 * found missing -- {@code saveConcept}'s blank-idRef fallback, and {@code patchConcept}'s opt-in
 * {@code createIfMissing}. Both default to the ORIGINAL, unchanged behavior (REG-75's deliberate
 * patch-not-upsert semantics for patchConcept; saveConcept's own always-required idRef otherwise)
 * -- proven here alongside the new opt-in paths so a future change can't quietly widen the default.
 */
class DefaultProcedureExecutorCreateIfMissingTest {

    private static final ExecutionContext CTX = ExecutionContext.of("tenant-a", "actor-a");
    private static final EventBus NOOP_EVENT_BUS = event -> { };

    /**
     * O5 (Move 11 W4): a GOVERNED gateway, not `new DefaultConceptGateway(store)`. These tests are
     * REG-77/REG-89's own subject, and REG-83 -- an auto-generated id never folded back into the
     * write's own data map -- shipped for nine commits precisely because they ran against a noop
     * semantic policy that never asked for a required id. Under the real policy every write here
     * must satisfy `id` exactly as a generated app demands.
     */
    private static final java.util.function.Supplier<ConceptGateway> GOVERNED = () ->
            GovernedTestGateways.forConcepts(
                    ConceptSpec.of("Widget", "label", "name"),
                    ConceptSpec.of("WidgetLot", "quantity", "other", "label"));

    private static DefaultProcedureExecutor newExecutor(ConceptGateway gateway) {
        return new DefaultProcedureExecutor(gateway, (call, state) -> CapabilityResult.success(null), NOOP_EVENT_BUS);
    }

    @Test
    void saveConceptGeneratesAnIdWhenIdRefIsBlank() {
        ConceptGateway gateway = GOVERNED.get();
        DefaultProcedureExecutor executor = newExecutor(gateway);
        ProcedureDefinition definition = new ProcedureDefinition(
                "CreateWidget",
                List.of(
                        ProcedureStep.saveConcept("save-widget", "Widget", null, "payload", "saved"),
                        ProcedureStep.returnValue("return-saved", "$saved")
                )
        );

        ProcedureExecutionResult result = executor.execute(
                definition, Map.of("payload", Map.of("label", "Widget A")), CTX);

        assertTrue(result.ok(), "saveConcept with a blank idRef must generate an id, not fail: " + result.failureMessage());
        ConceptRecord saved = (ConceptRecord) result.state().get("saved");
        assertNotNull(saved.id(), "saveConcept must have assigned a generated id");
        assertFalse(saved.id().isBlank());
        assertEquals("Widget A", saved.data().get("label"));
    }

    @Test
    void patchConceptStillFailsConceptNotFoundWhenCreateIfMissingIsDefaultFalse() {
        ConceptGateway gateway = GOVERNED.get();
        DefaultProcedureExecutor executor = newExecutor(gateway);
        ProcedureDefinition definition = new ProcedureDefinition(
                "PatchMissingWidget",
                List.of(ProcedureStep.patchConcept(
                        "patch-widget", "Widget", "$input.id", Map.of("label", "renamed"), "patched"))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of("id", "does-not-exist"), CTX);

        assertFalse(result.ok(), "REG-75's patch-not-upsert default must be unchanged");
        assertEquals("CONCEPT_NOT_FOUND", result.failureCode());
    }

    @Test
    void patchConceptCreatesANewRecordWithGeneratedIdWhenCreateIfMissingIsTrueAndNothingMatches() {
        ConceptGateway gateway = GOVERNED.get();
        DefaultProcedureExecutor executor = newExecutor(gateway);
        ProcedureDefinition definition = new ProcedureDefinition(
                "EnsureWidgetLot",
                List.of(
                        // "$existingLotId" resolves to nothing -- state never sets that key, matching
                        // the real use case: a prior lookup (e.g. listConcepts) found no match.
                        ProcedureStep.patchConcept("ensure-lot", "WidgetLot", "$existingLotId",
                                Map.of("quantity", "10"), "ensured", true),
                        ProcedureStep.returnValue("return-ensured", "$ensured")
                )
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), CTX);

        assertTrue(result.ok(), "createIfMissing=true must create rather than fail: " + result.failureMessage());
        ConceptRecord created = (ConceptRecord) result.state().get("ensured");
        assertNotNull(created.id(), "patchConcept's create-if-missing path must assign a generated id");
        assertFalse(created.id().isBlank());
        assertEquals("10", created.data().get("quantity"));

        Optional<ConceptRecord> reread = gateway.read(
                new ConceptReadRequest("WidgetLot", created.id(), null), CTX);
        assertTrue(reread.isPresent(), "the created record must actually be persisted, not just returned");
    }

    @Test
    void patchConceptWithCreateIfMissingStillPatchesInPlaceWhenTheRecordAlreadyExists() {
        ConceptGateway gateway = GOVERNED.get();
        gateway.save(new ConceptWriteRequest("WidgetLot", "L1", "tenant-a",
                Map.of("id", "L1", "quantity", "5", "other", "kept")), CTX);
        DefaultProcedureExecutor executor = newExecutor(gateway);
        ProcedureDefinition definition = new ProcedureDefinition(
                "EnsureWidgetLot",
                List.of(ProcedureStep.patchConcept("ensure-lot", "WidgetLot", "$input.lotId",
                        Map.of("quantity", "6"), "ensured", true))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of("lotId", "L1"), CTX);

        assertTrue(result.ok(), result.failureMessage());
        ConceptRecord patched = (ConceptRecord) result.state().get("ensured");
        assertEquals("L1", patched.id(), "createIfMissing must not mint a new id when a match was found");
        assertEquals("6", patched.data().get("quantity"));
        assertEquals("kept", patched.data().get("other"), "patchConcept must still preserve fields it does not name in set");
    }
}
