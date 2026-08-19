package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.expr.ComputedExpression;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The canonical evaluator for a field's {@code defaultExpression}/{@code derivedExpression} -- a
 * literal, a {@code $field} reference into the current data, {@code now()}/{@code uuid()}, a bare
 * literal value, or (R4.1) the {@link ComputedExpression} grammar (arithmetic, comparison, logical,
 * function calls) for anything else. Extracted from {@link ConfiguredConceptGatewaySemanticPolicy}
 * (Move 9 B1, {@code docs/ACCEPTED_BOUNDARIES.md} B2) so a NEW row's default (this class's original
 * caller) and an EXISTING row's expression-default backfill preview ({@code BackfillPass},
 * RuntimeHost) compute the identical value for the identical expression -- one evaluator, not two
 * dialects.
 *
 * <p>R4.1 (roadmap): {@code FieldValueValidation}'s author-time validator was widened to accept the
 * full {@code ComputedExpression} grammar (e.g. {@code "quantity * unitPrice"}), on the premise that
 * the runtime already evaluated it -- true for {@code SchemaExpressionSupport} (the expression-cel
 * adapter's CRUD path), but NOT for this class, which a generated concept's declarative
 * {@code capabilities}/{@code bindings}-bound create/update ultimately writes through
 * ({@code DefaultConceptGateway.save} -> this evaluator). Before this, an arithmetic expression fell
 * through every special form here and hit the bottom {@code Long.parseLong}/{@code Double.parseDouble}
 * attempt, which failed and returned the RAW EXPRESSION TEXT itself as the field's value -- silently
 * writing the literal string {@code "quantity * unitPrice"} into a decimal column (measured live: H2
 * then threw parsing that string as {@code BigDecimal}, "Character array is missing 'e' notation
 * exponential mark"). Only the final fallback changes; every existing special form above it ({@code
 * now()}/{@code uuid()}'s deterministic values, the {@code $field} sigil, quoted literals, true/
 * false, a bare number) keeps its exact prior behavior unchanged -- {@code ComputedExpression} is
 * tried only for text none of those already claimed.
 */
public final class ValueExpressionEvaluator {

    private ValueExpressionEvaluator() {
    }

    public static Object evaluate(String expression, Map<String, Object> data) {
        String text = expression == null ? "" : expression.trim();
        if (text.isEmpty()) {
            return null;
        }
        if ("now()".equalsIgnoreCase(text)) {
            return Instant.EPOCH.toString();
        }
        if ("uuid()".equalsIgnoreCase(text)) {
            return UUID.nameUUIDFromBytes("npdev-deterministic-concept-default".getBytes()).toString();
        }
        if (text.startsWith("$")) {
            return data.get(text.substring(1));
        }
        if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""))) {
            return text.substring(1, text.length() - 1);
        }
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        try {
            if (text.contains(".")) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            try {
                return ComputedExpression.evaluate(text, data);
            } catch (ComputedExpression.ExpressionException stillUnparseable) {
                return text;
            }
        }
    }
}
