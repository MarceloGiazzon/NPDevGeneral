package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** LIFT-EXPR-P3: compile-time validation of invariant `expression` invariants. */
class InvariantExpressionValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static String modelWithInvariant(String expression) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.inv", "version": "1.0",
              "concepts": [
                { "name": "Item", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "pos", "type": "integer" },
                  { "name": "cxPad", "type": "integer" },
                  { "name": "cxAvulsas", "type": "integer" },
                  { "name": "total", "type": "integer" } ],
                  "invariants": [ { "name": "inv1", "type": "expression", "expression": "%s" } ] }
              ]
            }
            """.formatted(expression);
    }

    @Test
    void typoedFieldInParenthesizedExpressionFailsWithFieldLocatedMessage() throws Exception {
        List<String> errors = validate(modelWithInvariant("(pos > 0 && totall == 1) || cxAvulsas > 0"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("references unknown field totall")),
                "expected an unknown-field error, got: " + errors);
    }

    @Test
    void nonBooleanExpressionIsRejected() throws Exception {
        List<String> errors = validate(modelWithInvariant("pos * cxPad + cxAvulsas"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("must evaluate to a boolean")),
                "expected a boolean-shape error, got: " + errors);
    }

    @Test
    void parenthesizedArithmeticInvariantPasses() throws Exception {
        List<String> errors = validate(modelWithInvariant("total == pos*cxPad + cxAvulsas"));
        assertTrue(errors.stream().noneMatch(e -> e.contains("invariant expression")),
                "unexpected invariant expression error, got: " + errors);
    }

    @Test
    void negationAndParensPass() throws Exception {
        List<String> errors = validate(modelWithInvariant("(pos > 0 && cxPad != 0) || !(cxAvulsas > 0)"));
        assertTrue(errors.stream().noneMatch(e -> e.contains("invariant expression")),
                "unexpected invariant expression error, got: " + errors);
    }

    @Test
    void celSpecificSyntaxIsUnaffectedByStaticShapeCheck() throws Exception {
        // scope.exists(...) doesn't parse via ComputedExpression; must remain accepted at
        // compile time and left to CelInvariantEngine at runtime (unchanged legacy behavior).
        List<String> errors = validate(modelWithInvariant("scope.exists(\\\"Other\\\", \\\"id\\\", pos)"));
        assertTrue(errors.stream().noneMatch(e -> e.contains("invariant expression")),
                "unexpected invariant expression error, got: " + errors);
    }
}
