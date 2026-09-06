package com.npdev.runtime.support.crud.schemaexpr;

import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.kernel.concepts.ValueExpressionFunctions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.OBJECT_MAPPER;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.normalize;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.readMapValue;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): schema {@code default}/{@code derived}
 * value-behavior expression evaluation -- the small hand-rolled literal/identifier/call grammar
 * (concat/coalesce/trim/uppercase/lowercase) that backs {@code CompiledSchema} default and derived
 * expressions.
 */
public final class SchemaExpressionSupport {

    /**
     * LNCH-15: {@code concat}/{@code coalesce}/{@code trim}/{@code uppercase}/{@code lowercase},
     * so schema default/derived expressions route through the same unified grammar as invariants
     * instead of this class's own hand-rolled literal/identifier/call evaluator (see
     * {@link #evaluateSchemaExpression}). STOR-26 (B2 lift): the function set itself, and {@link
     * #applyValueBehaviorFunction}'s implementation, moved to {@code
     * com.npdev.kernel.concepts.ValueExpressionFunctions} so the backfill shadow proof (RuntimeHost)
     * can resolve the identical functions -- this class delegates rather than keeping its own copy.
     */
    public static final Set<String> VALUE_BEHAVIOR_FUNCTIONS = ValueExpressionFunctions.VALUE_BEHAVIOR_FUNCTIONS;

    public static final ComputedExpression.FunctionRegistry SCHEMA_EXPRESSION_FUNCTIONS =
            ValueExpressionFunctions.base();

    private SchemaExpressionSupport() {
    }

    public static Object cloneSchemaDefaultValue(Object value) {
        if (value == null) {
            return null;
        }
        return OBJECT_MAPPER.convertValue(value, Object.class);
    }

    public static Object evaluateSchemaExpression(String expression, Map<String, Object> values) {
        String trimmed = expression == null ? "" : expression.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        // LNCH-15: try the unified ComputedExpression grammar first (concat/coalesce/trim/
        // uppercase/lowercase via SCHEMA_EXPRESSION_FUNCTIONS). Falls through to this method's
        // own legacy evaluator -- kept as a defensive safety net, not deleted -- for anything it
        // doesn't recognize, so a malformed expression still yields null at record-save time
        // instead of throwing.
        try {
            return ComputedExpression.evaluate(trimmed, values, SCHEMA_EXPRESSION_FUNCTIONS);
        } catch (ComputedExpression.ExpressionException ignored) {
            // fall through to the legacy evaluator below
        }
        if (isQuotedLiteral(trimmed)) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        if ("null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        if ("true".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }
        Object numeric = parseNumericLiteral(trimmed);
        if (numeric != null) {
            return numeric;
        }
        if (trimmed.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return readMapValue(values, trimmed);
        }

        int openParen = trimmed.indexOf('(');
        if (openParen <= 0 || !trimmed.endsWith(")") || !isBalancedValueExpression(trimmed)) {
            return null;
        }

        String functionName = trimmed.substring(0, openParen).trim();
        if (!VALUE_BEHAVIOR_FUNCTIONS.contains(normalize(functionName))) {
            return null;
        }
        List<String> args = splitTopLevelArguments(trimmed.substring(openParen + 1, trimmed.length() - 1));
        if (args == null) {
            return null;
        }
        List<Object> resolvedArgs = new ArrayList<>();
        for (String arg : args) {
            resolvedArgs.add(evaluateSchemaExpression(arg, values));
        }
        return applyValueBehaviorFunction(functionName, resolvedArgs);
    }

    public static Object applyValueBehaviorFunction(String functionName, List<Object> args) {
        return ValueExpressionFunctions.apply(functionName, args);
    }

    public static boolean isQuotedLiteral(String value) {
        if (value == null || value.length() < 2) {
            return false;
        }
        return (value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"));
    }

    public static Object parseNumericLiteral(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            long parsed = Long.parseLong(value);
            if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
                return (int) parsed;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static boolean isBalancedValueExpression(String expression) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (current == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (inSingle || inDouble) {
                continue;
            }
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0 && !inSingle && !inDouble;
    }

    public static List<String> splitTopLevelArguments(String argsBody) {
        List<String> args = new ArrayList<>();
        if (argsBody == null) {
            return args;
        }
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int index = 0; index < argsBody.length(); index++) {
            char currentChar = argsBody.charAt(index);
            if (currentChar == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(currentChar);
                continue;
            }
            if (currentChar == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(currentChar);
                continue;
            }
            if (!inSingle && !inDouble) {
                if (currentChar == '(') {
                    depth++;
                } else if (currentChar == ')') {
                    depth--;
                    if (depth < 0) {
                        return null;
                    }
                } else if (currentChar == ',' && depth == 0) {
                    String candidate = current.toString().trim();
                    if (candidate.isEmpty()) {
                        return null;
                    }
                    args.add(candidate);
                    current.setLength(0);
                    continue;
                }
            }
            current.append(currentChar);
        }
        if (depth != 0 || inSingle || inDouble) {
            return null;
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            args.add(tail);
        }
        return args;
    }

    public static boolean isMissingValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.trim().isEmpty();
        }
        return false;
    }
}
