package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.expr.ComputedExpression;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * STOR-26 (B2 lift, {@code ledger/boundaries/B2.yml}): the canonical {@link
 * ComputedExpression.FunctionRegistry} for a value-behavior expression -- {@code concat}/
 * {@code coalesce}/{@code trim}/{@code uppercase}/{@code lowercase}, plus (via {@link #withScope})
 * {@code scope.exists} -- so every caller that evaluates a {@code defaultExpression}/
 * {@code derivedExpression} through {@link ValueExpressionEvaluator} resolves the SAME function set
 * instead of each caller wiring its own subset (or, as the expression-default backfill shadow proof
 * did before this class existed, none at all -- see {@code ExpressionBackfillPreview}, RuntimeHost).
 *
 * <p>The five value-behavior functions are moved here, not copied, from {@code
 * SchemaExpressionSupport.applyValueBehaviorFunction} (runtime-support adapter), which now
 * delegates to {@link #apply}. Kernel is the right home: {@link ValueExpressionEvaluator} already
 * lives here, and runtime-support already depends on kernel.
 */
public final class ValueExpressionFunctions {

    private ValueExpressionFunctions() {
    }

    /** LNCH-15: {@code concat}/{@code coalesce}/{@code trim}/{@code uppercase}/{@code lowercase} --
     * moved verbatim from {@code SchemaExpressionSupport}, same name and semantics. */
    public static final Set<String> VALUE_BEHAVIOR_FUNCTIONS =
            Set.of("concat", "coalesce", "trim", "uppercase", "lowercase");

    /**
     * A live existence check for {@code scope.exists(concept, fieldPath, value)}. Deliberately
     * narrower than {@code CelInvariantEngine.ScopeChecker}/{@code InvariantScopeProvider} (no
     * {@code state}/{@code payload} -- there is no request in progress at boot-time backfill),
     * mirrored by {@code JdbcScopeExistsFn} (RuntimeHost), the boot-time JDBC implementation.
     */
    @FunctionalInterface
    public interface ScopeExistsFn {
        boolean exists(String concept, String fieldPath, Object value);
    }

    /** The five value-behavior functions, no {@code scope.exists}. */
    public static ComputedExpression.FunctionRegistry base() {
        return ComputedExpression.FunctionRegistry.of(valueBehaviorFunctions());
    }

    /** {@link #base()} plus {@code scope.exists}, backed by {@code fn}. */
    public static ComputedExpression.FunctionRegistry withScope(ScopeExistsFn fn) {
        Map<String, ComputedExpression.ExprFunction> functions = new LinkedHashMap<>(valueBehaviorFunctions());
        functions.put("scope.exists", (args, vars) -> {
            String concept = String.valueOf(args.get(0).eval(vars));
            String fieldPath = String.valueOf(args.get(1).eval(vars));
            Object value = args.get(2).eval(vars);
            return fn.exists(concept, fieldPath, value);
        });
        return ComputedExpression.FunctionRegistry.of(functions);
    }

    private static Map<String, ComputedExpression.ExprFunction> valueBehaviorFunctions() {
        Map<String, ComputedExpression.ExprFunction> functions = new LinkedHashMap<>();
        for (String name : VALUE_BEHAVIOR_FUNCTIONS) {
            functions.put(name, (args, vars) -> apply(name, args.stream().map(arg -> arg.eval(vars)).toList()));
        }
        return functions;
    }

    /**
     * The five value-behavior functions' shared implementation -- moved verbatim from {@code
     * SchemaExpressionSupport.applyValueBehaviorFunction} (same null handling in {@code coalesce},
     * same {@link Locale#ROOT} case-folding in {@code uppercase}/{@code lowercase}), which now
     * delegates here instead of keeping its own copy.
     */
    public static Object apply(String functionName, List<Object> args) {
        String normalized = functionName == null ? "" : functionName.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "concat" -> {
                if (args.isEmpty() || args.stream().anyMatch(Objects::isNull)) {
                    yield null;
                }
                StringBuilder out = new StringBuilder();
                for (Object arg : args) {
                    out.append(String.valueOf(arg));
                }
                yield out.toString();
            }
            case "coalesce" -> {
                for (Object arg : args) {
                    if (!isMissingValue(arg)) {
                        yield arg;
                    }
                }
                yield null;
            }
            case "trim" -> args.size() == 1 && args.get(0) != null ? String.valueOf(args.get(0)).trim() : null;
            case "uppercase" -> args.size() == 1 && args.get(0) != null
                    ? String.valueOf(args.get(0)).toUpperCase(Locale.ROOT)
                    : null;
            case "lowercase" -> args.size() == 1 && args.get(0) != null
                    ? String.valueOf(args.get(0)).toLowerCase(Locale.ROOT)
                    : null;
            default -> null;
        };
    }

    private static boolean isMissingValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.trim().isEmpty();
        }
        return false;
    }
}
