package com.npdev.runtime.support.crud.orchestration;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): the outcome of executing a single
 * {@link RuntimeOrchestrationAction}.
 */
public record OrchestrationActionExecutionResult(
        boolean success,
        String status,
        String reason
) {
    public static OrchestrationActionExecutionResult succeeded(String status, String reason) {
        return new OrchestrationActionExecutionResult(true, status, reason);
    }

    public static OrchestrationActionExecutionResult failed(String status, String reason) {
        return new OrchestrationActionExecutionResult(false, status, reason);
    }
}
