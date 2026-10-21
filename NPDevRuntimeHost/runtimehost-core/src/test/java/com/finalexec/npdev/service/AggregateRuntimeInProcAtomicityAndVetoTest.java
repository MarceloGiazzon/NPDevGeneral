package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.runtime.support.CelInvariantEngine;
import com.npdev.kernel.concepts.ConceptGatewaySemanticException;
import com.npdev.kernel.concepts.ConceptStoreUniqueViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-267 (a failing onCommit procedure must take the whole commit with it even on the in-process
 * store, which has no transaction manager), REG-265 (an invariant veto is a client error, not 503)
 * and REG-266 (an illegal lifecycle transition in the tree is a 422 with its code, not a bare 500),
 * REG-257 (a unique-index collision inside a tree is a 409). Constructed WITHOUT a
 * {@code PlatformTransactionManager}, exactly the shape a booted InMemory app has.
 */
class AggregateRuntimeInProcAtomicityAndVetoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "t", "version": "1.0",
          "concepts": [
            { "name": "Ord", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "status", "type": "string" },
                { "name": "totalQty", "type": "int" },
                { "name": "ledgerRef", "type": "uuid" } ],
              "lifecycle": {
                "statusField": "status",
                "states": [
                  { "value": "Draft", "label": "Draft", "initial": true },
                  { "value": "Committed", "label": "Committed", "terminal": true } ],
                "transitions": [ { "from": "Draft", "to": "Committed" } ] } },
            { "name": "OrdLine", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "ordId", "type": "uuid" },
                { "name": "sku", "type": "string" },
                { "name": "qty", "type": "int" } ],
              "indexes": [ { "name": "ux_ord_line_sku", "fields": ["ordId", "sku"], "unique": true } ] },
            { "name": "Ledger", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "synced", "type": "string" } ] }
          ],
          "aggregates": [
            { "name": "OrdAggregate", "root": "Ord", "onCommit": "SyncLedgerProcedure",
              "collections": [ { "name": "lines", "concept": "OrdLine", "childField": "ordId", "ownership": "owned" } ],
              "invariants": [
                { "name": "lines-within-total", "expression": "lines.sum(qty) <= totalQty" } ] }
          ],
          "procedures": [
            { "name": "SyncLedgerProcedure", "steps": [
              { "name": "sync-ledger", "type": "patchConcept", "concept": "Ledger", "id": "$input.ledgerRef",
                "set": { "synced": "true" }, "target": "patched" },
              { "name": "return-patched", "type": "return", "value": "$patched" }
            ] }
          ]
        }
        """;

    private final ExecutionContext ctx = ExecutionContext.of("trial", "tester");
    private ConceptGateway gateway;
    private AggregateRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        CompiledModel model = new ModelCompiler().compile(ast);
        gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(model), model);
        ProcedureRunner procedureRunner = new ProcedureRunner(model, gateway, null, null);
        runtime = new AggregateRuntime(model, gateway, procedureRunner, null, new CelInvariantEngine());
    }

    private Map<String, Object> draft(String status, int totalQty, String ledgerRef, Object... skuAndQty) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", "O1");
        root.put("status", status);
        root.put("totalQty", totalQty);
        root.put("ledgerRef", ledgerRef);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < skuAndQty.length; i += 2) {
            lines.add(new LinkedHashMap<>(Map.of(
                    "id", "L" + (i / 2 + 1), "sku", skuAndQty[i], "qty", skuAndQty[i + 1])));
        }
        root.put("lines", lines);
        return root;
    }

    @Test
    void failingOnCommitProcedureRollsBackRootAndChildrenOnTheInProcStore() {
        // No Ledger "missing" row exists, so the onCommit patchConcept fails AFTER root + line were written.
        assertThrows(IllegalStateException.class,
                () -> runtime.commit("OrdAggregate", draft("Draft", 10, "missing", "A", 1), ctx));

        assertEquals(Optional.empty(), gateway.read(new ConceptReadRequest("Ord", "O1", null), ctx),
                "the root must be rolled back with the failed onCommit");
        assertTrue(gateway.list(new ConceptListRequest("OrdLine", "trial", null, null), ctx).isEmpty(),
                "the child rows must be rolled back with the failed onCommit");
    }

    @Test
    void failingOnCommitIsStillAnUnavailableStyleIllegalStateNotAVeto() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runtime.commit("OrdAggregate", draft("Draft", 10, "missing", "A", 1), ctx));
        assertTrue(thrown.getMessage().contains("onCommit procedure"));
        assertTrue(!(thrown instanceof AggregateCommitVetoException),
                "a failing onCommit procedure is not a client veto -- the controller keeps answering 503");
    }

    @Test
    void successfulCommitKeepsEverythingWithTheJournalActive() {
        gateway.save(new ConceptWriteRequest("Ledger", "L1", "trial", Map.of("id", "L1", "synced", "false")), ctx);
        runtime.commit("OrdAggregate", draft("Draft", 10, "L1", "A", 1, "B", 2), ctx);
        assertEquals(2, gateway.list(new ConceptListRequest("OrdLine", "trial", null, null), ctx).size());
        assertEquals("true", gateway.read(new ConceptReadRequest("Ledger", "L1", null), ctx)
                .orElseThrow().data().get("synced"));
    }

    @Test
    void invariantVetoIsTheClientErrorSubtype() {
        AggregateCommitVetoException thrown = assertThrows(AggregateCommitVetoException.class,
                () -> runtime.commit("OrdAggregate", draft("Draft", 10, "L1", "A", 7, "B", 5), ctx));
        assertTrue(thrown.getMessage().contains("lines-within-total"));
        assertEquals(Optional.empty(), gateway.read(new ConceptReadRequest("Ord", "O1", null), ctx));
    }

    @Test
    void illegalLifecycleTransitionInTheTreeSurfacesTheGatewayCodeAndLeavesTheRowUnchanged() {
        gateway.save(new ConceptWriteRequest("Ledger", "L1", "trial", Map.of("id", "L1", "synced", "false")), ctx);
        runtime.commit("OrdAggregate", draft("Draft", 10, "L1", "A", 1), ctx);
        // Draft -> Committed is the only declared transition; Committed -> Draft is illegal.
        gateway.save(new ConceptWriteRequest("Ord", "O1", "trial",
                Map.of("id", "O1", "status", "Committed", "totalQty", 10, "ledgerRef", "L1")), ctx);

        ConceptGatewaySemanticException thrown = assertThrows(ConceptGatewaySemanticException.class,
                () -> runtime.commit("OrdAggregate", draft("Draft", 10, "L1", "A", 1), ctx));

        assertEquals("CONCEPT_LIFECYCLE_TRANSITION_INVALID", thrown.code());
        assertEquals("Committed", gateway.read(new ConceptReadRequest("Ord", "O1", null), ctx)
                .orElseThrow().data().get("status"));
    }

    @Test
    void uniqueIndexCollisionInsideTheTreeIsRaisedAndTheEarlierRootWriteIsUndone() {
        gateway.save(new ConceptWriteRequest("Ledger", "L1", "trial", Map.of("id", "L1", "synced", "false")), ctx);
        // two lines with the same (ordId, sku) pair
        assertThrows(ConceptStoreUniqueViolationException.class,
                () -> runtime.commit("OrdAggregate", draft("Draft", 10, "L1", "A", 1, "A", 2), ctx));
        assertEquals(Optional.empty(), gateway.read(new ConceptReadRequest("Ord", "O1", null), ctx),
                "the root written before the colliding line must be undone");
    }
}
