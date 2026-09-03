package com.finalexec.db;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 (REAL_LIFT_PLAN_2026-09-03, B2 "real lift"): unit tests for {@link
 * ExpressionBackfillShadowProof#compare}, the pure two-pass comparison, against FABRICATED row
 * values. {@link ValueExpressionEvaluator}'s only built-in special forms ({@code now()}/{@code
 * uuid()}) are deliberately deterministic constants, so no REAL expression in today's vocabulary can
 * exercise the disagreement branch through two genuine database evaluations -- this class proves the
 * comparison logic itself is correct, independent of whether a live non-deterministic function
 * exists yet. {@link ExpressionBackfillShadowProofIntegrationTest} covers the real end-to-end path.
 */
class ExpressionBackfillShadowProofTest {

    @Test
    void everyRowPopulatedAndAgreeingIsSafeAndCarriesTheProvenValues() {
        List<ExpressionBackfillPreview.RowValue> first = List.of(
                new ExpressionBackfillPreview.RowValue(1L, "alpha"),
                new ExpressionBackfillPreview.RowValue(2L, "beta"));
        List<ExpressionBackfillPreview.RowValue> second = List.of(
                new ExpressionBackfillPreview.RowValue(1L, "alpha"),
                new ExpressionBackfillPreview.RowValue(2L, "beta"));

        ExpressionBackfillShadowProof.ShadowProofResult result = ExpressionBackfillShadowProof.compare(first, second);

        assertTrue(result.safe(), result.toString());
        assertEquals(Map.of(1L, "alpha", 2L, "beta"), result.provenValues());
        assertTrue(result.unpopulatedRowIds().isEmpty());
        assertTrue(result.nondeterministicRowIds().isEmpty());
    }

    @Test
    void aRowWithNoValueOnTheFirstPassIsUnpopulatedNotSafe() {
        List<ExpressionBackfillPreview.RowValue> first = List.of(
                new ExpressionBackfillPreview.RowValue(1L, "alpha"),
                new ExpressionBackfillPreview.RowValue(2L, null));
        List<ExpressionBackfillPreview.RowValue> second = List.of(
                new ExpressionBackfillPreview.RowValue(1L, "alpha"),
                new ExpressionBackfillPreview.RowValue(2L, "beta"));

        ExpressionBackfillShadowProof.ShadowProofResult result = ExpressionBackfillShadowProof.compare(first, second);

        assertFalse(result.safe());
        assertEquals(List.of("2"), result.unpopulatedRowIds());
        assertTrue(result.nondeterministicRowIds().isEmpty(),
                "an unpopulated row is reported as unpopulated, not also as nondeterministic");
        assertTrue(result.provenValues().isEmpty());
    }

    @Test
    void aRowWhoseTwoEvaluationsDisagreeIsNondeterministicNotSafe() {
        List<ExpressionBackfillPreview.RowValue> first = List.of(
                new ExpressionBackfillPreview.RowValue(1L, "alpha"),
                new ExpressionBackfillPreview.RowValue(2L, "first-value"));
        List<ExpressionBackfillPreview.RowValue> second = List.of(
                new ExpressionBackfillPreview.RowValue(1L, "alpha"),
                new ExpressionBackfillPreview.RowValue(2L, "second-value"));

        ExpressionBackfillShadowProof.ShadowProofResult result = ExpressionBackfillShadowProof.compare(first, second);

        assertFalse(result.safe());
        assertEquals(List.of("2"), result.nondeterministicRowIds());
        assertTrue(result.unpopulatedRowIds().isEmpty());
        assertTrue(result.provenValues().isEmpty(), "no partial proof -- either every row is proven or none are");
    }

    @Test
    void aRowMissingEntirelyFromTheSecondPassIsTreatedAsNondeterministic() {
        // The row SET changing between passes (rather than just a value) is exactly as suspicious as a
        // disagreeing value -- nothing should write to this table mid-migration, so either shape means
        // something is wrong, not that this row is safe by omission.
        List<ExpressionBackfillPreview.RowValue> first = List.of(
                new ExpressionBackfillPreview.RowValue(1L, "alpha"),
                new ExpressionBackfillPreview.RowValue(2L, "beta"));
        List<ExpressionBackfillPreview.RowValue> second = List.of(
                new ExpressionBackfillPreview.RowValue(1L, "alpha"),
                new ExpressionBackfillPreview.RowValue(3L, "gamma"));

        ExpressionBackfillShadowProof.ShadowProofResult result = ExpressionBackfillShadowProof.compare(first, second);

        assertFalse(result.safe());
        assertEquals(List.of("3"), result.nondeterministicRowIds());
    }

    @Test
    void noRowsAtAllIsTriviallySafe() {
        ExpressionBackfillShadowProof.ShadowProofResult result =
                ExpressionBackfillShadowProof.compare(List.of(), List.of());

        assertTrue(result.safe());
        assertTrue(result.provenValues().isEmpty());
    }
}
