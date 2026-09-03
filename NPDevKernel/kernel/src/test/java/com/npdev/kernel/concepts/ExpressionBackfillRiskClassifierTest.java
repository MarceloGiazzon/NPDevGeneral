package com.npdev.kernel.concepts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpressionBackfillRiskClassifierTest {

    @Test
    void classifiesConstantsAndFieldCopiesAsSafe() {
        assertEquals(ExpressionBackfillRiskClassifier.Tier.SAFE,
                ExpressionBackfillRiskClassifier.classify("now()"));
        assertEquals(ExpressionBackfillRiskClassifier.Tier.SAFE,
                ExpressionBackfillRiskClassifier.classify("uuid()"));
        assertEquals(ExpressionBackfillRiskClassifier.Tier.SAFE,
                ExpressionBackfillRiskClassifier.classify("'draft'"));
        assertEquals(ExpressionBackfillRiskClassifier.Tier.SAFE,
                ExpressionBackfillRiskClassifier.classify("\"draft\""));
        assertEquals(ExpressionBackfillRiskClassifier.Tier.SAFE,
                ExpressionBackfillRiskClassifier.classify("true"));
        assertEquals(ExpressionBackfillRiskClassifier.Tier.SAFE,
                ExpressionBackfillRiskClassifier.classify("42"));
        assertEquals(ExpressionBackfillRiskClassifier.Tier.SAFE,
                ExpressionBackfillRiskClassifier.classify("4.5"));
        assertEquals(ExpressionBackfillRiskClassifier.Tier.SAFE,
                ExpressionBackfillRiskClassifier.classify("$quantity"));
    }

    @Test
    void classifiesPureSameRowArithmeticAndComparisonAsSafe() {
        // No function call anywhere -- a deterministic pure function of same-row columns only.
        assertEquals(ExpressionBackfillRiskClassifier.Tier.SAFE,
                ExpressionBackfillRiskClassifier.classify("quantity * unitPrice"));
        assertEquals(ExpressionBackfillRiskClassifier.Tier.SAFE,
                ExpressionBackfillRiskClassifier.classify("firstName + ' ' + lastName"));
        assertEquals(ExpressionBackfillRiskClassifier.Tier.SAFE,
                ExpressionBackfillRiskClassifier.classify("startDate <= endDate && active"));
    }

    @Test
    void classifiesAnOrdinaryFunctionCallAsReviewable() {
        assertEquals(ExpressionBackfillRiskClassifier.Tier.REVIEWABLE,
                ExpressionBackfillRiskClassifier.classify("conflicts(a, b, c)"));
        assertEquals(ExpressionBackfillRiskClassifier.Tier.REVIEWABLE,
                ExpressionBackfillRiskClassifier.classify("items.all(x => x.tag.matches(a))"));
    }

    @Test
    void classifiesAnUnparseableExpressionAsReviewableNotHighRisk() {
        // Nothing proves it dangerous -- only that nothing proves it safe.
        assertEquals(ExpressionBackfillRiskClassifier.Tier.REVIEWABLE,
                ExpressionBackfillRiskClassifier.classify("quantity *"));
    }

    @Test
    void classifiesAScopePrefixedCallAsHighRisk() {
        assertEquals(ExpressionBackfillRiskClassifier.Tier.HIGH_RISK,
                ExpressionBackfillRiskClassifier.classify("scope.exists(x => x.status == 'A')"));
    }

    @Test
    void classifiesAScopePrefixedCallAsHighRiskEvenNestedInsideAnotherCall() {
        assertEquals(ExpressionBackfillRiskClassifier.Tier.HIGH_RISK,
                ExpressionBackfillRiskClassifier.classify("items.all(x => scope.exists(y => y.id == x.refId))"));
    }
}
