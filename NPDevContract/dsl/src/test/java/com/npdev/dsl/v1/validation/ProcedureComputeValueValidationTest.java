package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, final item / REG-78): a {@code computeValue}
 * procedure step needs a known operator ("add"/"subtract"), both {@code left}/{@code right}
 * operands present, and a {@code target} naming where the result is written.
 */
class ProcedureComputeValueValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String stepJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.qcomputevalue", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "procedures": [
                { "name": "IncrementQuantity", "steps": [ %s ] }
              ]
            }
            """.formatted(stepJson);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    @Test
    void wellFormedComputeValuePasses() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "increment", "type": "computeValue", "operation": "add",
              "left": "$existing.quantidade", "right": "$delta", "target": "newQuantidade" }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("computeValue")), "unexpected errors: " + errors);
    }

    @Test
    void subtractIsAlsoAKnownOperator() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "decrement", "type": "computeValue", "operation": "subtract",
              "left": "$existing.quantidade", "right": 1, "target": "newQuantidade" }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("computeValue")), "unexpected errors: " + errors);
    }

    @Test
    void unknownOperatorIsRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "multiply", "type": "computeValue", "operation": "multiply",
              "left": 2, "right": 3, "target": "product" }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("computeValue requires operation to be one of")),
                "expected an unknown-operator error, got: " + errors);
    }

    @Test
    void missingLeftIsRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "increment", "type": "computeValue", "operation": "add",
              "right": 1, "target": "newQuantidade" }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("left is required for computeValue")),
                "expected a missing-left error, got: " + errors);
    }

    @Test
    void missingRightIsRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "increment", "type": "computeValue", "operation": "add",
              "left": "$existing.quantidade", "target": "newQuantidade" }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("right is required for computeValue")),
                "expected a missing-right error, got: " + errors);
    }

    @Test
    void missingTargetIsRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "increment", "type": "computeValue", "operation": "add",
              "left": "$existing.quantidade", "right": 1 }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("target is required for computeValue")),
                "expected a missing-target error, got: " + errors);
    }
}
