package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.kernel.ExecutionContext;

import java.time.Instant;
import java.util.LinkedHashMap;
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
 * the runtime already evaluated it -- true for {@code SchemaExpressionSupport} (the runtime-support
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
        if (isNowLiteral(text)) {
            return Instant.EPOCH.toString();
        }
        if (isUuidLiteral(text)) {
            return UUID.nameUUIDFromBytes("npdev-deterministic-concept-default".getBytes()).toString();
        }
        if (isFieldReference(text)) {
            return data.get(text.substring(1));
        }
        if (isQuotedLiteral(text)) {
            return text.substring(1, text.length() - 1);
        }
        if (isBooleanLiteral(text)) {
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

    /**
     * Pigmentampas P9: a {@code defaultExpression} naming the acting user -- {@code $user.id},
     * {@code $user.actorId}, {@code $user.tenantId}, bare or inside a larger expression
     * ({@code coalesce($user.id, 'system')}) -- so a REST create defaults its owner field the way a
     * hand-written controller would. Anything not naming {@code $user} takes the plain
     * {@link #evaluate(String, Map)} path unchanged. An anonymous actor resolves to {@code null}:
     * no value, so a required owner field still fails its required check instead of being forged.
     */
    public static Object evaluate(String expression, Map<String, Object> data, ExecutionContext context) {
        if (!UserScopedExpressions.referencesUser(expression)) {
            return evaluate(expression, data);
        }
        String text = expression.trim();
        ExecutionContext actor = context == null ? ExecutionContext.anonymous() : context;
        Map<String, Object> scope = new LinkedHashMap<>(data == null ? Map.of() : data);
        String actorId = actor.hasActor() ? actor.actorId() : null;
        scope.put("$user.id", actorId);
        scope.put("$user.actorId", actorId);
        scope.put("$user.tenantId", actor.tenantId());
        if (USER_VALUE_REFERENCES.contains(text)) {
            return scope.get(text);
        }
        try {
            return ComputedExpression.evaluate(text, scope, ValueExpressionFunctions.base());
        } catch (ComputedExpression.ExpressionException unresolvable) {
            // Only the anonymous case has a legitimate "no value" answer ($user.id + '-x' over a
            // null id). For a real actor an unresolvable default is an authoring bug: refuse it
            // loudly rather than silently writing a null owner.
            if (actorId == null) {
                return null;
            }
            throw unresolvable;
        }
    }

    /** The {@code $user} values a {@code defaultExpression} may name (FieldValueValidation mirrors it). */
    public static final java.util.Set<String> USER_VALUE_REFERENCES =
            java.util.Set.of("$user.id", "$user.actorId", "$user.tenantId");

    /**
     * STOR-26 (B2 lift): a STRICT sibling of {@link #evaluate(String, Map)}
     * -- an unparseable or unresolvable expression THROWS {@link ComputedExpression.ExpressionException}
     * instead of silently falling back to the raw expression text. Needed anywhere the raw-text
     * fallback would itself be the bug: the expression-default backfill shadow proof
     * ({@code ExpressionBackfillPreview}, RuntimeHost) must fail loudly, never treat unresolved
     * text as though it were a successfully computed value.
     */
    public static Object evaluateStrict(String expression, Map<String, Object> data, ComputedExpression.FunctionRegistry functions) {
        String text = expression == null ? "" : expression.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (isNowLiteral(text)) {
            return Instant.EPOCH.toString();
        }
        if (isUuidLiteral(text)) {
            return UUID.nameUUIDFromBytes("npdev-deterministic-concept-default".getBytes()).toString();
        }
        if (isFieldReference(text)) {
            return data.get(text.substring(1));
        }
        if (isQuotedLiteral(text)) {
            return text.substring(1, text.length() - 1);
        }
        if (isBooleanLiteral(text)) {
            return Boolean.parseBoolean(text);
        }
        try {
            if (text.contains(".")) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return ComputedExpression.evaluate(text, data, functions);
        }
    }

    // ---- special-form recognizers, extracted verbatim from evaluate()'s own dispatch (same order,
    // same conditions -- no behavior change) so ExpressionBackfillRiskClassifier (B2) can classify
    // exactly the forms this method treats as "no computation needed," never a form it would itself
    // reject or evaluate differently. Package-private: implementation detail of the two classes in
    // this package that need it, not a public API surface. ----

    static boolean isNowLiteral(String text) {
        return "now()".equalsIgnoreCase(text);
    }

    static boolean isUuidLiteral(String text) {
        return "uuid()".equalsIgnoreCase(text);
    }

    static boolean isFieldReference(String text) {
        return text.startsWith("$");
    }

    static boolean isQuotedLiteral(String text) {
        return (text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""));
    }

    static boolean isBooleanLiteral(String text) {
        return "true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text);
    }

    /** Mirrors evaluate()'s own numeric try/catch (Double when there's a '.', else Long) as a pure
     * predicate -- same acceptance set, discards the parsed value. */
    static boolean isNumericLiteral(String text) {
        try {
            if (text.contains(".")) {
                Double.parseDouble(text);
            } else {
                Long.parseLong(text);
            }
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
