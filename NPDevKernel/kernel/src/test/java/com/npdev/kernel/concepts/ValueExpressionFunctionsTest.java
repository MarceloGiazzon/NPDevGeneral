package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.expr.ComputedExpression;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOR-26 (B2 lift): the five value-behavior functions plus {@code scope.exists}, evaluated through
 * {@link ComputedExpression#evaluate(String, Map, ComputedExpression.FunctionRegistry)} exactly as
 * a real caller ({@code ExpressionBackfillPreview}, RuntimeHost) would.
 */
class ValueExpressionFunctionsTest {

    @Test
    void concatJoinsArgumentsAndIsNullIfAnyArgumentIsNull() {
        ComputedExpression.FunctionRegistry registry = ValueExpressionFunctions.base();
        assertEquals("alpha-tag", ComputedExpression.evaluate("concat(name, '-tag')", Map.of("name", "alpha"), registry));
        assertNull(ComputedExpression.evaluate("concat(missing, '-tag')", Map.of(), registry));
    }

    @Test
    void coalesceReturnsTheFirstNonMissingValue() {
        ComputedExpression.FunctionRegistry registry = ValueExpressionFunctions.base();
        assertEquals("fallback", ComputedExpression.evaluate(
                "coalesce(missing, 'fallback')", Map.of(), registry));
        assertEquals("real", ComputedExpression.evaluate(
                "coalesce(value, 'fallback')", Map.of("value", "real"), registry));
    }

    @Test
    void trimUppercaseLowercaseApplyToASingleArgument() {
        ComputedExpression.FunctionRegistry registry = ValueExpressionFunctions.base();
        assertEquals("alpha", ComputedExpression.evaluate("trim(value)", Map.of("value", "  alpha  "), registry));
        assertEquals("ALPHA", ComputedExpression.evaluate("uppercase(value)", Map.of("value", "alpha"), registry));
        assertEquals("alpha", ComputedExpression.evaluate("lowercase(value)", Map.of("value", "ALPHA"), registry));
    }

    @Test
    void baseRegistryDoesNotResolveScopeExists() {
        ComputedExpression.FunctionRegistry registry = ValueExpressionFunctions.base();
        assertNull(registry.lookup("scope.exists"));
    }

    @Test
    void withScopeResolvesScopeExistsAgainstTheSuppliedCheck() {
        ValueExpressionFunctions.ScopeExistsFn stub = (concept, fieldPath, value) ->
                "categories".equals(concept) && "code".equals(fieldPath) && "ALPHA".equals(value);
        ComputedExpression.FunctionRegistry registry = ValueExpressionFunctions.withScope(stub);

        assertTrue((Boolean) ComputedExpression.evaluate(
                "scope.exists('categories', 'code', code)", Map.of("code", "ALPHA"), registry));
        assertFalse((Boolean) ComputedExpression.evaluate(
                "scope.exists('categories', 'code', code)", Map.of("code", "ZETA"), registry));
    }

    @Test
    void withScopeStillResolvesTheFiveValueBehaviorFunctions() {
        ValueExpressionFunctions.ScopeExistsFn stub = (concept, fieldPath, value) -> true;
        ComputedExpression.FunctionRegistry registry = ValueExpressionFunctions.withScope(stub);
        assertEquals("alpha-tag", ComputedExpression.evaluate("concat(name, '-tag')", Map.of("name", "alpha"), registry));
    }

    @Test
    void applyMatchesEachFunctionsDocumentedBehavior() {
        assertEquals("ab", ValueExpressionFunctions.apply("concat", java.util.List.of("a", "b")));
        assertNull(ValueExpressionFunctions.apply("concat", java.util.Arrays.asList("a", null)));
        assertEquals("a", ValueExpressionFunctions.apply("coalesce", java.util.Arrays.asList(null, "a")));
        assertEquals("a", ValueExpressionFunctions.apply("trim", java.util.List.of("  a  ")));
        assertEquals("A", ValueExpressionFunctions.apply("uppercase", java.util.List.of("a")));
        assertEquals("a", ValueExpressionFunctions.apply("lowercase", java.util.List.of("A")));
    }
}
