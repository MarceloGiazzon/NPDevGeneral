package com.npdev.dsl.v1.expr;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** LIFT-EXPR-P1: boolean-completeness of {@link ComputedExpression} (parens/!/null/dotted paths). */
class ComputedExpressionBooleanTest {

    @Test
    void parenNotAndArithmeticCombine() {
        Map<String, Object> row = Map.of("a", 5, "b", 3, "c", 1, "flag", false);
        assertTrue(ComputedExpression.evaluateBoolean("(a > b && c != null) || !flag", row));
        assertTrue(ComputedExpression.evaluateBoolean("!flag", row));
        assertFalse(ComputedExpression.evaluateBoolean("(a > b && c == null) && flag", row));
    }

    @Test
    void arithmeticInvariantEvaluatesCorrectly() {
        Map<String, Object> row = Map.of("total", 297, "pos", 7, "cxPad", 42, "cxAvulsas", 3);
        assertTrue(ComputedExpression.evaluateBoolean("total == pos*cxPad + cxAvulsas", row));
        row = Map.of("total", 1, "pos", 7, "cxPad", 42, "cxAvulsas", 3);
        assertFalse(ComputedExpression.evaluateBoolean("total == pos*cxPad + cxAvulsas", row));
    }

    @Test
    void nullEqualityIsStrict() {
        Map<String, Object> row = new HashMap<>();
        row.put("a", null);
        row.put("b", "");
        assertTrue(ComputedExpression.evaluateBoolean("a == null", row));
        assertFalse(ComputedExpression.evaluateBoolean("a == b", row)); // null != ""
        assertTrue(ComputedExpression.evaluateBoolean("missing == null", row)); // absent key -> null
        assertFalse(ComputedExpression.evaluateBoolean("a != null", row));
    }

    @Test
    void dottedFieldPathsResolveNestedScope() {
        Map<String, Object> cliente = Map.of("tipo", "PJ");
        Map<String, Object> row = Map.of("cliente", cliente);
        assertTrue(ComputedExpression.evaluateBoolean("cliente.tipo == 'PJ'", row));
        assertFalse(ComputedExpression.evaluateBoolean("cliente.tipo == 'PF'", row));
        assertTrue(ComputedExpression.evaluateBoolean("cliente.faltante == null", row));
    }

    @Test
    void deMorganEquivalenceHoldsWithinTheEngine() {
        Map<String, Object> row = Map.of("a", true, "b", false);
        assertEquals(
                ComputedExpression.evaluateBoolean("!(a && b)", row),
                ComputedExpression.evaluateBoolean("!a || !b", row)
        );
        assertEquals(
                ComputedExpression.evaluateBoolean("!(a || b)", row),
                ComputedExpression.evaluateBoolean("!a && !b", row)
        );
    }

    @Test
    void nonBooleanTopLevelResultThrows() {
        assertThrows(ComputedExpression.ExpressionException.class,
                () -> ComputedExpression.evaluateBoolean("1 + 1", Map.of()));
    }

    @Test
    void referencedFieldsCollectsAllVarsIncludingDotted() {
        assertEquals(
                java.util.Set.of("cliente.tipo", "total", "a"),
                ComputedExpression.referencedFields("(cliente.tipo == 'PJ' && total > 0) || a")
        );
    }

    @Test
    void booleanShapeIsSyntacticNotEvaluated() {
        assertTrue(ComputedExpression.isBooleanShaped("a > b"));
        assertTrue(ComputedExpression.isBooleanShaped("a == b && c != d"));
        assertTrue(ComputedExpression.isBooleanShaped("!flag"));
        assertTrue(ComputedExpression.isBooleanShaped("(a > b) || (c < d)"));
        assertFalse(ComputedExpression.isBooleanShaped("a + b"));
        assertFalse(ComputedExpression.isBooleanShaped("a"));
        assertFalse(ComputedExpression.isBooleanShaped("'literal'"));
    }

    @Test
    void stringLiteralsAndUnaryMinus() {
        assertEquals("Rua A", ComputedExpression.evaluate("'Rua ' + code", Map.of("code", "A")));
        assertEquals(-5L, ComputedExpression.evaluate("-5", Map.of()));
    }
}
