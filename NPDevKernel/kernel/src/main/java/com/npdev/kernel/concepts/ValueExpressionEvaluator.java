package com.npdev.kernel.concepts;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The canonical evaluator for a field's {@code defaultExpression}/{@code derivedExpression} -- a
 * literal, a {@code $field} reference into the current data, {@code now()}/{@code uuid()}, or a
 * bare literal value. Extracted from {@link ConfiguredConceptGatewaySemanticPolicy} (Move 9 B1,
 * {@code docs/ACCEPTED_BOUNDARIES.md} B2) so a NEW row's default (this class's original caller) and
 * an EXISTING row's expression-default backfill preview ({@code BackfillPass}, RuntimeHost) compute
 * the identical value for the identical expression -- one evaluator, not two dialects.
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
            return text;
        }
    }
}
