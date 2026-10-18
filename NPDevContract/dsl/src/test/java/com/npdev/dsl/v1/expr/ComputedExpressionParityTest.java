package com.npdev.dsl.v1.expr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F6-5: replays {@code expr-parity.json}, the fixture the browser engine ({@code npdev-expr.js}) is also
 * checked against by {@code scripts/quality/check-client-expr-parity.py}. The server engine is the ground
 * truth: if this test and the browser check both pass, the two engines agree on every fixture case.
 */
class ComputedExpressionParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fixture() throws Exception {
        try (InputStream in = ComputedExpressionParityTest.class.getResourceAsStream("/expr-parity.json")) {
            return MAPPER.readTree(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> vars(JsonNode node) {
        return MAPPER.convertValue(node, Map.class);
    }

    @Test
    void everyEvalCaseMatchesTheFixture() throws Exception {
        for (JsonNode c : fixture().get("eval")) {
            String expr = c.get("expr").asText();
            Object actual = ComputedExpression.evaluate(expr, vars(c.get("vars")));
            JsonNode expect = c.get("expect");
            if (expect.isNull()) {
                assertNull(actual, expr);
            } else if (expect.isNumber()) {
                assertTrue(actual instanceof Number, expr + " expected a number but got " + actual);
                assertEquals(expect.asDouble(), ((Number) actual).doubleValue(), 1e-9, expr);
            } else if (expect.isBoolean()) {
                assertEquals(expect.asBoolean(), actual, expr);
            } else {
                assertEquals(expect.asText(), actual, expr);
            }
        }
    }

    @Test
    void everyParseErrorCaseIsRejected() throws Exception {
        for (JsonNode expr : fixture().get("parseError")) {
            assertThrows(ComputedExpression.ExpressionException.class,
                    () -> ComputedExpression.parse(expr.asText()), "should not parse: '" + expr.asText() + "'");
        }
    }

    @Test
    void everyEvalErrorCaseParsesButFailsToEvaluate() throws Exception {
        for (JsonNode c : fixture().get("evalError")) {
            String expr = c.get("expr").asText();
            ComputedExpression.parse(expr);
            assertThrows(ComputedExpression.ExpressionException.class,
                    () -> ComputedExpression.evaluate(expr, vars(c.get("vars"))), expr);
        }
    }

    @Test
    void referencedFieldsMatchTheFixtureInOrder() throws Exception {
        for (JsonNode c : fixture().get("refs")) {
            List<String> expected = new ArrayList<>();
            c.get("fields").forEach(f -> expected.add(f.asText()));
            Set<String> actual = ComputedExpression.referencedFields(c.get("expr").asText());
            assertEquals(expected, new ArrayList<>(actual), c.get("expr").asText());
        }
    }

    @Test
    void booleanShapeMatchesTheFixture() throws Exception {
        for (JsonNode c : fixture().get("boolean")) {
            assertEquals(c.get("shaped").asBoolean(), ComputedExpression.isBooleanShaped(c.get("expr").asText()),
                    c.get("expr").asText());
        }
    }
}
