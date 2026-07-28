package com.npdev.runtime.support.crud.uniqueness;

import com.npdev.kernel.ports.RuntimeInvariantEngineFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3). LIFT-UNIQUE-P3: existence check for a
 * compound-unique invariant's field group, at the concept-name level (mirrors
 * {@link RuntimeInvariantEngineFactory.CompoundUniqueValueLookup}).
 */
@FunctionalInterface
public interface CompoundUniqueValueLookup {
    boolean exists(
            String entityName,
            List<String> fieldNames,
            List<Object> values,
            UUID excludeId,
            Map<String, Object> payload
    );
}
