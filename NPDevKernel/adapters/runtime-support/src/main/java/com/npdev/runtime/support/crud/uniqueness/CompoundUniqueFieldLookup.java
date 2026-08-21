package com.npdev.runtime.support.crud.uniqueness;

import java.util.List;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3). LIFT-UNIQUE-P3: existence check for a
 * compound-unique invariant's field group, scoped to a single generated service's own store
 * (mirrors {@link UniqueFieldLookup}).
 */
@FunctionalInterface
public interface CompoundUniqueFieldLookup<ID> {
    boolean exists(List<String> fieldNames, List<Object> values, ID excludeId);
}
