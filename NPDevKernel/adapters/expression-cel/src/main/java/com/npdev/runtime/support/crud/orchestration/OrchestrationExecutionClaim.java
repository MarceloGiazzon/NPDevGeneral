package com.npdev.runtime.support.crud.orchestration;

import java.util.List;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): the result of attempting to claim an
 * orchestration execution's idempotency keys before running its actions.
 */
public record OrchestrationExecutionClaim(
        boolean acquired,
        List<String> keys,
        String duplicateKey
) {
    public static OrchestrationExecutionClaim acquired(List<String> keys) {
        return new OrchestrationExecutionClaim(true, keys == null ? List.of() : List.copyOf(keys), null);
    }

    public static OrchestrationExecutionClaim duplicate(String duplicateKey) {
        return new OrchestrationExecutionClaim(false, List.of(), duplicateKey);
    }
}
