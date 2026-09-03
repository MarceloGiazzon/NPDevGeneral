package com.finalexec.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A2 (REAL_LIFT_PLAN_2026-09-03, B2 "real lift"): proves a REVIEWABLE expression-default backfill
 * safe BY RUNNING IT TWICE against the live rows, rather than by reasoning about its text. {@link
 * com.npdev.kernel.concepts.ExpressionBackfillRiskClassifier} classifies an expression REVIEWABLE the
 * moment it contains any function call at all -- a necessary conservatism when the only evidence is
 * the expression's TEXT, but the platform is not actually limited to text: it can evaluate the
 * expression against every row that needs it and look at what happened.
 *
 * <h2>What "safe" means here</h2>
 * <ol>
 *   <li>Every affected row produces a non-null value on the first evaluation (the SAME "did this
 *       fail for any row" check the ack-required path already makes via {@link
 *       ExpressionBackfillPreview.Item#hasFailures()}).</li>
 *   <li>A SECOND, independent evaluation -- against the SAME live rows, moments later, in the SAME
 *       migration boot where nothing else is writing to this table -- produces the IDENTICAL value
 *       for every row. Two evaluations of the same expression against the same underlying data
 *       disagreeing is only possible if the expression itself is non-deterministic (reads wall-clock
 *       time, randomness, or anything else that varies call-to-call) -- exactly the case an
 *       acknowledgment must still gate, and exactly the case a human reading the expression's TEXT
 *       cannot reliably catch (see {@link com.npdev.kernel.concepts.ExpressionBackfillRiskClassifier}'s
 *       own javadoc: {@code now()}/{@code uuid()} are classified SAFE today because THIS evaluator's
 *       own forms for them are deterministic constants, not real time -- proven, not assumed, by this
 *       very check).</li>
 * </ol>
 *
 * <p>No database column is added by this class -- it evaluates in memory only, against rows read
 * fresh from the live table. Nothing is written until the caller ({@link BackfillPass}) is satisfied
 * the proof succeeded, and the caller then writes the ALREADY-PROVEN values directly rather than
 * evaluating a third time.
 */
final class ExpressionBackfillShadowProof {

    private ExpressionBackfillShadowProof() {
    }

    /**
     * @param safe {@code true} only when every row produced a value on both passes AND every row's
     *             two values agreed
     * @param provenValues present only when {@code safe} -- the row id -> proven value map, ready to
     *             write directly (no third evaluation needed)
     * @param unpopulatedRowIds rows where the FIRST evaluation produced no value at all (a referenced
     *             field absent from that row) -- checked before determinism, since there is nothing to
     *             compare for a row that never produced a value in the first place
     * @param nondeterministicRowIds rows where both evaluations produced a value, but a DIFFERENT one
     *             each time
     */
    record ShadowProofResult(boolean safe, Map<Object, Object> provenValues, List<String> unpopulatedRowIds,
            List<String> nondeterministicRowIds) {
    }

    static ShadowProofResult prove(Connection connection, String table, String column, String expression)
            throws SQLException {
        List<ExpressionBackfillPreview.RowValue> firstPass =
                ExpressionBackfillPreview.evaluateRows(connection, table, column, expression);
        List<ExpressionBackfillPreview.RowValue> secondPass =
                ExpressionBackfillPreview.evaluateRows(connection, table, column, expression);
        return compare(firstPass, secondPass);
    }

    /** The pure comparison this class exists for, extracted so it can be unit-tested against
     *  fabricated row values -- {@link ValueExpressionEvaluator}'s only built-in special forms
     *  ({@code now()}/{@code uuid()}) are deliberately deterministic constants (see class javadoc), so
     *  there is no REAL expression in today's vocabulary that would exercise the disagreement branch
     *  through two genuine database evaluations; the logic is proven correct here instead. */
    static ShadowProofResult compare(List<ExpressionBackfillPreview.RowValue> firstPass,
            List<ExpressionBackfillPreview.RowValue> secondPass) {
        List<String> unpopulated = new ArrayList<>();
        Map<Object, Object> firstValues = new LinkedHashMap<>();
        for (ExpressionBackfillPreview.RowValue row : firstPass) {
            if (row.value() == null) {
                unpopulated.add(row.displayId());
            } else {
                firstValues.put(row.rawId(), row.value());
            }
        }
        if (!unpopulated.isEmpty()) {
            return new ShadowProofResult(false, Map.of(), List.copyOf(unpopulated), List.of());
        }

        List<String> nondeterministic = new ArrayList<>();
        for (ExpressionBackfillPreview.RowValue row : secondPass) {
            // The row set itself is expected to be identical between passes (nothing else writes to
            // this table mid-migration, under the migration mutex) -- a row missing from the first
            // pass's map would mean the ROW SET changed between passes, not that this expression is
            // non-deterministic; treated the same as a disagreement (something changed that should
            // not have), named explicitly rather than silently skipped.
            Object firstValue = firstValues.get(row.rawId());
            if (!firstValues.containsKey(row.rawId()) || !Objects.equals(firstValue, row.value())) {
                nondeterministic.add(row.displayId());
            }
        }
        if (!nondeterministic.isEmpty()) {
            return new ShadowProofResult(false, Map.of(), List.of(), List.copyOf(nondeterministic));
        }

        return new ShadowProofResult(true, Map.copyOf(firstValues), List.of(), List.of());
    }
}
