package com.npdev.kernel.properties;

import com.npdev.kernel.ExecutionContext;

/**
 * RC-A3 (Move 14 Phase B item B2): resolves a declared runtime property (model {@code properties[]},
 * RC-A1/Wave 6) through the scoped-property cascade ({@code propertyScopes[]}, most specific first)
 * against stored rows in the {@code workspace::PropertyValue} concept (RC-A2). Row PRESENCE is the
 * is-set signal: a stored row with a null value means explicitly set to null at that scope; no row at
 * all means inherit from the next-less-specific scope. The least-specific level is not a stored row
 * at all -- it is the property's own declared {@code default}.
 *
 * <p>Deliberately narrow surface (see the cascade's own prohibitions, {@code __OutsideRepo/
 * move13-helpers/rc-a3-cascade-vectors.json}): never fetch "the user's settings blob" (resolution is
 * per-property, never a blob a caller could cache wrong), never resolve {@code from:} with a per-read
 * database lookup (scope keys come from {@link ExecutionContext#tags()}, populated once at
 * authentication), never add a stored "system" scope (the model's declared {@code default} is the
 * floor).
 */
public interface PropertyResolver {

    /**
     * Resolves {@code propertyKey} for {@code context}, coerced to the property's declared type.
     * Throws {@link PropertyNotDeclaredException} if {@code propertyKey} names no declared property
     * (the X0 rule: an input the resolver cannot handle is an error, never a default answer).
     */
    Object resolve(String propertyKey, ExecutionContext context);

    /**
     * Same resolution as {@link #resolve}, but names the winning scope AND every less-specific scope
     * it overrode -- "why is this value X?" must be answerable on day one. Ships in the same commit as
     * {@link #resolve} (nearly free now, effectively unbuildable once the mechanism is in use).
     */
    PropertyExplanation explain(String propertyKey, ExecutionContext context);

    /**
     * Sets {@code propertyKey} at the given scope (a value of {@code null} is a real, distinct
     * cascade state -- "explicitly set to null" -- not a delete). Enforced at WRITE time only:
     * {@code settableAt} does not constrain read-time resolution, only which scopes may hold a row.
     * Throws {@link PropertyNotSettableAtScopeException} when {@code scopeType} is not one of the
     * property's declared {@code settableAt} values. Every set is audited, including a set to null.
     */
    void set(String scopeType, String scopeId, String propertyKey, Object propertyValue, ExecutionContext context);
}
