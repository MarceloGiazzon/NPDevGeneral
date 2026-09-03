package com.finalexec.db;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOUNDARY_LIFT_PLAN_2026-09-02 package 3.1 (B2, REG-202), done-when #5: the shape rendered by
 * {@link ExpressionBackfillPreviewJson#encode} -- column, nullability change, expression, rows
 * affected, null results, ordered steps, risk tier, auto-apply eligibility.
 */
class ExpressionBackfillPreviewJsonTest {

    @Test
    void encodesASafeZeroFailureCandidateAsAutoApplyEligible() {
        ExpressionBackfillPreview.Item item = new ExpressionBackfillPreview.Item(
                "widgets", "auditQuantity", "$quantity", 2, List.of("10", "20"), List.of());

        List<Map<String, Object>> encoded = ExpressionBackfillPreviewJson.encode(List.of(item));

        assertEquals(1, encoded.size());
        Map<String, Object> row = encoded.get(0);
        assertEquals("widgets", row.get("table"));
        assertEquals("auditQuantity", row.get("column"));
        assertEquals("NULLABLE -> NOT NULL", row.get("nullabilityChange"));
        assertEquals("$quantity", row.get("expression"));
        assertEquals(2L, row.get("rowsAffected"));
        assertEquals(List.of("10", "20"), row.get("distinctValues"));
        assertEquals(List.of(), row.get("failedRowIds"));
        assertEquals("SAFE", row.get("riskTier"));
        assertEquals(true, row.get("autoApplyEligible"));

        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) row.get("steps");
        assertEquals(3, steps.size());
        assertTrue(steps.get(0).startsWith("ADD COLUMN auditQuantity"));
        assertTrue(steps.get(1).startsWith("UPDATE widgets SET auditQuantity"));
        assertTrue(steps.get(2).startsWith("ALTER COLUMN auditQuantity SET NOT NULL"));
    }

    @Test
    void encodesAFailingRowAsNotAutoApplyEligibleRegardlessOfTier() {
        // SAFE tier, but a failing row -- must never claim auto-apply eligibility.
        ExpressionBackfillPreview.Item item = new ExpressionBackfillPreview.Item(
                "widgets", "auditQuantity", "$quantity", 1, List.of(), List.of("row#1"));

        Map<String, Object> row = ExpressionBackfillPreviewJson.encode(List.of(item)).get(0);

        assertEquals("SAFE", row.get("riskTier"));
        assertFalse((boolean) row.get("autoApplyEligible"));
        assertEquals(List.of("row#1"), row.get("failedRowIds"));
    }

    @Test
    void encodesAFunctionCallExpressionAsReviewableAndNotEligible() {
        ExpressionBackfillPreview.Item item = new ExpressionBackfillPreview.Item(
                "widgets", "auditTag", "riskyLookup(quantity)", 1, List.of("x"), List.of());

        Map<String, Object> row = ExpressionBackfillPreviewJson.encode(List.of(item)).get(0);

        assertEquals("REVIEWABLE", row.get("riskTier"));
        assertFalse((boolean) row.get("autoApplyEligible"));
    }

    @Test
    void encodesAScopePrefixedCallAsHighRisk() {
        ExpressionBackfillPreview.Item item = new ExpressionBackfillPreview.Item(
                "widgets", "auditTag", "scope.exists(x => x.status == 'A')", 1, List.of("x"), List.of());

        Map<String, Object> row = ExpressionBackfillPreviewJson.encode(List.of(item)).get(0);

        assertEquals("HIGH_RISK", row.get("riskTier"));
        assertFalse((boolean) row.get("autoApplyEligible"));
    }

    @Test
    void encodesAnEmptyListAsAnEmptyList() {
        assertEquals(List.of(), ExpressionBackfillPreviewJson.encode(List.of()));
    }
}
