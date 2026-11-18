package com.npdev.kernel.concepts;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REG-341: an ordered comparison (&lt;, &lt;=, &gt;, &gt;=) with a MISSING value is false, as in SQL. The in-memory
 * engine orders a null last for sorting and reused that for filtering, so {@code listedOn >= '2026-11-18'} matched
 * every row with no listedOn -- the in-memory store and the JDBC store gave different counts for the same predicate.
 */
class ConceptQueryEngineMissingValueOrderingTest {

    private static ConceptRecord row(String id, Object listedOn) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (listedOn != null) {
            data.put("listedOn", listedOn);
        }
        return new ConceptRecord("Supplier", id, "t1", data);
    }

    private static List<String> ids(ConceptQuery.Operator operator, Object bound) {
        List<ConceptRecord> rows = List.of(row("none", null), row("past", "2020-01-01"), row("future", "2999-01-01"));
        ConceptQuery query = new ConceptQuery(List.of(new ConceptQuery.Filter("listedOn", operator, bound)),
                List.of(), 0, ConceptQuery.MAX_LIMIT);
        return ConceptQueryEngine.apply(rows, query).items().stream().map(ConceptRecord::id).sorted().toList();
    }

    @Test
    void aRowWithNoValueNeverSatisfiesAnOrderedComparison() {
        assertEquals(List.of("future"), ids(ConceptQuery.Operator.GTE, "2026-11-18"));
        assertEquals(List.of("future"), ids(ConceptQuery.Operator.GT, "2026-11-18"));
        assertEquals(List.of("past"), ids(ConceptQuery.Operator.LT, "2026-11-18"));
        assertEquals(List.of("past"), ids(ConceptQuery.Operator.LTE, "2026-11-18"));
    }

    @Test
    void aMissingBoundMatchesNothing() {
        assertEquals(List.of(), ids(ConceptQuery.Operator.GTE, null));
    }

    @Test
    void isNullAndEqualityAreUnchanged() {
        assertEquals(List.of("none"), ids(ConceptQuery.Operator.IS_NULL, null));
        assertEquals(List.of("past"), ids(ConceptQuery.Operator.EQ, "2020-01-01"));
    }
}
