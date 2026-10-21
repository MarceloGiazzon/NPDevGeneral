package com.npdev.dsl.v1.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** REG-269: a date field compared with another date field in an invariant was always true. */
class ComputedExpressionDateComparisonTest {

    private static boolean eval(String expression, Object start, Object end) {
        Map<String, Object> row = new HashMap<>();
        row.put("startDate", start);
        row.put("endDate", end);
        return ComputedExpression.evaluateBoolean(expression, row);
    }

    @Test
    void isoDateStringsOrderChronologically() {
        assertTrue(eval("endDate >= startDate", "2026-03-10", "2026-03-10"));
        assertTrue(eval("endDate >= startDate", "2026-03-10", "2026-03-12"));
        assertFalse(eval("endDate >= startDate", "2026-03-10", "2026-03-09"));
        assertTrue(eval("endDate > startDate", "2026-03-10", "2026-03-11"));
        assertFalse(eval("endDate > startDate", "2026-03-10", "2026-03-10"));
        assertTrue(eval("startDate < endDate", "2026-03-10", "2026-03-11"));
        assertFalse(eval("startDate <= endDate", "2026-03-11", "2026-03-10"));
    }

    @Test
    void orderingIsChronologicalNotLexicalAcrossMonthAndYearBoundaries() {
        assertFalse(eval("endDate >= startDate", "2026-12-31", "2026-02-01"));
        assertTrue(eval("endDate >= startDate", "2026-12-31", "2027-01-01"));
    }

    @Test
    void javaTimeValuesAreOrderedLikeTheirStringForms() {
        assertFalse(eval("endDate >= startDate", LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 9)));
        assertTrue(eval("endDate >= startDate", LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 10)));
        assertFalse(eval("endDate > startDate",
                LocalDateTime.of(2026, 3, 10, 9, 0), LocalDateTime.of(2026, 3, 10, 8, 0)));
        assertTrue(eval("endDate > startDate",
                OffsetDateTime.parse("2026-03-10T08:00:00Z"), OffsetDateTime.parse("2026-03-10T09:00:00Z")));
    }

    @Test
    void dateAgainstIsoLiteralAndDateTimeStrings() {
        Map<String, Object> row = Map.of("d", "2026-05-01");
        assertTrue(ComputedExpression.evaluateBoolean("d >= '2026-01-01'", row));
        assertFalse(ComputedExpression.evaluateBoolean("d >= '2026-06-01'", row));
        assertTrue(eval("endDate > startDate", "2026-03-10T08:00:00Z", "2026-03-10T09:00:00Z"));
    }

    @Test
    void aTemporalOperandAgainstNothingOrNonTemporalIsNeverSilentlyTrue() {
        assertFalse(eval("endDate >= startDate", null, "2026-03-10"));
        assertFalse(eval("endDate >= startDate", "2026-03-10", null));
        assertFalse(eval("endDate >= startDate", "2026-03-10", "tomorrow"));
        Map<String, Object> row = Map.of("d", "2026-05-01");
        assertFalse(ComputedExpression.evaluateBoolean("d >= 0", row));
    }

    @Test
    void optionalEndDateGuardStillWorks() {
        assertTrue(eval("endDate == null || endDate >= startDate", "2026-03-10", null));
        assertFalse(eval("endDate == null || endDate >= startDate", "2026-03-10", "2026-03-01"));
    }

    @Test
    void numericComparisonsAreUnchanged() {
        Map<String, Object> row = new HashMap<>();
        row.put("a", 5);
        row.put("b", "12.30");
        row.put("c", "2");
        row.put("n", null);
        assertTrue(ComputedExpression.evaluateBoolean("a > 3", row));
        assertTrue(ComputedExpression.evaluateBoolean("b > c", row));
        assertTrue(ComputedExpression.evaluateBoolean("n >= 0", row)); // legacy: null reads as 0
        assertFalse(ComputedExpression.evaluateBoolean("a < c", row));
    }

    @Test
    void orderedComparisonsAreReportedWithTheirOperandKinds() {
        List<ComputedExpression.OrderedComparison> found =
                ComputedExpression.orderedComparisons("endDate >= startDate && days > 0 && !(x < '2026-01-01')");
        assertEquals(3, found.size());
        assertEquals("field", found.get(0).left().kind());
        assertEquals("startDate", found.get(0).right().field());
        assertEquals("literal", found.get(1).right().kind());
        assertEquals("<", found.get(2).op());
    }
}
