package com.npdev.dsl.v1.validation;

import java.util.List;
import java.util.Optional;

/**
 * The one PUBLIC way to ask "which identifiers does this interaction expression read?".
 *
 * <p>{@link ExpressionValidation} owns the tokenizer and is package-private, which was fine while
 * the only caller was another validator in this package. XREF-1 needs the same answer from
 * {@code com.npdev.dsl.v1.xref}, and the alternative -- a second tokenizer over there -- is
 * REG-108's "two walks of the same graph that drift" shape applied to expression syntax: the day
 * someone adds an operator to one, {@code visibleWhen} silently stops being cross-referenced.
 * So this is a delegating facade with no logic of its own, deliberately.
 *
 * <p>The distinction between "parsed, and read nothing" and "did not parse" is preserved by
 * {@link #references(String)} returning an empty {@link Optional} for the latter. Collapsing the
 * two into an empty list would make an unparseable predicate read as a predicate with no
 * references -- i.e. as clean -- which is exactly REG-185's failure mode.
 */
public final class ExpressionReferences {

    private ExpressionReferences() {
    }

    /**
     * @return the ordered, de-duplicated identifiers the expression reads, or an empty
     *         {@link Optional} when the expression is outside the interaction grammar (a
     *         {@code $ui.}/{@code $root.} form, an unsupported operator, unbalanced parentheses).
     *         A null or blank expression parses trivially and yields an empty LIST, not an empty
     *         Optional -- nothing declared is not the same as something unreadable.
     */
    public static Optional<List<String>> references(String expression) {
        if (expression == null || expression.isBlank()) {
            return Optional.of(List.of());
        }
        ExpressionValidation.InteractionExpressionAnalysis analysis =
                ExpressionValidation.analyzeInteractionExpression(expression);
        if (!analysis.valid()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(analysis.references()));
    }
}
