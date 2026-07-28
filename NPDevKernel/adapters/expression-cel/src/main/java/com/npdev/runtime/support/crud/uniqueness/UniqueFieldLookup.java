package com.npdev.runtime.support.crud.uniqueness;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): existence check for a single-field
 * unique invariant, scoped to a single generated service's own store.
 */
@FunctionalInterface
public interface UniqueFieldLookup<ID> {
    boolean exists(String fieldName, Object value, ID excludeId);
}
