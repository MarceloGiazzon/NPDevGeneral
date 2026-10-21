package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.expr.ComputedExpression;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * REG-269 ("accepted implies enforced"): an invariant that orders a {@code date}/{@code datetime}
 * field ({@code endDate >= startDate}) is evaluated chronologically by the engine, but only against
 * another temporal value. Ordering a date against a number, a free-text literal or a non-date field has
 * no meaning, and before the engine learned dates such an expression was silently always true. The
 * author must be told at validate time instead of believing the rule is enforced.
 */
final class InvariantTemporalComparisonValidation {

    private static final Pattern ISO_DATE_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([T ].*)?$");

    private InvariantTemporalComparisonValidation() {
    }

    static void validate(String entityName, String expression, List<FieldAst> fields, List<String> errors) {
        List<ComputedExpression.OrderedComparison> comparisons;
        try {
            comparisons = ComputedExpression.orderedComparisons(expression);
        } catch (ComputedExpression.ExpressionException celSpecificSyntax) {
            return; // not parseable by the unified grammar; no static check available
        }
        for (ComputedExpression.OrderedComparison comparison : comparisons) {
            String left = describe(comparison.left(), fields);
            String right = describe(comparison.right(), fields);
            boolean leftTemporal = "temporal".equals(left);
            boolean rightTemporal = "temporal".equals(right);
            if (!leftTemporal && !rightTemporal) {
                continue;
            }
            if (leftTemporal && rightTemporal) {
                continue;
            }
            String other = leftTemporal ? right : left;
            if ("unknown".equals(other)) {
                continue; // arithmetic / call / unresolved reference: cannot judge statically
            }
            errors.add("Entity " + entityName + " invariant expression: '" + comparison.op()
                    + "' compares a date field with " + describeForMessage(other)
                    + ", which cannot be ordered -- compare it with another date/datetime field or an ISO date"
                    + " literal such as '2026-01-31': " + expression);
        }
    }

    /** @return "temporal", "other-field", "number", "boolean", "null", "text" or "unknown". */
    private static String describe(ComputedExpression.Operand operand, List<FieldAst> fields) {
        switch (operand.kind()) {
            case "field": {
                String type = typeOf(operand.field(), fields);
                if (type == null) {
                    return "unknown";
                }
                return "date".equals(type) || "datetime".equals(type) ? "temporal" : "other-field";
            }
            case "literal": {
                Object value = operand.literal();
                if (value == null) {
                    return "null";
                }
                if (value instanceof Number) {
                    return "number";
                }
                if (value instanceof Boolean) {
                    return "boolean";
                }
                return ISO_DATE_PREFIX.matcher(String.valueOf(value).trim()).matches() ? "temporal" : "text";
            }
            default:
                return "unknown";
        }
    }

    private static String describeForMessage(String kind) {
        return switch (kind) {
            case "other-field" -> "a field that is not a date";
            case "number" -> "a number";
            case "boolean" -> "a boolean";
            case "null" -> "null";
            default -> "text that is not an ISO date";
        };
    }

    /** The lower-cased declared type of a plain (non-dotted) field reference, or null when unresolved. */
    private static String typeOf(String reference, List<FieldAst> fields) {
        if (reference == null || reference.contains(".") || reference.startsWith("$") || fields == null) {
            return null;
        }
        for (FieldAst field : fields) {
            if (field != null && field.getName() != null && field.getName().equalsIgnoreCase(reference)) {
                return field.getType() == null ? null : field.getType().trim().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }
}
