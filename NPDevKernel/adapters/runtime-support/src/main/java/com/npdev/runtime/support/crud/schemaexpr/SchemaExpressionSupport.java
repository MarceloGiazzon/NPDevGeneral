package com.npdev.runtime.support.crud.schemaexpr;

import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.kernel.concepts.ValueExpressionFunctions;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.OBJECT_MAPPER;

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
        // own legacy literal/identifier/call forms for anything it doesn't recognize. REG-284: an
        // expression neither can evaluate is an error, never null -- a null derived value REMOVES the
        // field, so the old "malformed yields null" answer erased it on every save.
        ComputedExpression.ExpressionException unevaluable;
        try {
            return ComputedExpression.evaluate(trimmed, values, SCHEMA_EXPRESSION_FUNCTIONS);
        } catch (ComputedExpression.ExpressionException computedError) {
            unevaluable = computedError;
        }
        // REG-284: an expression the unified grammar cannot evaluate is an error, never null -- a null
        // derived value REMOVES the field. (FORM P3-T4: the legacy literal/identifier/call fallback is gone.)
        throw unevaluable;
    }

    public static Object applyValueBehaviorFunction(String functionName, List<Object> args) {
        return ValueExpressionFunctions.apply(functionName, args);
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
