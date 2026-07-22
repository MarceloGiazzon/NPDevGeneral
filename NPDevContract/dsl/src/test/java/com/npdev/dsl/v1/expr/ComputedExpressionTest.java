package com.npdev.dsl.v1.expr;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the AutoPanel computed-column expression engine (P3 slice 2). */
class ComputedExpressionTest {

    private static Object eval(String expr, Map<String, Object> vars) {
        return ComputedExpression.evaluate(expr, vars);
    }

    @Test
    void arithmeticWithFieldsAndPrecedence() {
        // The WMS Total: pos*cxPad + cxAvulsas, with cxPad=42.
        Map<String, Object> row = Map.of("pos", 7, "cxPad", 42, "cxAvulsas", 3);
        assertEquals(297L, eval("pos*cxPad + cxAvulsas", row));       // 7*42 + 3
        assertEquals(294L, eval("pos * cxPad", row));
        assertEquals(63L, eval("(pos + 2) * 7", row));                 // parens override precedence
        assertEquals(10L, eval("pos + cxAvulsas", row));
    }

    @Test
    void wholeResultsRenderAsIntegersFractionsAsDoubles() {
        assertEquals(5L, eval("10 / 2", Map.of()));
        assertEquals(2.5d, eval("5 / 2", Map.of()));
        assertEquals(1L, eval("7 % 3", Map.of()));
    }

    @Test
    void comparisonAndLogical() {
        Map<String, Object> row = Map.of("pos", 7, "max", 5);
        assertEquals(Boolean.TRUE, eval("pos > max", row));
        assertEquals(Boolean.FALSE, eval("pos <= max", row));
        assertEquals(Boolean.TRUE, eval("pos > max && max > 0", row));
        assertEquals(Boolean.TRUE, eval("pos == 7", row));
        assertEquals(Boolean.FALSE, eval("pos != 7", row));
    }

    @Test
    void unaryAndStrings() {
        assertEquals(-5L, eval("-5", Map.of()));
        assertEquals(Boolean.TRUE, eval("!false", Map.of()));
        assertEquals("Rua A", eval("'Rua ' + code", Map.of("code", "A")));
    }

    @Test
    void missingOrStringNumericFieldsCoerceLeniently() {
        assertEquals(84L, eval("cxPad * 2", Map.of("cxPad", "42")));      // string-numeric "42" -> 42
        assertEquals(0L, eval("pos * cxPad", Map.of("cxPad", 42)));       // pos missing -> 0
        assertEquals(52L, eval("qty + 10", Map.of("qty", "42")));         // "42" -> 42
        assertEquals(0L, eval("bogus / 3", Map.of()));                    // missing -> 0
    }

    @Test
    void divideByZeroIsSafe() {
        assertEquals(0L, eval("pos / zero", Map.of("pos", 5, "zero", 0)));
    }

    @Test
    void syntaxErrorsAreReported() {
        assertThrows(ComputedExpression.ExpressionException.class, () -> ComputedExpression.validate("pos * "));
        assertThrows(ComputedExpression.ExpressionException.class, () -> ComputedExpression.validate("(pos + 1"));
        assertThrows(ComputedExpression.ExpressionException.class, () -> ComputedExpression.validate("pos $ 2"));
        assertThrows(ComputedExpression.ExpressionException.class, () -> ComputedExpression.validate("'unterminated"));
    }

    @Test
    void validExpressionsPassValidation() {
        assertDoesNotThrow(() -> ComputedExpression.validate("pos*cxPad + cxAvulsas"));
        assertDoesNotThrow(() -> ComputedExpression.validate("(a + b) * c - d / 2"));
        assertDoesNotThrow(() -> ComputedExpression.validate("x > 0 && y != 'n'"));
    }

    // ---- LNCH-15: function calls, receiver.method() sugar, and lambdas --------

    @Test
    void directFunctionCallInvokesRegisteredFunction() {
        ComputedExpression.FunctionRegistry registry = ComputedExpression.FunctionRegistry.of(Map.of(
                "double", (args, vars) -> {
                    Object v = args.get(0).eval(vars);
                    return ((Number) v).longValue() * 2;
                }
        ));
        assertEquals(84L, ComputedExpression.evaluate("double(x)", Map.of("x", 42), registry));
    }

