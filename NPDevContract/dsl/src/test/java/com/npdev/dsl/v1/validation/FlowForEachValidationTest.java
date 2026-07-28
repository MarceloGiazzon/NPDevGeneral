package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** LIFT-LOOP-P1: `forEach` flow step schema/DSL support. */
class FlowForEachValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String flowStepsJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.loop", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "total", "type": "integer" } ] }
              ],
              "flows": [
                { "name": "SumOrders", "concept": "Order", "steps": %s }
              ]
            }
            """.formatted(flowStepsJson);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    @Test
    void forEachCompilesAndValidates() throws Exception {
        String json = modelJson("""
            [
              { "name": "sum-orders", "type": "forEach", "collection": "input.orders", "itemKey": "order",
                "maxLoopIterations": 500,
                "steps": [
                  { "name": "return-item", "type": "return", "value": "order" }
                ]
              }
            ]
            """);
        List<String> errors = validate(json);
        assertTrue(errors.isEmpty(), "unexpected errors: " + errors);

        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledFlow flow = compiled.getFlows().stream().findFirst().orElseThrow();
        CompiledFlowStep step = flow.getSteps().get(0);
        assertEquals("forEach", step.getType());
        assertEquals("input.orders", step.getCollectionRef());
        assertEquals("order", step.getItemKey());
        assertEquals(500, step.getMaxLoopIterations());
        assertEquals(1, step.getLoopSteps().size());
        assertEquals("return", step.getLoopSteps().get(0).getType());
    }

    @Test
    void emptyLoopBodyIsRejectedAtSchemaLevel() {
        assertThrows(ModelSchemaValidationException.class, () -> new JsonModelParser().parse(
                MAPPER.readTree(modelJson("""
                    [ { "name": "sum-orders", "type": "forEach", "collection": "input.orders", "itemKey": "order", "steps": [] } ]
                    """))
        ));
    }

    @Test
    void missingCollectionIsRejectedAtSchemaLevel() {
        assertThrows(ModelSchemaValidationException.class, () -> new JsonModelParser().parse(
                MAPPER.readTree(modelJson("""
                    [ { "name": "sum-orders", "type": "forEach", "itemKey": "order",
                        "steps": [ { "name": "return-item", "type": "return", "value": "order" } ] } ]
                    """))
        ));
    }

    @Test
    void nestedAwaitInsideLoopBodyIsRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            [
              { "name": "sum-orders", "type": "forEach", "collection": "input.orders", "itemKey": "order",
                "steps": [
                  { "name": "wait-for-approval", "type": "awaitEvent", "awaitEvent": "OrderApproved", "awaitRef": "approval" }
                ]
              }
            ]
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("await is not supported inside a forEach loop body")),
                "expected an await-inside-loop error, got: " + errors);
    }

    @Test
    void zeroMaxLoopIterationsIsRejectedAtSchemaLevel() {
        // Schema-level minimum:1 rejects 0 before SemanticValidator's own positive-value check
        // (added defensively for any future non-schema-validated construction path) would run.
        assertThrows(ModelSchemaValidationException.class, () -> new JsonModelParser().parse(
                MAPPER.readTree(modelJson("""
                    [ { "name": "sum-orders", "type": "forEach", "collection": "input.orders", "itemKey": "order",
                        "maxLoopIterations": 0,
                        "steps": [ { "name": "return-item", "type": "return", "value": "order" } ] } ]
                    """))
        ));
    }

    @Test
    void excessiveMaxLoopIterationsIsRejectedBySemanticValidator() throws Exception {
        List<String> errors = validate(modelJson("""
            [
              { "name": "sum-orders", "type": "forEach", "collection": "input.orders", "itemKey": "order",
                "maxLoopIterations": 5000000,
                "steps": [ { "name": "return-item", "type": "return", "value": "order" } ]
              }
            ]
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("maxLoopIterations must not exceed")),
                "expected a maxLoopIterations-ceiling error, got: " + errors);
    }

    @Test
    void itemKeyShadowingReservedFlowStateIsRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            [
              { "name": "sum-orders", "type": "forEach", "collection": "input.orders", "itemKey": "input",
                "steps": [ { "name": "return-item", "type": "return", "value": "input" } ]
              }
            ]
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("shadows a reserved flow state key")),
                "expected a reserved-state-shadowing error, got: " + errors);
    }

    @Test
    void itemKeyShadowingItsOwnCollectionRootIsRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            [
              { "name": "sum-orders", "type": "forEach", "collection": "orders", "itemKey": "orders",
                "steps": [ { "name": "return-item", "type": "return", "value": "orders" } ]
              }
            ]
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("must not shadow its own collection reference")),
                "expected a self-shadowing error, got: " + errors);
    }

    @Test
    void nestedForEachReusingOuterItemKeyIsRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            [
              { "name": "outer-loop", "type": "forEach", "collection": "input.orders", "itemKey": "order",
                "steps": [
                  { "name": "inner-loop", "type": "forEach", "collection": "order.lines", "itemKey": "order",
                    "steps": [ { "name": "return-line", "type": "return", "value": "order" } ]
                  }
                ]
              }
            ]
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("shadows enclosing forEach step")),
                "expected a nested-shadowing error, got: " + errors);
    }
}
