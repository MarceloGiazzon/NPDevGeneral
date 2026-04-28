package com.npdev.kernel.ports;

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

    @FunctionalInterface
    interface UniqueValueLookup {
        boolean exists(
                String requestedEntity,
                String fieldName,
                Object value,
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
