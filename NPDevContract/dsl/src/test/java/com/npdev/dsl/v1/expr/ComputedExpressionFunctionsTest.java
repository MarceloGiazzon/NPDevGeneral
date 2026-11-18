package com.npdev.dsl.v1.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.npdev.dsl.v1.expr.ComputedExpression.ExpressionException;
import com.npdev.dsl.v1.expr.ComputedExpression.UserFunction;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** FORM P4-T2: user functions (extension ladder rung 2) are inlined into plain expressions. */
class ComputedExpressionFunctionsTest {

    private static Function<String, UserFunction> table(UserFunction... functions) {
        Map<String, UserFunction> byName = new java.util.HashMap<>();
        for (UserFunction f : functions) {
            byName.put(f.name(), f);
        }
        return byName::get;
    }

    private static final UserFunction DOUBLE = new UserFunction("double", List.of("x"), "x * 2");
    private static final UserFunction SURGE = new UserFunction(
            "surge", List.of("open", "free"), "max(open - free, 0) * 0.1 + 1");

    private static void assertNumber(String expected, Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(String.valueOf(actual))), "expected " + expected + " but got " + actual);
    }

    private static Object eval(String expression, Map<String, Object> vars, UserFunction... functions) {
        String inlined = ComputedExpression.inlineFunctions(expression, table(functions));
        return ComputedExpression.evaluate(inlined, vars, StdlibFunctions.registry());
    }

    @Test
    void anExpressionWithoutUserCallsIsReturnedUnchanged() {
        assertEquals("a + b", ComputedExpression.inlineFunctions("a + b", table(DOUBLE)));
    }

    @Test
    void argumentsAreSubstitutedAndParenthesisedSoPrecedenceSurvives() {
        // double(a + 1) must be (a + 1) * 2, not a + 1 * 2
        assertEquals("(a + 1) * 2", ComputedExpression.inlineFunctions("double(a + 1)", table(DOUBLE)));
        assertNumber("8", eval("double(a + 1)", Map.of("a", 3), DOUBLE));
    }

    @Test
    void anArgumentUsedTwiceIsEvaluatedFromTheSameText() {
        UserFunction square = new UserFunction("square", List.of("x"), "x * x");
        assertNumber("25", eval("square(2 + 3)", Map.of(), square));
    }

    @Test
    void functionsCallFunctions() {
        UserFunction price = new UserFunction("price", List.of("base", "open", "free"), "base * surge(open, free)");
        assertNumber("12", eval("price(10, 4, 2)", Map.of(), price, SURGE));
    }

    @Test
    void methodSugarCallsAUserFunctionToo() {
        assertNumber("14", eval("a.double()", Map.of("a", 7), DOUBLE));
    }

    @Test
    void aNamespacedPackFunctionIsOneIdentifier() {
        UserFunction pack = new UserFunction("pricing::double", List.of("x"), "x * 2");
        assertEquals("a * 2", ComputedExpression.inlineFunctions("pricing::double(a)", table(pack)));
    }

    @Test
    void aDottedParameterReadsTheFieldOfAFieldArgument() {
        UserFunction rating = new UserFunction("rating", List.of("ride"), "ride.stars * 2");
        assertEquals("trip.stars * 2", ComputedExpression.inlineFunctions("rating(trip)", table(rating)));
        assertThrows(ExpressionException.class, () -> ComputedExpression.inlineFunctions("rating(1 + 2)", table(rating)));
    }

    @Test
    void aLambdaParameterShadowsAFunctionParameter() {
        UserFunction f = new UserFunction("f", List.of("x"), "items.all(x => x > 0)");
        assertEquals("all(items, x => x > 0)", ComputedExpression.inlineFunctions("f(5)", table(f)));
    }

    @Test
    void wrongArgumentCountNamesTheFunction() {
        ExpressionException e = assertThrows(ExpressionException.class,
                () -> ComputedExpression.inlineFunctions("surge(1)", table(SURGE)));
        assertEquals("function surge takes 2 argument(s) (open, free) but was called with 1", e.getMessage());
    }

    @Test
    void aRecursiveCallChainIsRejectedWithItsPath() {
        UserFunction a = new UserFunction("a", List.of("x"), "b(x)");
        UserFunction b = new UserFunction("b", List.of("x"), "a(x)");
        ExpressionException e = assertThrows(ExpressionException.class,
                () -> ComputedExpression.inlineFunctionBody(a, table(a, b)));
        assertEquals("function a calls itself: a -> b -> a", e.getMessage());
    }

    @Test
    void negationAndTernaryKeepTheirMeaning() {
        UserFunction pick = new UserFunction("pick", List.of("c", "x"), "!c ? -x : x");
        assertNumber("-3", eval("pick(a && b, 3)", Map.of("a", true, "b", false), pick));
        assertNumber("3", eval("pick(a || b, 3)", Map.of("a", true, "b", false), pick));
    }
}
