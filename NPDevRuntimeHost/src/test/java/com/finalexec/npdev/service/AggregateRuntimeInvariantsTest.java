package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.runtime.support.CelInvariantEngine;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4.4: a declared aggregate's {@code invariants[]} are evaluated against the whole draft tree --
 * root fields plus every named collection -- in the SAME pre-commit slot {@code onValidate} uses.
 *
 * <p>Deliberately constructed WITHOUT a {@code PlatformTransactionManager}, exactly as
 * {@code AggregateRuntimeOnValidateTest} is and for the same reason: with nothing to roll a write
 * back WITH, a root or child row existing after a rejected commit proves the invariant ran too
 * late, rather than proving a rollback failed. That is a stronger assertion than "the transaction
 * undid it".</p>
 */
class AggregateRuntimeInvariantsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
          "concepts": [
            { "name": "Order", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "totalQty", "type": "int" } ] },
            { "name": "OrderLine", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "orderId", "type": "uuid" },
              { "name": "qty", "type": "int" } ] }
          ],
          "aggregates": [
            { "name": "Order", "root": "Order",
              "collections": [ { "name": "lines", "concept": "OrderLine", "childField": "orderId" } ],
              "invariants": [
                { "name": "positive-line-qty", "expression": "lines.all(l => l.qty > 0)",
                  "message": "every line must have a positive qty" },
                { "name": "lines-within-total", "expression": "lines.sum(qty) <= totalQty" }
              ] }
          ]
        }
        """;

    private ConceptGateway gateway;
    private AggregateRuntime runtime;
    private final ExecutionContext ctx = ExecutionContext.of("trial", "tester");

    @BeforeEach
    void setUp() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        CompiledModel model = new ModelCompiler().compile(ast);
        gateway = new DefaultConceptGateway(new InMemoryConceptStore());
        runtime = new AggregateRuntime(model, gateway, null, null, new CelInvariantEngine());
    }

    private Map<String, Object> draft(int totalQty, int... lineQuantities) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", "O1");
        root.put("totalQty", totalQty);
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        int index = 0;
        for (int qty : lineQuantities) {
            lines.add(new LinkedHashMap<>(Map.of("id", "L" + (++index), "qty", qty)));
        }
        root.put("lines", lines);
        return root;
    }

    @Test
    void aSatisfiedInvariantLetsTheWholeTreeCommit() {
        runtime.commit("Order", draft(10, 3, 4), ctx);

        assertTrue(gateway.read(new ConceptReadRequest("Order", "O1", null), ctx).isPresent(),
                "a draft satisfying every invariant must commit its root");
        assertEquals(2, gateway.list(new ConceptListRequest("OrderLine", "trial", null, null), ctx).size(),
                "a draft satisfying every invariant must commit its collection rows too");
    }

    @Test
    void aViolatedSumInvariantVetoesTheCommitAndNothingIsWrittenAtAll() {
        // 7 + 5 = 12 > totalQty 10.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runtime.commit("Order", draft(10, 7, 5), ctx));

        assertTrue(thrown.getMessage().contains("lines-within-total"),
                "the commit error must NAME the failing rule, got: " + thrown.getMessage());
        assertEquals(Optional.empty(), gateway.read(new ConceptReadRequest("Order", "O1", null), ctx),
                "with no transaction manager to undo anything, a vetoed commit must never write the root");
        assertTrue(gateway.list(new ConceptListRequest("OrderLine", "trial", null, null), ctx).isEmpty(),
                "a vetoed commit must never write collection rows either");
    }

    @Test
    void aViolatedCollectionInvariantUsesItsDeclaredMessage() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runtime.commit("Order", draft(10, 3, 0), ctx));

        assertTrue(thrown.getMessage().contains("positive-line-qty"),
                "expected the rule name in: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("every line must have a positive qty"),
                "a declared message must override the composed default, got: " + thrown.getMessage());
    }

    @Test
    void everyFailingRuleIsNamedNotJustTheFirst() {
        // qty 0 fails positive-line-qty AND 0 + 11 = 11 > 10 fails lines-within-total.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> runtime.commit("Order", draft(10, 0, 11), ctx));

        assertTrue(thrown.getMessage().contains("positive-line-qty")
                        && thrown.getMessage().contains("lines-within-total"),
                "an author fixing a draft should see every failing rule in one round trip, got: "
                        + thrown.getMessage());
    }

    @Test
    void withNoInvariantEngineAvailableTheAggregateCommitsExactlyAsBefore() {
        CompiledModel model;
        try {
            ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
            model = new ModelCompiler().compile(ast);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        ConceptGateway bare = new DefaultConceptGateway(new InMemoryConceptStore());
        AggregateRuntime withoutEngine = new AggregateRuntime(model, bare, null, null, null);

        // Violates both invariants -- but with no engine wired there is nothing to evaluate them,
        // which is the pre-R4.4 behaviour every existing direct caller still gets.
        withoutEngine.commit("Order", draft(10, 0, 11), ctx);

        assertTrue(bare.read(new ConceptReadRequest("Order", "O1", null), ctx).isPresent(),
                "absent an InvariantEngine, commit must behave exactly as it did before R4.4");
    }
}
