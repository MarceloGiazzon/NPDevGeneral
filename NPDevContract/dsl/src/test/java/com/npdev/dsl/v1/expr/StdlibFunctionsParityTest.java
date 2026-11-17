package com.npdev.dsl.v1.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * FORM P3-T2: replays functions-parity.json on the server's {@link StdlibFunctions} (the browser side is
 * scripts/quality/check-client-expr-parity.py) and pins the registry to the JSON: every stdlib function
 * has a case, the validator's name set is derived from the JSON, and every JSON name is implemented.
 */
class StdlibFunctionsParityTest {

    private static JsonNode fixture() throws Exception {
        try (InputStream in = StdlibFunctionsParityTest.class.getResourceAsStream("/functions-parity.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    private static Object plain(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), plain(e.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(n -> list.add(plain(n)));
            return list;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        return node.isBoolean() ? (Object) node.asBoolean() : node.asText();
    }

    private static void assertSame(Object expected, Object actual, String expr) {
        if (expected == null) {
            assertEquals(null, actual, expr);
        } else if (expected instanceof BigDecimal want) {
            assertTrue(actual instanceof Number, expr + " expected a number but got " + actual);
            assertEquals(0, want.compareTo(ComputedExpression.dec(actual)), expr + " got " + actual);
        } else if (expected instanceof List<?> want) {
            assertTrue(actual instanceof List<?>, expr + " expected a list but got " + actual);
            List<?> got = (List<?>) actual;
            assertEquals(want.size(), got.size(), expr);
            for (int i = 0; i < want.size(); i++) {
                assertSame(want.get(i), got.get(i), expr + "[" + i + "]");
            }
        } else {
            assertEquals(expected, actual, expr);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCaseAgreesWithTheServerImplementation() throws Exception {
        ComputedExpression.FunctionRegistry registry = StdlibFunctions.registry();
        int count = 0;
        for (JsonNode c : fixture().get("cases")) {
            String expr = c.get("expr").asText();
            Map<String, Object> vars = (Map<String, Object>) plain(c.get("vars"));
            assertSame(plain(c.get("expect")), ComputedExpression.evaluate(expr, vars, registry), expr);
            count++;
        }
        assertTrue(count > 50, "the fixture should be substantial, was " + count);
    }

    @Test
    void everyStdlibFunctionHasAFixtureCase() throws Exception {
        StringBuilder all = new StringBuilder();
        for (JsonNode c : fixture().get("cases")) {
            all.append(c.get("expr").asText()).append('\n');
        }
        List<String> uncovered = new ArrayList<>();
        for (StdlibRegistry.Entry entry : StdlibRegistry.entries()) {
            if (entry.implementedByStdlib() && !all.toString().contains(entry.name() + "(")
                    && !all.toString().contains("." + entry.name() + "(")) {
                uncovered.add(entry.name());
            }
        }
        assertEquals(List.of(), uncovered, "functions-stdlib.json entries without a functions-parity.json case");
    }

    @Test
    void theValidatorNameSetIsDerivedFromTheJson() {
        Set<String> expected = new HashSet<>();
        for (StdlibRegistry.Entry entry : StdlibRegistry.entries()) {
            if (entry.implementedByStdlib() && "value".equals(entry.kind())) {
                expected.addAll(entry.allNames());
            }
        }
        assertEquals(expected, ValueBehaviorFunctions.NAMES);
        assertTrue(ValueBehaviorFunctions.NAMES.containsAll(Set.of("concat", "uppercase", "lowercase", "today", "addDays",
                "daysBetween", "now", "uuid", "round")));
    }

    @Test
    void everyImplementedNameResolvesToAFunction() {
        for (StdlibRegistry.Entry entry : StdlibRegistry.entries()) {
            if (entry.implementedByStdlib()) {
                for (String name : entry.allNames()) {
                    assertTrue(StdlibFunctions.registry().lookup(name) != null, name + " is marked impl=stdlib but has no implementation");
                }
            }
        }
    }

    @Test
    void volatileFunctionsArePinnedByTheDeterministicWrapper() {
        ComputedExpression.FunctionRegistry registry = StdlibFunctions.registry();
        Object first = StdlibFunctions.withDeterministicVolatiles(() -> ComputedExpression.evaluate("[now(), uuid(), today()]", Map.of(), registry));
        Object second = StdlibFunctions.withDeterministicVolatiles(() -> ComputedExpression.evaluate("[now(), uuid(), today()]", Map.of(), registry));
        assertEquals(first, second);
        assertEquals(List.of("1970-01-01T00:00:00Z", StdlibFunctions.DETERMINISTIC_UUID, "1970-01-01"), first);
    }
}
