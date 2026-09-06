package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.expr.ComputedExpression;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Direct coverage of the two public entry points ({@code evaluate}/{@code evaluateStrict}) --
 * exercised elsewhere only through cross-module callers ({@code ConfiguredConceptGatewaySemanticPolicy}
 * in-module, {@code ExpressionBackfillPreview} in RuntimeHost), so the kernel module's own coverage
 * ratchet never saw either method run.
 */
class ValueExpressionEvaluatorTest {

    @Test
    void evaluateDispatchesEverySpecialFormAndFallsBackToRawTextWhenUnparseable() {
        assertNull(ValueExpressionEvaluator.evaluate(null, Map.of()));
        assertNull(ValueExpressionEvaluator.evaluate("  ", Map.of()));
        assertEquals(Instant.EPOCH.toString(), ValueExpressionEvaluator.evaluate("now()", Map.of()));
        assertEquals(UUID.nameUUIDFromBytes("npdev-deterministic-concept-default".getBytes()).toString(),
                ValueExpressionEvaluator.evaluate("uuid()", Map.of()));
        assertEquals("alpha", ValueExpressionEvaluator.evaluate("$name", Map.of("name", "alpha")));
        assertEquals("draft", ValueExpressionEvaluator.evaluate("'draft'", Map.of()));
        assertEquals(true, ValueExpressionEvaluator.evaluate("true", Map.of()));
        assertEquals(42L, ValueExpressionEvaluator.evaluate("42", Map.of()));
        assertEquals(4.5, ValueExpressionEvaluator.evaluate("4.5", Map.of()));
        assertEquals(42L, ValueExpressionEvaluator.evaluate(
                "quantity * unitPrice", Map.of("quantity", 6, "unitPrice", 7)));
        // Unknown function -- ComputedExpression throws, evaluate() swallows it and returns the raw text.
        assertEquals("bogus(1)", ValueExpressionEvaluator.evaluate("bogus(1)", Map.of()));
    }

    @Test
    void evaluateStrictSharesTheSameSpecialFormsButResolvesFunctionsThroughTheGivenRegistry() {
        ComputedExpression.FunctionRegistry registry = ValueExpressionFunctions.base();
        assertNull(ValueExpressionEvaluator.evaluateStrict(null, Map.of(), registry));
        assertEquals(Instant.EPOCH.toString(), ValueExpressionEvaluator.evaluateStrict("now()", Map.of(), registry));
        assertEquals(UUID.nameUUIDFromBytes("npdev-deterministic-concept-default".getBytes()).toString(),
                ValueExpressionEvaluator.evaluateStrict("uuid()", Map.of(), registry));
        assertEquals("alpha", ValueExpressionEvaluator.evaluateStrict("$name", Map.of("name", "alpha"), registry));
        assertEquals("draft", ValueExpressionEvaluator.evaluateStrict("'draft'", Map.of(), registry));
        assertEquals(true, ValueExpressionEvaluator.evaluateStrict("true", Map.of(), registry));
        assertEquals(42L, ValueExpressionEvaluator.evaluateStrict("42", Map.of(), registry));
        assertEquals(4.5, ValueExpressionEvaluator.evaluateStrict("4.5", Map.of(), registry));
        assertEquals("fallback", ValueExpressionEvaluator.evaluateStrict(
                "coalesce(missing, 'fallback')", Map.of(), registry));
    }

    @Test
    void evaluateStrictThrowsInsteadOfFallingBackToRawTextWhenUnresolvable() {
        ComputedExpression.FunctionRegistry registry = ValueExpressionFunctions.base();
        assertThrows(ComputedExpression.ExpressionException.class,
                () -> ValueExpressionEvaluator.evaluateStrict("bogus(1)", Map.of(), registry));
    }
}
