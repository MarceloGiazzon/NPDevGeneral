package com.npdev.dsl.v1.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FORM P3-T2: decimal arithmetic (no binary-float artefacts) and strict vs lenient evaluation. */
class ComputedExpressionDecimalStrictTest {

    private static Map<String, Object> vars() {
        Map<String, Object> v = new HashMap<>();
        v.put("price", new BigDecimal("19.99"));
        v.put("qty", 3);
        v.put("nothing", null);
        v.put("text", "abc");
        v.put("amount", "12.30");
        return v;
    }

    private static Object lenient(String expression) {
        return ComputedExpression.evaluate(expression, vars());
    }

    private static Object strict(String expression) {
        return ComputedExpression.evaluateStrict(expression, vars(), ComputedExpression.FunctionRegistry.empty());
    }

    @Test
    void decimalArithmeticIsExact() {
        assertEquals(new BigDecimal("0.3"), lenient("0.1 + 0.2"));
        assertEquals(new BigDecimal("59.97"), lenient("price * qty"));
        assertEquals(new BigDecimal("0.1"), lenient("0.3 - 0.2"));
        assertEquals(new BigDecimal("1.1"), lenient("1.1 * 1"));
        assertEquals(new BigDecimal("12.3"), lenient("amount + 0"));
    }

    @Test
    void integersStayLong() {
        assertEquals(7L, lenient("3 + 4"));
        assertEquals(3L, lenient("1.5 + 1.5"));
        assertEquals(12L, lenient("qty * 4"));
        assertEquals(Long.MAX_VALUE, lenient("9223372036854775806 + 1"));
        assertEquals(1L, lenient("10 % 3"));
    }

    @Test
    void bigIntegersOverflowIntoDecimalNotWrapAround() {
        assertEquals(new BigDecimal("9223372036854775808"), lenient("9223372036854775807 + 1"));
    }

    @Test
    void divisionUsesDecimal64() {
        assertEquals(new BigDecimal("2.5"), lenient("10 / 4"));
        assertEquals(new BigDecimal("0.3333333333333333"), lenient("1 / 3"));
        assertEquals(new BigDecimal("0.5"), lenient("5.5 % 1"));
    }

    @Test
    void comparisonsAreDecimalToo() {
        assertEquals(true, lenient("0.1 + 0.2 == 0.3"));
        assertEquals(true, lenient("0.1 + 0.2 <= 0.3"));
        assertEquals(false, lenient("0.1 + 0.2 > 0.3"));
        assertEquals(true, lenient("amount == '12.3000'"));
    }

    @Test
    void lenientKeepsLegacyDefaults() {
        assertEquals(0L, lenient("nothing * 2"));
        assertEquals(0L, lenient("text * 2"));
        assertEquals(0L, lenient("10 / 0"));
        assertEquals(0L, lenient("10 % 0"));
        assertEquals("1", lenient("nothing + 1"));
        assertEquals(13L, lenient("'12d' + 1"));
        assertEquals(1000L, lenient("'1e3' * 1"));
    }

    @Test
    void strictRejectsNullArithmeticAndNamesTheSubExpression() {
        ComputedExpression.ExpressionException e = assertThrows(ComputedExpression.ExpressionException.class,
                () -> strict("price * nothing"));
        assertTrue(e.getMessage().contains("null operand `nothing`"), e.getMessage());
        assertTrue(e.getMessage().contains("`price * nothing`"), e.getMessage());
        assertThrows(ComputedExpression.ExpressionException.class, () -> strict("nothing + 1"));
        assertThrows(ComputedExpression.ExpressionException.class, () -> strict("-nothing"));
        assertThrows(ComputedExpression.ExpressionException.class, () -> strict("text * 2"));
    }

    @Test
    void strictRejectsDivisionByZero() {
        ComputedExpression.ExpressionException e = assertThrows(ComputedExpression.ExpressionException.class,
                () -> strict("(price + 1) / (qty - 3)"));
        assertTrue(e.getMessage().contains("division by zero"), e.getMessage());
        assertTrue(e.getMessage().contains("(price + 1) / (qty - 3)"), e.getMessage());
        assertThrows(ComputedExpression.ExpressionException.class, () -> strict("5 % 0"));
    }

    @Test
    void strictAllowsCoalesceAndOrdinaryValues() {
        assertEquals(new BigDecimal("59.97"), strict("price * qty"));
        assertEquals(3L, strict("(nothing ?? 0) + 3"));
        assertEquals("ab", strict("'a' + 'b'"));
        assertEquals(true, strict("nothing == null"));
    }

    @Test
    void strictModeDoesNotLeakIntoLaterLenientCalls() {
        assertThrows(ComputedExpression.ExpressionException.class, () -> strict("nothing * 2"));
        assertEquals(0L, lenient("nothing * 2"));
    }

    @Test
    void strictBooleanEntryPoint() {
        assertEquals(true, ComputedExpression.evaluateBooleanStrict("qty * 2 > 5", vars(),
                ComputedExpression.FunctionRegistry.empty()));
        assertThrows(ComputedExpression.ExpressionException.class, () -> ComputedExpression.evaluateBooleanStrict(
                "nothing * 2 > 5", vars(), ComputedExpression.FunctionRegistry.empty()));
    }
}
