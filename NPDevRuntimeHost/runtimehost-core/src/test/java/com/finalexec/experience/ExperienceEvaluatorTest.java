package com.finalexec.experience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * FORM P5-T4: the decisions behind {@code POST /api/x/eval} -- the allowlist (only text written in the caller's own
 * experience), the bounds, the null-on-any-error rule and how store calls are lifted out of an expression. The store is
 * a recording fake; the permission-scoped gateway read path is {@link GatewayConceptReads}'s.
 */
class ExperienceEvaluatorTest {

    private static final String RESOLVED = """
            {
              "experiences": [
                { "name": "warehouse", "for": ["WarehouseManager"], "home": "orders",
                  "navigation": [ { "screen": "orders", "badge": "count('WidgetOrder', status == 'NEW')" },
                                  { "screen": "today" } ] },
                { "name": "other", "for": ["Other"], "home": "elsewhere",
                  "navigation": [ { "screen": "elsewhere", "badge": "count('Secret')" } ] }
              ],
              "screens": [
                { "name": "orders", "kind": "list", "concept": "WidgetOrder",
                  "visibleWhen": "count('WidgetOrder') > 0",
                  "countdown": { "until": "addSeconds(now(), 20)" },
                  "actions": [ { "label": "Ship", "flow": "Ship", "visibleWhen": "exists('WidgetOrder', status == 'PAID')" } ] },
                { "name": "today", "kind": "dashboard",
                  "widgets": [
                    { "kind": "tile", "value": "sum('WidgetOrder', qty)" },
                    { "kind": "chart", "value": { "New": "count('WidgetOrder', status == 'NEW')", "Paid": "count('WidgetOrder', status == 'PAID')" } },
                    { "kind": "chart", "value": [ { "label": "All", "value": "count('WidgetOrder')" } ] } ] },
                { "name": "elsewhere", "kind": "list", "concept": "Secret" }
              ]
            }
            """;

    private static final ExperienceEvaluator.Caller CALLER =
            new ExperienceEvaluator.Caller("alice", "default", Set.of("WAREHOUSEMANAGER"), false);

    /** Records every call; answers from the maps, or throws when told to. */
    private static class FakeReads implements ExperienceEvaluator.ConceptReads {
        final List<String> calls = new ArrayList<>();
        long count = 7;
        BigDecimal aggregate = new BigDecimal("12.50");
        boolean fail;

        @Override
        public long count(String concept, String where) {
            calls.add("count|" + concept + "|" + where);
            if (fail) {
                throw new IllegalStateException("denied");
            }
            return count;
        }

        @Override
        public BigDecimal aggregate(String concept, String function, String field, String where) {
            calls.add(function + "|" + concept + "|" + field + "|" + where);
            if (fail) {
                throw new IllegalStateException("denied");
            }
            return aggregate;
        }
    }

    private static Set<String> allowedForWarehouse() {
        return ExperienceEvaluator.allowedExpressions(
                ExperienceCatalog.fromJson(RESOLVED).me(Set.of("WarehouseManager"), "alice"));
    }

    private static Map<String, String> ask(String... keyAndText) {
        Map<String, String> asked = new LinkedHashMap<>();
        for (int i = 0; i < keyAndText.length; i += 2) {
            asked.put(keyAndText[i], keyAndText[i + 1]);
        }
        return asked;
    }

    private static Object one(String text, FakeReads reads) {
        return new ExperienceEvaluator().evaluate(ask("k", text), Set.of(text), CALLER, reads).get("k");
    }

    // ------------------------------------------------------------------ the allowlist

    @Test
    void theAllowlistIsEveryExpressionTheCallersOwnExperienceAndItsScreensDeclare() {
        Set<String> allowed = allowedForWarehouse();
        assertEquals(Set.of(
                "count('WidgetOrder', status == 'NEW')",
                "count('WidgetOrder') > 0",
                "addSeconds(now(), 20)",
                "exists('WidgetOrder', status == 'PAID')",
                "sum('WidgetOrder', qty)",
                "count('WidgetOrder', status == 'PAID')",
                "count('WidgetOrder')"), allowed);
        assertTrue(!allowed.contains("count('Secret')"), "another experience's expressions are never allowed");
    }

    @Test
    void aCallerWithoutAnExperienceAllowsNothing() {
        assertEquals(Set.of(), ExperienceEvaluator.allowedExpressions(
                ExperienceCatalog.fromJson(RESOLVED).me(Set.of("ADMIN"), "root")));
        assertEquals(Set.of(), ExperienceEvaluator.allowedExpressions(null));
    }