    @Test
    void receiverMethodSugarDesugarsToFunctionCallWithReceiverPrepended() {
        ComputedExpression.FunctionRegistry registry = ComputedExpression.FunctionRegistry.of(Map.of(
                "upper", (args, vars) -> String.valueOf(args.get(0).eval(vars)).toUpperCase()
        ));
        assertEquals("HELLO", ComputedExpression.evaluate("name.upper()", Map.of("name", "hello"), registry));
    }

    @Test
    void lambdaArgumentIsNotEagerlyEvaluated() {
        // A quantifier-style function: invoke the lambda per item, short-circuit on first match.
        ComputedExpression.FunctionRegistry registry = ComputedExpression.FunctionRegistry.of(Map.of(
                "exists", (args, vars) -> {
                    Object collection = args.get(0).eval(vars);
                    for (Object item : (java.util.List<?>) collection) {
                        if (Boolean.TRUE.equals(args.get(1).invokeLambda(item, vars))) {
                            return true;
                        }
                    }
                    return false;
                }
        ));
        Map<String, Object> vars = Map.of("items", java.util.List.of(
                Map.of("severity", "Mild"), Map.of("severity", "Severe")));
        assertEquals(Boolean.TRUE,
                ComputedExpression.evaluate("items.exists(a => a.severity == 'Severe')", vars, registry));
        assertEquals(Boolean.FALSE,
                ComputedExpression.evaluate("items.exists(a => a.severity == 'Extreme')", vars, registry));
    }

    @Test
    void lambdaEvaluatedOutsideAFunctionArgumentThrows() {
        assertThrows(ComputedExpression.ExpressionException.class,
                () -> ComputedExpression.evaluate("a => a > 0", Map.of()));
    }

    @Test
    void unknownFunctionThrows() {
        assertThrows(ComputedExpression.ExpressionException.class,
                () -> ComputedExpression.evaluate("bogus(1)", Map.of()));
    }

    @Test
    void callAndLambdaSyntaxParseWithoutRegistry() {
        // Parsing (for compile-time field/shape checks) must not require a registry.
        assertDoesNotThrow(() -> ComputedExpression.validate("field.matches('.+@.+')"));
        assertDoesNotThrow(() -> ComputedExpression.validate("items.uniqueBy(key)"));
        assertDoesNotThrow(() -> ComputedExpression.validate("items.all(x => x.field != null)"));
        assertDoesNotThrow(() -> ComputedExpression.validate("!conflicts(a, b, c, d)"));
        assertDoesNotThrow(() -> ComputedExpression.validate("scope.exists(\"Patient\", \"id\", patientId)"));
    }

    @Test
    void callsAreBooleanShapedForKnownFunctionNames() {
        assertTrue(ComputedExpression.isBooleanShaped("field.matches('x')"));
        assertTrue(ComputedExpression.isBooleanShaped("items.uniqueBy(key)"));
        assertTrue(ComputedExpression.isBooleanShaped("items.all(x => x > 0)"));
        assertTrue(ComputedExpression.isBooleanShaped("!conflicts(a, b, c)"));
        assertTrue(ComputedExpression.isBooleanShaped("scope.exists(\"C\", \"f\", v)"));
    }

    @Test
    void dollarSigilPseudoVariableParsesAndEvaluates() {
        // LNCH-13: $user.id etc. -- the current-actor pseudo-variable used in row-level access
        // rules (ownerId == $user.id). '$' is only valid as the leading character.
        Map<String, Object> vars = Map.of("ownerId", "u-1", "$user.id", "u-1", "$user.tenantId", "t-1");
        assertEquals(Boolean.TRUE, eval("ownerId == $user.id", vars));
        assertEquals("u-1", eval("$user.id", vars));
        assertDoesNotThrow(() -> ComputedExpression.validate("ownerId == $user.id && tenantId == $user.tenantId"));
    }

    @Test
    void referencedFieldsSkipsLambdaBodyAndUniqueByKeyArg() {
        // The lambda alias and uniqueBy's per-item key are scoped to the item, not the outer
        // concept -- collecting them as "referenced fields" would produce false unknown-field hits.
        java.util.Set<String> allFields = ComputedExpression.referencedFields("items.all(x => x.field > 0)");
        assertEquals(java.util.Set.of("items"), allFields);

        java.util.Set<String> uniqueByFields = ComputedExpression.referencedFields("items.uniqueBy(key)");
        assertEquals(java.util.Set.of("items"), uniqueByFields);

        java.util.Set<String> conflictsFields = ComputedExpression.referencedFields("conflicts(a, b, c)");
        assertEquals(java.util.Set.of("a", "b", "c"), conflictsFields);
    }
}
