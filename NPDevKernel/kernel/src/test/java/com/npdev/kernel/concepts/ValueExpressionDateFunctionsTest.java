package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.dsl.v1.expr.DateFunctions;
import com.npdev.dsl.v1.expr.ValueBehaviorFunctions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-313 (b): {@code today()}/{@code addDays}/{@code daysBetween} reach every evaluator that shares the
 * value-function registry -- a field {@code defaultExpression}/{@code derivedExpression}
 * ({@link ValueExpressionEvaluator}), the strict backfill path, and the concept gateway's invariants
 * ({@link GatewayInvariantFunctions}).
 */
class ValueExpressionDateFunctionsTest {

    private static final Map<String, Object> PARTICIPANT = Map.of("enrolmentDate", "2026-10-01");

    @Test
    void theDslNamesTheValidatorAcceptsAreTheOnesTheRegistryResolves() {
        for (String name : DateFunctions.NAMES) {
            assertTrue(ValueBehaviorFunctions.NAMES.contains(name), name + " must be a validated value function");
            assertTrue(ValueExpressionFunctions.base().lookup(name) != null, name + " must resolve in the registry");
        }
        assertNull(ValueBehaviorFunctions.firstUnavailable("addDays(enrolmentDate, 84)", false));
        assertEquals("nope", ValueBehaviorFunctions.firstUnavailable("nope(enrolmentDate)", false));
    }

    @Test
    void aDefaultOrDerivedExpressionCanComputeAWindowFromADateField() {
        assertEquals("2026-12-24", ValueExpressionEvaluator.evaluate("addDays(enrolmentDate, 84)", PARTICIPANT));
        assertEquals("2026-10-01",
                ValueExpressionEvaluator.evaluate("addDays(addDays(enrolmentDate, 7), -7)", PARTICIPANT));
        assertEquals(84L,
                ValueExpressionEvaluator.evaluate("daysBetween(enrolmentDate, addDays(enrolmentDate, 84))", PARTICIPANT));
    }

    @Test
    void todayIsTheLiveUtcDateOnAWriteAndComposesWithTheOthers() {
        assertEquals(LocalDate.now(ZoneOffset.UTC).toString(), ValueExpressionEvaluator.evaluate("today()", Map.of()));
        assertEquals(0L, ValueExpressionEvaluator.evaluate("daysBetween(today(), addDays(today(), 0))", Map.of()));
        assertEquals(7L, ValueExpressionEvaluator.evaluate("daysBetween(today(), addDays(today(), 7))", Map.of()));
        Object pinned = DateFunctions.withToday(LocalDate.of(2026, 10, 29),
                () -> ValueExpressionEvaluator.evaluate("addDays(today(), 7)", Map.of()));
        assertEquals("2026-11-05", pinned);
    }

    @Test
    void aMissingOrBrokenOperandYieldsNullNotAnError() {
        assertNull(ValueExpressionEvaluator.evaluate("addDays(missing, 7)", Map.of()));
        assertNull(ValueExpressionEvaluator.evaluate("addDays(enrolmentDate, 1.5)", PARTICIPANT));
        assertNull(ValueExpressionEvaluator.evaluate("daysBetween(enrolmentDate, 'soon')", PARTICIPANT));
    }

    @Test
    void backfillIsDeterministicSoTodayIsTheEpochDateLikeNow() {
        ComputedExpression.FunctionRegistry registry = ValueExpressionFunctions.base();
        assertEquals("1970-01-01", ValueExpressionEvaluator.evaluateStrict("today()", Map.of(), registry));
        assertEquals("1970-01-08", ValueExpressionEvaluator.evaluateStrict("addDays(today(), 7)", Map.of(), registry));
        assertEquals(ValueExpressionEvaluator.evaluateStrict("addDays(today(), 7)", Map.of(), registry),
                ValueExpressionEvaluator.evaluateStrict("addDays(today(), 7)", Map.of(), registry));
        // an outer pin is not clobbered for later non-strict callers
        assertEquals(LocalDate.now(ZoneOffset.UTC).toString(), ValueExpressionEvaluator.evaluate("today()", Map.of()));
    }

    @Test
    void theConceptGatewayInvariantsKnowTheDateFunctionsToo() {
        ComputedExpression.FunctionRegistry registry = GatewayInvariantFunctions.registry();
        Object withinWindow = DateFunctions.withToday(LocalDate.of(2026, 12, 1), () -> ComputedExpression.evaluate(
                "daysBetween(enrolmentDate, today()) <= 84", PARTICIPANT, registry));
        Object pastWindow = DateFunctions.withToday(LocalDate.of(2027, 1, 1), () -> ComputedExpression.evaluate(
                "daysBetween(enrolmentDate, today()) <= 84", PARTICIPANT, registry));
        assertEquals(Boolean.TRUE, withinWindow);
        assertEquals(Boolean.FALSE, pastWindow);
    }
}
