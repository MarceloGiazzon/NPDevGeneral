package com.npdev.kernel.ports;

import java.util.List;

/**
 * Adapter-neutral factory for runtime invariant evaluation.
 *
 * Shared runtime support depends on this boundary instead of constructing
 * a concrete invariant-engine adapter directly.
 */
public interface RuntimeInvariantEngineFactory {

    InvariantEngine create(
            UniqueValueLookup uniqueValueLookup,
            ConflictLookup conflictLookup
    );

    default InvariantEngine create(
            UniqueValueLookup uniqueValueLookup,
            ConflictLookup conflictLookup,
            InvariantScopeProvider invariantScopeProvider
    ) {
        return create(uniqueValueLookup, conflictLookup);
    }

    /** LIFT-UNIQUE-P3: adds compound (multi-field) unique pre-checking. Defaults to no compound
     * checking (always "not a duplicate") so existing adapters compile unchanged; only entities
     * with a compound-unique invariant need it wired. */
    default InvariantEngine create(
            UniqueValueLookup uniqueValueLookup,
            ConflictLookup conflictLookup,
            InvariantScopeProvider invariantScopeProvider,
            CompoundUniqueValueLookup compoundUniqueValueLookup
    ) {
        return create(uniqueValueLookup, conflictLookup, invariantScopeProvider);
    }

    @FunctionalInterface
    interface UniqueValueLookup {
        boolean exists(
                String requestedEntity,
                String fieldName,
                Object value,
                Object rawPayload
        );
    }

    /** LIFT-UNIQUE-P3: existence check for a compound-unique invariant's field group. */
    @FunctionalInterface
    interface CompoundUniqueValueLookup {
        boolean exists(
                String requestedEntity,
                List<String> fieldNames,
                List<Object> values,
                Object rawPayload
        );
    }

    interface ConflictLookup {
        boolean conflicts(
                String resourceField,
                Object resourceId,
                String startsAtField,
                Object startsAt,
                String durationField,
                Object durationMinutes,
                Object excludeId,
                Object payload
        );
    }
}
