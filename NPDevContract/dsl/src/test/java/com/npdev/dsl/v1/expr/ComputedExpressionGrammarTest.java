package com.npdev.dsl.v1.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FORM P3-T2: list literals, in / not in, ternary and ??. One table per feature. */
class ComputedExpressionGrammarTest {

    private static Map<String, Object> vars() {
        Map<String, Object> v = new HashMap<>();
        v.put("status", "ASSIGNED");
        v.put("distanceKm", 7);
        v.put("discount", null);
        v.put("tags", List.of("a", "b"));
        v.put("qty", 3);
        return v;
    }

    private static Object eval(String expression) {
        return ComputedExpression.evaluate(expression, vars());
    }

    @Test
    void listLiteral() {
        assertEquals(List.of("A", "B"), eval("['A', 'B']"));
        assertEquals(List.of(), eval("[]"));
        assertEquals(List.of("A", 3), eval("['A', qty]"));
    }

    @Test
    void membership() {
        assertEquals(true, eval("status in ['REQUESTED', 'ASSIGNED']"));
        assertEquals(false, eval("status in ['REQUESTED']"));
        assertEquals(true, eval("status not in ['REQUESTED']"));
        assertEquals(false, eval("status not in ['ASSIGNED']"));
        assertEquals(true, eval("qty in [1, 2, 3]"));
        assertEquals(true, eval("qty in [1, 3 * 1]"));
        assertEquals(true, eval("'a' in tags"));
        assertEquals(false, eval("'z' in tags"));
        assertEquals(false, eval("status in discount"));
    }

    @Test
    void membershipComposesWithLogicalOperators() {
        assertEquals(true, eval("status in ['ASSIGNED'] && distanceKm > 5"));
        assertEquals(false, eval("status in ['ASSIGNED'] && distanceKm > 9"));
        assertEquals(true, eval("!(status in ['REQUESTED'])"));
    }

    @Test
    void fieldNamedInStillWorks() {
        assertEquals(5, ((Number) ComputedExpression.evaluate("in + 1", Map.of("in", 4))).intValue());
    }

    @Test
    void ternary() {
        assertEquals("far", eval("distanceKm > 5 ? 'far' : 'near'"));
        assertEquals("near", eval("distanceKm > 9 ? 'far' : 'near'"));
        assertEquals("b", eval("distanceKm > 9 ? 'a' : distanceKm > 6 ? 'b' : 'c'"));
        assertEquals("a", eval("distanceKm > 1 ? (distanceKm > 2 ? 'a' : 'x') : 'c'"));
    }

    @Test
    void ternaryEvaluatesOnlyTheChosenBranch() {
        // the unchosen branch would throw if evaluated
        ComputedExpression.FunctionRegistry boom = ComputedExpression.FunctionRegistry.of(Map.of(
                "boom", (args, vars) -> {
                    throw new ComputedExpression.ExpressionException("evaluated");
                }));
        assertEquals("ok", ComputedExpression.evaluate("true ? 'ok' : boom()", vars(), boom));
        assertEquals("ok", ComputedExpression.evaluate("false ? boom() : 'ok'", vars(), boom));
    }

    @Test
    void coalesce() {
        assertEquals(0, ((Number) eval("discount ?? 0")).intValue());
        assertEquals(3, ((Number) eval("qty ?? 0")).intValue());
        assertEquals(9, ((Number) eval("discount ?? discount ?? 9")).intValue());
        // ?? binds looser than ||, tighter than ?:
        assertEquals("x", eval("discount ?? false || true ? 'x' : 'y'"));
        // an empty string is NOT null
        assertEquals("", ComputedExpression.evaluate("v ?? 'd'", Map.of("v", "")));
    }

    @Test
    void newSyntaxIsReportedByTheCollectors() {
        java.util.Set<String> fields = new java.util.TreeSet<>();
        ComputedExpression.parse("status in ['A'] ? qty : discount ?? other").collectFields(fields);
        assertEquals(java.util.Set.of("status", "qty", "discount", "other"), fields);
    }

    @Test
    void booleanLooksForNewForms() {
        assertEquals(true, ComputedExpression.parse("status in ['A']").looksBoolean());
        assertEquals(true, ComputedExpression.parse("status not in ['A']").looksBoolean());
        assertEquals(true, ComputedExpression.parse("qty > 1 ? true : false").looksBoolean());
        assertEquals(false, ComputedExpression.parse("qty > 1 ? 'a' : 'b'").looksBoolean());
    }

    @Test
    void syntaxErrors() {
        for (String bad : new String[] {"[1, 2", "[1 2]", "a ? b", "a ? b :", "? a : b", "status in", "status not 'x'",
                "a ?? ", "[,]"}) {
            assertThrows(ComputedExpression.ExpressionException.class, () -> ComputedExpression.parse(bad), bad);
        }
    }

    @Test
    void portableTreeCarriesTheNewKinds() {
        assertEquals("list", ComputedExpression.toPortableTree("['A']").get("k"));
        assertEquals("tern", ComputedExpression.toPortableTree("a ? b : c").get("k"));
        assertEquals("in", ComputedExpression.toPortableTree("a in ['A']").get("op"));
    }
}
