package com.npdev.kernel.ports;

import java.util.Map;

/**
 * Controlled lookup boundary for scope-aware invariant evaluation.
 *
 * Implementations should remain deterministic and intentionally narrow.
 * This is not a general query surface.
 */
@FunctionalInterface
public interface InvariantScopeProvider {

    boolean exists(
            String conceptName,
            String fieldPath,
            Object expectedValue,
            Map<String, Object> state,
            Object payload
    );

    static InvariantScopeProvider noop() {
        return (conceptName, fieldPath, expectedValue, state, payload) -> false;
    }
}
