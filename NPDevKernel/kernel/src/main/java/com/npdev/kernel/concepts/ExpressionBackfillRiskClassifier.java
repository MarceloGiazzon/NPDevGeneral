package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.expr.ComputedExpression;

import java.util.Set;

/**
 * BOUNDARY_LIFT_PLAN_2026-09-02 package 3.1 (B2): classifies an expression-default's TEXT -- never
 * a live row's evaluated result, see {@code ExpressionBackfillPreview} (RuntimeHost) for that --
 * into a risk tier a boot-time backfill can act on differently.
 *
 * <p>{@link Tier#SAFE}: a constant, a single-column copy, or a pure arithmetic/comparison/logical
 * function of same-row fields -- i.e. exactly the forms {@link ValueExpressionEvaluator#evaluate}
 * itself recognizes as "no computation needed" (its {@code isXxxLiteral}/{@code isFieldReference}
 * helpers, reused here rather than re-derived) plus any {@link ComputedExpression} parse that
 * contains no function call at all. A SAFE expression may auto-apply once a fresh preview proves
 * every affected row resolves to a non-null value.
 *
 * <p>{@link Tier#REVIEWABLE}: contains a function call (a lookup, a conditional built from a call,
 * cross-column logic routed through one). {@link Tier#HIGH_RISK}: contains a call whose name starts
 * with {@code "scope."} -- the one concrete cross-record signal this grammar carries today (see
 * {@code ComputedExpression.DIRECT_FUNCTION_NAMES}, which names {@code "scope.exists"}). Both tiers
 * keep today's mandatory preview-and-acknowledge path unchanged -- the split is for the boot log
 * and the ControlPanel preview to say WHY a candidate isn't auto-applying, not a behavior gate. An
 * expression that fails to parse at all is REVIEWABLE, not HIGH_RISK: nothing proves it dangerous,
 * only that nothing proves it safe.
 */
public final class ExpressionBackfillRiskClassifier {

    public enum Tier {
        SAFE, REVIEWABLE, HIGH_RISK
    }

    private static final String CROSS_RECORD_FUNCTION_PREFIX = "scope.";

    private ExpressionBackfillRiskClassifier() {
    }

    public static Tier classify(String expression) {
        String text = expression == null ? "" : expression.trim();
        if (isSimpleSameRowForm(text)) {
            return Tier.SAFE;
        }
        Set<String> functionCalls;
        try {
            functionCalls = ComputedExpression.functionCalls(text);
        } catch (ComputedExpression.ExpressionException unparseable) {
            return Tier.REVIEWABLE;
        }
        if (functionCalls.stream().anyMatch(name -> name.startsWith(CROSS_RECORD_FUNCTION_PREFIX))) {
            return Tier.HIGH_RISK;
        }
        return functionCalls.isEmpty() ? Tier.SAFE : Tier.REVIEWABLE;
    }

    private static boolean isSimpleSameRowForm(String text) {
        return text.isEmpty()
                || ValueExpressionEvaluator.isNowLiteral(text)
                || ValueExpressionEvaluator.isUuidLiteral(text)
                || ValueExpressionEvaluator.isFieldReference(text)
                || ValueExpressionEvaluator.isQuotedLiteral(text)
                || ValueExpressionEvaluator.isBooleanLiteral(text)
                || ValueExpressionEvaluator.isNumericLiteral(text);
    }
}
