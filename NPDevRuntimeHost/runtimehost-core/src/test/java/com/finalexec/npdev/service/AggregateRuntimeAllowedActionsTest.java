package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENF-1 (2026-10-24): {@code lifecycleState.allowedActions} is enforced on
 * {@code POST /api/runtime/aggregate/{a}/invoke/{procedure}}, not only by hiding the workbench button. Draft
 * allows only Stamp; Submitted declares no list (every action allowed). The current state is the PERSISTED
 * root's status, never the client's draft; a procedure that is not a workbench action is never gated.
 */
class AggregateRuntimeAllowedActionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "t", "version": "1.0",
          "concepts": [
            { "name": "Ord", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "status", "type": "string" },
                { "name": "note", "type": "string" } ],
              "lifecycle": {
                "statusField": "status",
                "states": [
                  { "value": "Draft", "initial": true, "allowedActions": ["StampProcedure"] },
                  { "value": "Submitted" },
                  { "value": "Done", "terminal": true } ],
                "transitions": [ { "from": "Draft", "to": "Submitted" }, { "from": "Submitted", "to": "Done" } ] } }
          ],
          "aggregates": [ { "name": "OrdAggregate", "root": "Ord" } ],
          "autoPanels": [
            { "name": "OrdWorkbench", "aggregate": "OrdAggregate", "route": "/ords",
              "transaction": { "actions": [
                { "label": "Stamp", "procedure": "StampProcedure" },
                { "label": "Clear", "procedure": "ClearProcedure" } ] } }
          ],
          "procedures": [
            { "name": "StampProcedure", "steps": [ { "name": "r", "type": "return", "value": { "note": "stamped" } } ] },
            { "name": "ClearProcedure", "steps": [ { "name": "r", "type": "return", "value": { "note": "" } } ] },
            { "name": "RecomputeProcedure", "steps": [ { "name": "r", "type": "return", "value": { "note": "recomputed" } } ] }
          ]
        }
        """;

    private final ExecutionContext ctx = ExecutionContext.of("trial", "tester");
    private ConceptGateway gateway;
    private AggregateRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(MODEL)));
        gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(model), model);
        runtime = new AggregateRuntime(model, gateway, new ProcedureRunner(model, gateway, null, null));
    }

    private static Map<String, Object> draft(String id, String claimedStatus) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("id", id);
        draft.put("status", claimedStatus);
        return draft;
    }

    @Test
    void aNewRootIsInTheInitialStateWhereOnlyTheListedActionRuns() {
        assertEquals("stamped", runtime.invoke("OrdAggregate", "StampProcedure", draft("N1", "Draft"), ctx).get("note"));
        AggregateActionNotAllowedException refused = assertThrows(AggregateActionNotAllowedException.class,
                () -> runtime.invoke("OrdAggregate", "ClearProcedure", draft("N1", "Draft"), ctx));
        assertTrue(refused.getMessage().contains("ClearProcedure") && refused.getMessage().contains("Draft"),
                refused.getMessage());
    }

    @Test
    void aProcedureThatIsNotAWorkbenchActionIsNeverGated() {
        assertEquals("recomputed",
                runtime.invoke("OrdAggregate", "RecomputeProcedure", draft("N1", "Draft"), ctx).get("note"));
    }

    @Test
    void thePersistedStateDecidesNotTheStatusTheDraftClaims() {
        gateway.save(new ConceptWriteRequest("Ord", "O1", "trial", Map.of("id", "O1", "status", "Draft")), ctx);
        assertThrows(AggregateActionNotAllowedException.class,
                () -> runtime.invoke("OrdAggregate", "ClearProcedure", draft("O1", "Submitted"), ctx),
                "a draft claiming Submitted must not unlock an action the persisted Draft forbids");

        gateway.save(new ConceptWriteRequest("Ord", "O1", "trial", Map.of("id", "O1", "status", "Submitted")), ctx);
        assertDoesNotThrow(() -> runtime.invoke("OrdAggregate", "ClearProcedure", draft("O1", "Draft"), ctx),
                "Submitted declares no allowedActions, so every action runs");
    }
}
