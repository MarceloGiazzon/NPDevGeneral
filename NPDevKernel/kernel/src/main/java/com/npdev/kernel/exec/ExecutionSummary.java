package com.npdev.kernel.exec;

public record ExecutionSummary(
        String executionId,
        String tenantId,
        String correlationId,
        String flowName,
        String status,
        int currentStepIndex,
        String waitingForEventName,
        long updatedAtMs,
        int resumeAttemptCount,
        Long lastResumeAtEpochMs,
        String lastResumeErrorCode,
        Long nextEligibleResumeAtEpochMs,
        Long lastProgressAtEpochMs,
        String lastErrorKind,
        String lastErrorCode,
        Long failedAtEpochMs
) {
}
