package com.npdev.dsl.v1.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** FORM P3-T2: the static type checker reports definite mistakes only. One table per behaviour. */
class ExpressionTypeCheckerTest {

    private static final Map<String, ExprType> FIELDS = Map.of(
            "price", ExprType.NUMBER, "qty", ExprType.NUMBER, "name", ExprType.TEXT, "active", ExprType.BOOLEAN,
            "startDate", ExprType.DATE, "createdAt", ExprType.DATETIME, "tags", ExprType.LIST);

    private static final Function<String, ExprType> SCOPE = FIELDS::get;

    private static List<String> check(String expression) {
        return ExpressionTypeChecker.check(expression, SCOPE, ExpressionTypeChecker.Expected.ANY, false);
    }

    private static void assertClean(String... expressions) {
        for (String expression : expressions) {
            assertEquals(List.of(), check(expression), expression);
        }
    }

    private static void assertRefused(String expression, String messagePart) {
        List<String> errors = check(expression);
        assertTrue(errors.stream().anyMatch(e -> e.contains(messagePart)), expression + " -> " + errors);
    }

    @Test
    void validExpressionsAreSilent() {
        assertClean("price * qty", "name + 'x'", "name + 1", "startDate < createdAt", "startDate <= '2026-01-31'",
                "status in ['A', 'B']", "round(price, 2) + 1", "active ? 1 : 2", "discount ?? 0", "price ?? 0",
                "coalesce(name, 'n/a')", "daysBetween(startDate, createdAt) > 3", "addDays(startDate, 3) > startDate",
                "upper(name) == 'A'", "price > 0 && name != ''", "'a' in tags", "-price", "tags.filter(x => x > 1)",
                "unknownField * 2", "someUnknownFunction(price)", "name == 5", "price >= '10'");
    }

    @Test
    void arithmeticOnNonNumbersIsRefused() {
        assertRefused("startDate * 2", "operand `startDate` is date, not a number");
        assertRefused("name * 2", "operand `name` is text, not a number");
        assertRefused("active / 2", "operand `active` is boolean");
        assertRefused("-name", "operand `name` is text");
        assertRefused("price - tags", "operand `tags` is list");
    }

    @Test
    void dateArithmeticPointsToTheDateFunctions() {
        assertRefused("startDate + 3", "use addDays");
        assertRefused("createdAt - startDate", "use daysBetween");
        assertRefused("startDate + createdAt", "adds two dates");
        assertRefused("active + 1", "adds a boolean and a number");
    }

    @Test
    void orderingIsForNumbersAndDatesOnly() {
        assertRefused("name < 'b'", "text cannot be ordered");
        assertRefused("active < 1", "boolean cannot be ordered");
        assertRefused("tags > 1", "list cannot be ordered");
    }

    @Test
    void membershipNeedsAListOnTheRight() {
        assertRefused("price in name", "right side of `in` must be a list but `name` is text");
        assertRefused("price not in qty", "right side of `not in` must be a list");
    }

    @Test
    void ternaryAndCoalesceNeedMatchingTypes() {
        assertRefused("name ? 1 : 2", "condition before `?` must be a boolean");
        assertRefused("active ? 1 : 'x'", "both branches of ?: must have the same type (number and text)");
        assertRefused("price ?? 'x'", "both sides of ?? must have the same type (number and text)");
    }

    @Test
    void stdlibArgumentsAreChecked() {
        assertRefused("round(price)", "round() takes 2 argument(s) but is given 1");
        assertRefused("abs(price, 2)", "abs() takes 1 argument(s) but is given 2");
        assertRefused("round(active, 2)", "argument 1 `active` of round() must be a number but is boolean");
        assertRefused("addDays(5, 2)", "argument 1 `5` of addDays() must be a date but is number");
        assertRefused("floor(startDate)", "must be a number but is date");
    }

    @Test
    void theWholeExpressionTypeIsCheckedAgainstTheContext() {
        assertEquals(List.of(), ExpressionTypeChecker.check("price > 1", SCOPE, ExpressionTypeChecker.Expected.BOOLEAN, false));
        assertEquals(List.of(), ExpressionTypeChecker.check("unknown", SCOPE, ExpressionTypeChecker.Expected.BOOLEAN, false));
        assertTrue(ExpressionTypeChecker.check("price + 1", SCOPE, ExpressionTypeChecker.Expected.BOOLEAN, false)
                .get(0).contains("must evaluate to a boolean here but is number"));
        assertTrue(ExpressionTypeChecker.check("name", SCOPE, ExpressionTypeChecker.Expected.NUMBER, false)
                .get(0).contains("must evaluate to a number here but is text"));
    }

    @Test
    void pushableContextsAcceptOnlySqlFunctionsWithoutLambdas() {
        assertEquals(List.of(), ExpressionTypeChecker.check("upper(name) == 'A' && price > 1", SCOPE, ExpressionTypeChecker.Expected.BOOLEAN, true));
        assertTrue(ExpressionTypeChecker.check("name.matches('x')", SCOPE, ExpressionTypeChecker.Expected.BOOLEAN, true)
                .get(0).contains("matches() cannot be pushed down"));
        assertTrue(ExpressionTypeChecker.check("tags.filter(x => x > 1)", SCOPE, ExpressionTypeChecker.Expected.ANY, true)
                .stream().anyMatch(e -> e.contains("filter() takes a lambda, which cannot be pushed down")));
        assertTrue(ExpressionTypeChecker.check("mystery(price)", SCOPE, ExpressionTypeChecker.Expected.ANY, true)
                .get(0).contains("mystery() is not a function this context can push down"));
    }

    @Test
    void aSyntaxErrorIsLeftToTheParserDiagnostics() {
        assertEquals(List.of(), check("price *"));
    }

    @Test
    void lambdaParametersAreNotFieldReferences() {
        assertClean("tags.filter(t => t.score > 1)", "tags.map(t => t.name + 'x')");
    }

    @Test
    void dslFieldTypesMapToExpressionTypes() {
        assertEquals(ExprType.NUMBER, ExprType.fromDslType("Decimal"));
        assertEquals(ExprType.TEXT, ExprType.fromDslType("enum"));
        assertEquals(ExprType.DATE, ExprType.fromDslType("date"));
        assertEquals(ExprType.DATETIME, ExprType.fromDslType("datetime"));
        assertEquals(ExprType.ANY, ExprType.fromDslType("object"));
        assertEquals(ExprType.ANY, ExprType.fromDslType(null));
    }
}
