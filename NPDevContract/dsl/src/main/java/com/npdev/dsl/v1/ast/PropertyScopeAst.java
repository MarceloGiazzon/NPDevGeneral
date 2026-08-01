package com.npdev.dsl.v1.ast;

/**
 * MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 6 (RC-A1, {@code MOVE11_RUNTIME_CONFIGURATION_PLAN}
 * Part A.1): one level of the scoped-property cascade -- its name and how its scope-id resolves
 * from {@code ExecutionContext} at authentication time ({@code from}), never a per-read database
 * lookup.
 *
 * <p><b>Order in the model's declared {@code propertyScopes} list IS the resolution order</b>,
 * most specific first -- one list, no second place to state precedence. The least-specific level
 * is not a declared scope at all: it is the property's own {@code default} (see
 * {@link PropertyAst}), which is deliberate -- it avoids a "system" scope needing its own storage
 * row and sidesteps the reserved-sentinel trap a literal tenant id like {@code "default"} would
 * otherwise invite.
 *
 * <p>{@code from} is null/blank for the always-available root scope (implicitly the current
 * tenant, {@code $ctx.tenantId} -- every request already carries one, so it needs no expression).
 * A non-root scope's {@code from} must be one of the grammar {@code PropertyValidation} enforces
 * at compile time: {@code $ctx.tenantId}, {@code $user.id}, or {@code $user.<tagName>} (an
 * arbitrary key already present in {@code ExecutionContext.tags}, e.g. a JWT claim mapped at
 * authentication time).
 */
public record PropertyScopeAst(String name, String from) {
    public PropertyScopeAst {
        from = from == null || from.isBlank() ? null : from.trim();
    }
}