    @Test
    void anAllowedTextIsEvaluatedAndAnyOtherTextAnswersNullWithoutTouchingTheStore() {
        FakeReads reads = new FakeReads();
        Map<String, Object> values = new ExperienceEvaluator().evaluate(
                ask("badge", "count('WidgetOrder', status == 'NEW')",
                        "sneaky", "count('Secret')",
                        "arith", "1 + 1",
                        "nearMiss", "count('WidgetOrder', status == 'NEW') "),
                allowedForWarehouse(), CALLER, reads);
        assertEquals(7L, values.get("badge"));
        assertNull(values.get("sneaky"));
        assertNull(values.get("arith"), "only verbatim declared text is evaluated, even a harmless one");
        assertNull(values.get("nearMiss"), "verbatim means exactly: a trailing space is a different text");
        assertEquals(List.of("count|WidgetOrder|status == 'NEW'"), reads.calls);
        assertEquals(List.of("badge", "sneaky", "arith", "nearMiss"), List.copyOf(values.keySet()));
    }

    // ------------------------------------------------------------------ what an expression may do

    @Test
    void storeCallsAreLiftedAndTheRestIsEvaluatedByTheExpressionEngine() {
        FakeReads reads = new FakeReads();
        assertEquals(7L, one("count('WidgetOrder')", reads));
        assertEquals(true, one("count('WidgetOrder') > 0", reads));
        assertEquals(14L, one("count('A') + count('B', qty > 1)", reads));
        assertEquals(true, one("exists('WidgetOrder', status == 'PAID')", reads));
        assertEquals(12.5, one("sum('WidgetOrder', qty)", reads));
        assertEquals(12.5, one("avg('WidgetOrder', 'qty', status == 'NEW')", reads));
        assertEquals(24L, one("count('A') * 3 + 3", reads));
        assertTrue(reads.calls.contains("avg|WidgetOrder|qty|status == 'NEW'"), reads.calls.toString());
    }

    @Test
    void theCallerIsBoundToUserInTheRestOfTheExpressionAndInThePredicateText() {
        FakeReads reads = new FakeReads();
        assertEquals("alice", one("$user.id", reads));
        assertEquals(7L, one("count('Ride', riderAccount == $user.id && status in ['A','B'])", reads));
        assertEquals("count|Ride|riderAccount == $user.id && status in ['A','B']", reads.calls.get(0),
                "the predicate reaches the store layer verbatim (its $user is resolved against the caller there)");
    }

    @Test
    void anEmptySumIsZeroAndAnEmptyAverageIsNull() {
        FakeReads reads = new FakeReads();
        reads.aggregate = null;
        assertEquals(0L, one("sum('WidgetOrder', qty)", reads));
        assertNull(one("avg('WidgetOrder', qty)", reads));
    }

    @Test
    void anIdenticalStoreCallIsMadeOnceForTheWholeRequest() {
        FakeReads reads = new FakeReads();
        String badge = "count('WidgetOrder', status == 'NEW')";
        Map<String, Object> values = new ExperienceEvaluator().evaluate(
                ask("a", badge, "b", badge), Set.of(badge), CALLER, reads);
        assertEquals(7L, values.get("a"));
        assertEquals(7L, values.get("b"));
        assertEquals(1, reads.calls.size());
    }

    @Test
    void textInsideAStringLiteralIsNeverTreatedAsACall() {
        FakeReads reads = new FakeReads();
        assertEquals("count(x)", one("'count(x)'", reads));
        assertEquals(List.of(), reads.calls);
    }

    // ------------------------------------------------------------------ failures are null, never an error

    @Test
    void anyFailureAnswersNull() {
        FakeReads failing = new FakeReads();
        failing.fail = true;
        assertNull(one("count('WidgetOrder')", failing));
        assertNull(one("sum('WidgetOrder', qty)", failing));

        FakeReads reads = new FakeReads();
        for (String bad : List.of(
                "count(", "count('A'", "count(A)", "count('A b')", "count('A', )", "count()",
                "count('A', count('B'))", "exists('A', x => x.y)", "exists('A')", "sum('A')", "sum('A', 1x)",
                "sum('A', 'q w')", "count('A', 'b', 'c')", "nope('A')", "1 +", "count('A') +")) {
            assertNull(one(bad, reads), bad);
        }
        assertEquals(List.of(), reads.calls, "a malformed call never reaches the store");
    }

