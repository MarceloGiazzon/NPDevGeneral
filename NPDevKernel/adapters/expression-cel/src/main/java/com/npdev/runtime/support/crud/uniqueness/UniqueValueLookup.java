package com.npdev.runtime.support.crud.uniqueness;

import java.util.Map;
import java.util.UUID;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): existence check for a single-field
 * unique invariant, at the concept-name level.
 */
@FunctionalInterface
public interface UniqueValueLookup {
    boolean exists(
            String entityName,
            String fieldName,
            Object value,
            UUID excludeId,
            Map<String, Object> payload
    );
}
