package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.kernel.ExecutionContext;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The ONE evaluator for a model expression that may name the acting user -- {@code $user.id},
 * {@code $user.actorId}, {@code $user.tenantId}, {@code $user.roles.contains('Curator')} -- over a
 * record's own fields. Row/field access rules (LNCH-13, R5.5) and lifecycle transition guards both
 * call it, so "who may" reads the same everywhere it is written (extracted from
 * {@link ConfiguredConceptGatewaySemanticPolicy}'s private access-rule evaluator, 2026-10-07, when
 * lifecycle guards gained {@code $user}: Pigmentampas' "only a Curator approves a cap").
 *
 * <p>Fails closed: an expression that does not evaluate cleanly is {@code false}, never a grant.
 */
public final class UserScopedExpressions {

    private UserScopedExpressions() {
    }

    /**
     * REG-195: {@code contains} is registered here because the plain two-argument
     * {@code evaluateBoolean} resolves to an EMPTY function registry, which made
     * {@code $user.roles.contains(...)} silently false regardless of the actor's roles.
     */
    private static final ComputedExpression.FunctionRegistry FUNCTIONS =
            ComputedExpression.FunctionRegistry.of(Map.of(
                    "contains", (args, vars) -> {
                        Object receiver = args.get(0).eval(vars);
                        Object needle = args.get(1).eval(vars);
                        if (receiver instanceof SortedSet<?> roleSet) {
                            // $user.roles (below): a case-insensitive String set, so a non-String
                            // needle would throw ClassCastException -- it simply is not a role.
                            return needle instanceof String && roleSet.contains(needle);
                        }
                        if (receiver instanceof Collection<?> collection) {
                            return collection.contains(needle);
                        }
                        if (receiver instanceof String haystack) {
                            return needle != null && haystack.contains(String.valueOf(needle));
                        }
                        return false;
                    }
            ));

    /** True when {@code expression} names the acting user, i.e. needs this evaluator's scope. */
    public static boolean referencesUser(String expression) {
        return expression != null && expression.contains("$user");
    }

    public static boolean evaluate(String expression, Map<String, Object> recordData, ExecutionContext context) {
        Map<String, Object> scope = new LinkedHashMap<>(recordData == null ? Map.of() : recordData);
        ExecutionContext effectiveContext = context == null ? ExecutionContext.anonymous() : context;
        scope.put("$user.id", effectiveContext.actorId());
        scope.put("$user.actorId", effectiveContext.actorId());
        scope.put("$user.tenantId", effectiveContext.tenantId());
        // Role names are case-insensitive identifiers platform-wide (ExecutionContext upper-cases
        // them), so a model's natural $user.roles.contains('Staff') must match a STAFF actor.
        SortedSet<String> roles = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        roles.addAll(effectiveContext.roles());
        scope.put("$user.roles", roles);
        try {
            return ComputedExpression.evaluateBoolean(expression, scope, FUNCTIONS);
        } catch (ComputedExpression.ExpressionException malformed) {
            // Fail closed: SemanticValidator already rejects a malformed rule at model-compile time,
            // so reaching this at runtime means something bypassed validation (e.g. a hand-edited
            // compiled model); denying is the only safe default.
            return false;
        }
    }
}
