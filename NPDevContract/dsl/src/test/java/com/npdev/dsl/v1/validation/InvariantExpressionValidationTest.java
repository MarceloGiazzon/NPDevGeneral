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
        // LNCH-15: scope.exists(...) now DOES parse via ComputedExpression (function-call
        // syntax) and is boolean-shaped by construction (Call.looksBoolean() is permissive), so
        // this must still pass -- runtime evaluation stays CelInvariantEngine's job either way.
        List<String> errors = validate(modelWithInvariant("scope.exists(\\\"Other\\\", \\\"id\\\", pos)"));
        assertTrue(errors.stream().noneMatch(e -> e.contains("invariant expression")),
                "unexpected invariant expression error, got: " + errors);
    }

    @Test
    void unknownFieldInsideUniqueByCollectionArgumentIsCaught() throws Exception {
        // LNCH-15: uniqueBy's collection argument now gets the SAME compile-time unknown-field
        // check as any other expression -- a genuinely new capability (this form used to be
        // unparseable by ComputedExpression, so referencedFields() silently returned nothing for
        // it). The per-item key argument ("allergen") is deliberately NOT checked -- it's scoped
        // to each collection item, not this concept's own fields.
        List<String> errors = validate(modelWithInvariant("bogusCollection.uniqueBy(allergen)"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("references unknown field bogusCollection")),
                "expected an unknown-field error for the collection arg, got: " + errors);
    }

    @Test
    void negatedConflictsExpressionIsBooleanShapedAndFieldChecked() throws Exception {
        // A parenthesized, negated, function-call invariant -- the exact shape the doc's DoD
        // calls out ("a parenthesized negated invariant works end-to-end") -- must be both
        // accepted as boolean-shaped and have its field arguments compile-time checked.
        List<String> errors = validate(modelWithInvariant("!(bogusField > 0)"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("references unknown field bogusField")),
                "expected an unknown-field error, got: " + errors);
        assertTrue(errors.stream().noneMatch(e -> e.contains("must evaluate to a boolean")),
                "negated parenthesized comparison must be boolean-shaped, got: " + errors);
    }
}
