package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.expr.ComputedExpression;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ENG-7: the PURE subset of CelInvariantEngine.buildFunctionRegistry
 * (NPDevKernel/adapters/runtime-support/.../CelInvariantEngine.java:935-1060) that the concept gateway can
 * evaluate without a store or a request: matches, uniqueBy, all, exists -- plus the value functions
 * (ValueExpressionFunctions.VALUE_BEHAVIOR_FUNCTIONS). Semantics copied, minus FailureHint plumbing:
 * <ul>
 *   <li>uniqueBy: null collection = true; key compared trim().toLowerCase(ROOT); blank keys ignored</li>
 *   <li>all: null collection = true (vacuous); exists: null/empty collection = false</li>
 * </ul>
 * Store-dependent functions (conflicts/overlapsProvider, scope.exists, sum over another concept) are
 * deliberately ABSENT: ComputedExpression throws "unknown function" for them, and the gateway then
 * keeps today's "skippedUnsupportedExpression" outcome (the kernel CEL engine enforces those on the CRUD path).
 */
public final class GatewayInvariantFunctions {

    private GatewayInvariantFunctions() {
    }

    public static ComputedExpression.FunctionRegistry registry() {
        Map<String, ComputedExpression.ExprFunction> functions = new LinkedHashMap<>();
        for (String name : ValueExpressionFunctions.VALUE_BEHAVIOR_FUNCTIONS) {
            functions.put(name, (args, vars) ->
                    ValueExpressionFunctions.apply(name, args.stream().map(arg -> arg.eval(vars)).toList()));
        }
        functions.put("matches", (args, vars) -> {
            Object value = args.get(0).eval(vars);
            Object regex = args.get(1).eval(vars);
            return value instanceof String text && regex != null && !String.valueOf(regex).isBlank()
                    && text.matches(String.valueOf(regex));
        });
        functions.put("uniqueBy", (args, vars) -> {
            Object collection = args.get(0).eval(vars);
            if (collection == null) {
                return true;
            }
            if (!(collection instanceof Iterable<?> items)) {
                return false;
            }
            String keyField = args.get(1).describeFieldPath();
            if (keyField == null) {
                keyField = String.valueOf(args.get(1).eval(vars));
            }
            Set<String> seen = new LinkedHashSet<>();
            for (Object item : items) {
                Object raw = item instanceof Map<?, ?> map ? map.get(keyField) : null;
                if (raw == null) {
                    continue;
                }
                String key = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
                if (!key.isBlank() && !seen.add(key)) {
                    return false;
                }
            }
            return true;
        });
        functions.put("all", (args, vars) -> quantifier(args, vars, true));
        functions.put("exists", (args, vars) -> quantifier(args, vars, false));
        return ComputedExpression.FunctionRegistry.of(functions);
    }

    private static Object quantifier(List<ComputedExpression.Node> args, Map<String, Object> vars, boolean isAll) {
        Object collection = args.get(0).eval(vars);
        if (collection == null) {
            return isAll;
        }
        if (!(collection instanceof Iterable<?> items)) {
            return false;
        }
        for (Object item : items) {
            boolean passes = Boolean.TRUE.equals(args.get(1).invokeLambda(item, vars));
            if (isAll && !passes) {
                return false;
            }
            if (!isAll && passes) {
                return true;
            }
        }
        return isAll;
    }
}
