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
}
