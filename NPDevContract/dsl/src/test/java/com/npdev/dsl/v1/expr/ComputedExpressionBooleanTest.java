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

    /**
     * R5 (MASTER-ROADMAP.md Step 5): found live -- a groupBy/aggregate proof app's invariant
     * ({@code totalAmount == totalAmountAlt}) rejected two decimal fields holding the same value at
     * different scales, because both arrived at the untyped invariant-check scope as JSON STRINGS
     * ("12.30" / "12.3000" -- a decimal field submitted as a string on purpose, to avoid a lossy
     * JS-double round-trip) rather than as {@code Number}. {@code equalsLoose}'s old
     * {@code l instanceof Number || r instanceof Number} guard only takes the numeric-equality
     * branch when at least one operand already IS a Number, so two numeric-looking Strings fell
     * through to exact string equality. Fixed to reuse the same {@code isNumericLike} check the
     * {@code +} operator already uses to decide arithmetic vs. concatenation, and to match the
     * unconditionally-numeric {@code <}/{@code <=}/{@code >}/{@code >=} operators just above it.
     */
    @Test
    void numericLookingStringsAtDifferentScalesCompareEqual() {
        Map<String, Object> row = Map.of("totalAmount", "12.30", "totalAmountAlt", "12.3000");
        assertTrue(ComputedExpression.evaluateBoolean("totalAmount == totalAmountAlt", row));
        assertFalse(ComputedExpression.evaluateBoolean("totalAmount != totalAmountAlt", row));

        Map<String, Object> unequal = Map.of("totalAmount", "10.00", "totalAmountAlt", "20.00");
        assertFalse(ComputedExpression.evaluateBoolean("totalAmount == totalAmountAlt", unequal));
        assertTrue(ComputedExpression.evaluateBoolean("totalAmount != totalAmountAlt", unequal));

        // Non-numeric strings still compare lexically, not numerically (0 == 0 would be a false
        // positive if isNumericLike's parse failure silently fell back to treating both as 0).
        Map<String, Object> nonNumeric = Map.of("a", "abc", "b", "def");
        assertFalse(ComputedExpression.evaluateBoolean("a == b", nonNumeric));
        Map<String, Object> sameNonNumeric = Map.of("a", "abc", "b", "abc");
        assertTrue(ComputedExpression.evaluateBoolean("a == b", sameNonNumeric));
    }
}