    @Test
    void moreThanTheStoreCallCapInOneExpressionAnswersNull() {
        StringBuilder text = new StringBuilder("count('A')");
        for (int i = 0; i < ExperienceEvaluator.MAX_STORE_CALLS; i++) {
            text.append(" + count('A', n == ").append(i).append(")");
        }
        assertNull(one(text.toString(), new FakeReads()));
    }

    @Test
    void anExpressionOverTheLengthCapAnswersNullEvenIfDeclared() {
        String longText = "count('A', note == '" + "x".repeat(ExperienceEvaluator.MAX_EXPRESSION_CHARS) + "')";
        FakeReads reads = new FakeReads();
        assertNull(new ExperienceEvaluator().evaluate(ask("k", longText), Set.of(longText), CALLER, reads).get("k"));
        assertEquals(List.of(), reads.calls);
        String atCap = "count('A') + 0" + " ".repeat(ExperienceEvaluator.MAX_EXPRESSION_CHARS - "count('A') + 0".length());
        assertEquals(ExperienceEvaluator.MAX_EXPRESSION_CHARS, atCap.length());
        assertEquals(7L, new ExperienceEvaluator().evaluate(ask("k", atCap), Set.of(atCap), CALLER, new FakeReads()).get("k"));
    }

    @Test
    void aNonTextEntryAnswersNullAndStaysInTheAnswer() {
        Map<String, String> asked = new LinkedHashMap<>();
        asked.put("k", null);
        Map<String, Object> values = new ExperienceEvaluator().evaluate(asked, Set.of("x"), CALLER, new FakeReads());
        assertTrue(values.containsKey("k"));
        assertNull(values.get("k"));
    }

    @Test
    void thePerRequestTimeBudgetAnswersNullForWhatIsLeftOnceSpent() {
        AtomicLong now = new AtomicLong(1_000);
        ExperienceEvaluator evaluator = new ExperienceEvaluator(now::get, 100);
        String first = "count('A')";
        String second = "count('B')";
        FakeReads reads = new FakeReads() {
            @Override
            public long count(String concept, String where) {
                now.addAndGet(500); // the first store call is slow
                return super.count(concept, where);
            }
        };
        Map<String, Object> values = evaluator.evaluate(ask("a", first, "b", second), Set.of(first, second), CALLER, reads);
        assertEquals(7L, values.get("a"));
        assertNull(values.get("b"), "the budget was spent by the first");
        assertEquals(1, reads.calls.size());
    }

    // ------------------------------------------------------------------ lifting

    @Test
    void liftReplacesEachStoreCallWithAVariableAndKeepsTheRawArguments() {
        ExperienceEvaluator.Lifted lifted = ExperienceEvaluator.lift(
                "count('Ride', status == 'A,B' && n in [1, 2]) > 0 ? sum( 'Ride' , fare ) : round(2.5, 0)");
        assertEquals("__q0 > 0 ? __q1 : round(2.5, 0)", lifted.expression());
        assertEquals(new ExperienceEvaluator.StoreCall("count", "Ride", null, "status == 'A,B' && n in [1, 2]"), lifted.calls().get(0));
        assertEquals(new ExperienceEvaluator.StoreCall("sum", "Ride", "fare", null), lifted.calls().get(1));
    }

    @Test
    void aMemberOrNamespacedNameThatEndsInAStoreFunctionIsNotACall() {
        ExperienceEvaluator.Lifted lifted = ExperienceEvaluator.lift("$row.count + pack::count(1) + discount(2)");
        assertEquals("$row.count + pack::count(1) + discount(2)", lifted.expression());
        assertEquals(List.of(), lifted.calls());
        assertThrows(IllegalArgumentException.class, () -> ExperienceEvaluator.lift("count('A'"));
    }

    @Test
    void normalizeProducesJsonScalarsOnly() {
        assertEquals(3L, ExperienceEvaluator.normalize(new BigDecimal("3.0")));
        assertEquals(2.5, ExperienceEvaluator.normalize(new BigDecimal("2.50")));
        assertEquals(4L, ExperienceEvaluator.normalize(4.0d));
        assertNull(ExperienceEvaluator.normalize(Double.NaN));
        assertEquals("x", ExperienceEvaluator.normalize("x"));
        assertEquals(true, ExperienceEvaluator.normalize(true));
        assertNull(ExperienceEvaluator.normalize(List.of(1)));
        assertNull(ExperienceEvaluator.normalize(null));
    }
}
