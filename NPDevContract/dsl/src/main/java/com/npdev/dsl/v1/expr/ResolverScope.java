package com.npdev.dsl.v1.expr;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * FORM P3-T3: a {@link ComputedExpression} variable scope whose every lookup is delegated to a
 * resolver function, for hosts whose values live in something that is not a flat map (procedure
 * state with {@code $ref} paths, an event envelope). {@code containsKey} always answers {@code true},
 * so {@code ComputedExpression}'s variable node always takes the {@code get} branch and the resolver
 * sees the whole dotted name ({@code $input.customer.email}) rather than a pre-split path.
 *
 * <p>Replaces the per-host condition grammars ({@code ProcedureConditionEvaluator},
 * {@code ConditionValueSupport}): the host supplies only "how do I find a name"; parsing, operators
 * and null rules are the one expression language's.
 */
public final class ResolverScope extends AbstractMap<String, Object> {

    private final Function<String, Object> resolver;

    public ResolverScope(Function<String, Object> resolver) {
        this.resolver = resolver;
    }

    @Override
    public Object get(Object key) {
        return key instanceof String name ? resolver.apply(name) : null;
    }

    @Override
    public boolean containsKey(Object key) {
        return true;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return Set.of();
    }
}
