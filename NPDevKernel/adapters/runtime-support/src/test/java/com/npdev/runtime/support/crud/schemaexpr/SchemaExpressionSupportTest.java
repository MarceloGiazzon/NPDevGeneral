package com.npdev.runtime.support.crud.schemaexpr;

import com.npdev.dsl.v1.expr.ComputedExpression;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * REG-284 (X0-14): evaluateSchemaExpression used to answer null for anything it could not evaluate, and a
 * null derived value removes the field -- so a typo'd function in a derivedExpression erased it on every
 * save. An unevaluable expression is an error now; a genuinely null result stays null.
 */
class SchemaExpressionSupportTest {

    @Test
    void unknownFunctionThrowsInsteadOfYieldingNull() {
        Map<String, Object> values = Map.of("firstName", "Ada");
        assertThrows(ComputedExpression.ExpressionException.class,
                () -> SchemaExpressionSupport.evaluateSchemaExpression("uppercse(firstName)", values));
        assertThrows(ComputedExpression.ExpressionException.class,
                () -> SchemaExpressionSupport.evaluateSchemaExpression("now()", values));
    }

    @Test
    void registeredFunctionsLiteralsAndGenuineNullsStillEvaluate() {
        Map<String, Object> values = new HashMap<>();
        values.put("lastName", "Lovelace");
        values.put("firstName", "Ada");
        values.put("nickname", null);
        assertEquals("Lovelace, Ada", SchemaExpressionSupport.evaluateSchemaExpression(
                "concat(lastName, ', ', firstName)", values));
        assertEquals("ADA", SchemaExpressionSupport.evaluateSchemaExpression("uppercase(firstName)", values));
        assertEquals("draft", SchemaExpressionSupport.evaluateSchemaExpression("'draft'", values));
        assertNull(SchemaExpressionSupport.evaluateSchemaExpression("coalesce(nickname, missing)", values));
        assertNull(SchemaExpressionSupport.evaluateSchemaExpression("  ", values));
    }
}
