package com.npdev.kernel.execution;

import com.npdev.kernel.errors.FailureCodes;
import com.npdev.kernel.errors.FailureInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record FlowInstance(
        String executionId,
        String flowName,
        String correlationId,
        String tenantId,
        String actorId,
        int currentStepIndex,
        FlowInstanceStatus status,
        Map<String, Object> state,
        String waitingForEventName,
        long createdAtEpochMs,
        long updatedAtEpochMs,
        int resumeAttemptCount,
        Long lastResumeAtEpochMs,
        String lastResumeErrorCode,
        Long nextEligibleResumeAtEpochMs,
        Long lastProgressAtEpochMs,
        String lastErrorKind,
        String lastErrorCode,
        String lastErrorMessage,
        Long failedAtEpochMs
) {
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1024;

    public FlowInstance {
        executionId = requireNonBlank(executionId, "executionId");
        flowName = requireNonBlank(flowName, "flowName");
        correlationId = requireNonBlank(correlationId, "correlationId");
        tenantId = normalizeOptional(tenantId);
        actorId = normalizeOptional(actorId);
        if (currentStepIndex < 0) {
            throw new IllegalArgumentException("currentStepIndex must be >= 0");
        }
        status = Objects.requireNonNull(status, "status");
        state = state == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(state));
        if (status == FlowInstanceStatus.WAITING_EVENT) {
            waitingForEventName = requireNonBlank(waitingForEventName, "waitingForEventName");
        } else {
            waitingForEventName = normalizeOptional(waitingForEventName);
        }
        if (createdAtEpochMs <= 0) {
            throw new IllegalArgumentException("createdAtEpochMs must be > 0");
        }
        if (updatedAtEpochMs <= 0) {
            throw new IllegalArgumentException("updatedAtEpochMs must be > 0");
        }
        if (resumeAttemptCount < 0) {
            throw new IllegalArgumentException("resumeAttemptCount must be >= 0");
        }
        if (lastResumeAtEpochMs != null && lastResumeAtEpochMs <= 0) {
            lastResumeAtEpochMs = null;
        }
        lastResumeErrorCode = normalizeOptional(lastResumeErrorCode);
        if (nextEligibleResumeAtEpochMs != null && nextEligibleResumeAtEpochMs <= 0) {
            nextEligibleResumeAtEpochMs = null;
        }
        if (status != FlowInstanceStatus.WAITING_EVENT) {
            nextEligibleResumeAtEpochMs = null;
        }
        if (lastProgressAtEpochMs == null || lastProgressAtEpochMs <= 0) {
            lastProgressAtEpochMs = updatedAtEpochMs;
        }
        lastErrorKind = normalizeOptional(lastErrorKind);
        lastErrorCode = normalizeOptional(lastErrorCode);
        lastErrorMessage = sanitizeErrorMessage(lastErrorMessage);
        if (failedAtEpochMs != null && failedAtEpochMs <= 0) {
            failedAtEpochMs = null;
        }
    }

    public static FlowInstance start(
            String executionId,
            String flowName,
            String correlationId,
            Map<String, Object> state,
            long nowEpochMs
    ) {
        return start(executionId, flowName, correlationId, null, null, state, nowEpochMs);
    }

    public static FlowInstance start(
            String executionId,
            String flowName,
            String correlationId,
            String tenantId,
            String actorId,
            Map<String, Object> state,
            long nowEpochMs
    ) {
        return new FlowInstance(
                executionId,
                flowName,
                correlationId,
                tenantId,
                actorId,
                0,
                FlowInstanceStatus.RUNNING,
                state,
                null,
                nowEpochMs,
                nowEpochMs,
                0,
                null,
                null,
                null,
                nowEpochMs,
                null,
                null,
                null,
                null
        );
    }

    public FlowInstance markRunning(int nextStepIndex, Map<String, Object> nextState, long nowEpochMs) {
        return new FlowInstance(
                executionId,
                flowName,
                correlationId,
                tenantId,
                actorId,
                Math.max(nextStepIndex, 0),
                FlowInstanceStatus.RUNNING,
                nextState,
                null,
                createdAtEpochMs,
                nowEpochMs,
                0,
                null,
                null,
                null,
                nowEpochMs,
                null,
                null,
                null,
                null
        );
    }

    public FlowInstance markWaiting(int stepIndex, String eventName, Map<String, Object> nextState, long nowEpochMs) {
        Long nextEligible = nextEligibleResumeAtEpochMs == null ? nowEpochMs : nextEligibleResumeAtEpochMs;
        return new FlowInstance(
                executionId,
                flowName,
                correlationId,
                tenantId,
                actorId,
                Math.max(stepIndex, 0),
                FlowInstanceStatus.WAITING_EVENT,
                nextState,
                requireNonBlank(eventName, "waitingForEventName"),
                createdAtEpochMs,
                nowEpochMs,
                resumeAttemptCount,
                lastResumeAtEpochMs,
                lastResumeErrorCode,
                nextEligible,
                lastProgressAtEpochMs,
                lastErrorKind,
                lastErrorCode,
                lastErrorMessage,
                failedAtEpochMs
        );
    }

    public FlowInstance markResumeFailure(
            String errorCode,
            long nowEpochMs,
            long nextEligibleEpochMs,
            int maxAttempts
    ) {
        int attempts = resumeAttemptCount + 1;
        boolean exhausted = maxAttempts > 0 && attempts >= maxAttempts;
        FlowInstanceStatus nextStatus = exhausted ? FlowInstanceStatus.STUCK : FlowInstanceStatus.WAITING_EVENT;
        String stableErrorCode = exhausted
                ? FailureCodes.RESUME_ATTEMPT_CAP
                : normalizeOptional(errorCode);
        Long failedAt = exhausted ? Long.valueOf(nowEpochMs) : failedAtEpochMs;
        return new FlowInstance(
                executionId,
                flowName,
                correlationId,
                tenantId,
                actorId,
                currentStepIndex,
                nextStatus,
                state,
                waitingForEventName,
                createdAtEpochMs,
                nowEpochMs,
                attempts,
                nowEpochMs,
                stableErrorCode,
                exhausted ? null : nextEligibleEpochMs,
                lastProgressAtEpochMs,
                exhausted ? "SYSTEM" : lastErrorKind,
                exhausted ? FailureCodes.RESUME_ATTEMPT_CAP : lastErrorCode,
                exhausted ? "Resume attempt cap reached" : lastErrorMessage,
                failedAt
        );
    }

    public FlowInstance markCompleted(Map<String, Object> nextState, long nowEpochMs) {
        return new FlowInstance(
                executionId,
                flowName,
                correlationId,
                tenantId,
                actorId,
                currentStepIndex,
                FlowInstanceStatus.COMPLETED,
                nextState,
                null,
                createdAtEpochMs,
                nowEpochMs,
                0,
                null,
                null,
                null,
                nowEpochMs,
                null,
                null,
                null,
                null
        );
    }

    public FlowInstance markFailed(Map<String, Object> nextState, long nowEpochMs) {
        return markFailed(nextState, nowEpochMs, null);
    }

    public FlowInstance markFailed(
            Map<String, Object> nextState,
            long nowEpochMs,
            FailureInfo failureInfo
    ) {
        String nextErrorKind = failureInfo == null || failureInfo.kind() == null
                ? lastErrorKind
                : failureInfo.kind().name();
        String nextErrorCode = failureInfo == null ? lastErrorCode : failureInfo.code();
        String nextErrorMessage = failureInfo == null ? lastErrorMessage : failureInfo.message();
        Long nextFailedAt = failureInfo == null ? failedAtEpochMs : nowEpochMs;
        return new FlowInstance(
                executionId,
                flowName,
                correlationId,
                tenantId,
                actorId,
                currentStepIndex,
                FlowInstanceStatus.FAILED,
                nextState,
                waitingForEventName,
                createdAtEpochMs,
                nowEpochMs,
                resumeAttemptCount,
                lastResumeAtEpochMs,
                lastResumeErrorCode,
                null,
                lastProgressAtEpochMs,
                nextErrorKind,
                nextErrorCode,
                nextErrorMessage,
                nextFailedAt
        );
    }

    public FlowInstance markFailedPermanent(
            Map<String, Object> nextState,
            long nowEpochMs,
            FailureInfo failureInfo
    ) {
        FailureInfo safeFailure = failureInfo == null
                ? FailureInfo.of(
                com.npdev.kernel.errors.ErrorKind.SYSTEM,
                FailureCodes.SYSTEM_EXCEPTION,
                "Execution failed permanently")
                : failureInfo;
        return new FlowInstance(
                executionId,
                flowName,
                correlationId,
                tenantId,
                actorId,
                currentStepIndex,
                FlowInstanceStatus.FAILED_PERMANENT,
                nextState,
                waitingForEventName,
                createdAtEpochMs,
                nowEpochMs,
                resumeAttemptCount,
                lastResumeAtEpochMs,
                lastResumeErrorCode,
                null,
                lastProgressAtEpochMs,
                safeFailure.kind().name(),
                safeFailure.code(),
                safeFailure.message(),
                nowEpochMs
        );
    }

    public FlowInstance markStuck(
            Map<String, Object> nextState,
            long nowEpochMs,
            FailureInfo failureInfo
    ) {
        FailureInfo safeFailure = failureInfo == null
                ? FailureInfo.of(
                com.npdev.kernel.errors.ErrorKind.SYSTEM,
                FailureCodes.RESUME_ATTEMPT_CAP,
                "Execution became stuck")
                : failureInfo;
        return new FlowInstance(
                executionId,
                flowName,
                correlationId,
                tenantId,
                actorId,
                currentStepIndex,
                FlowInstanceStatus.STUCK,
                nextState,
                waitingForEventName,
                createdAtEpochMs,
                nowEpochMs,
                resumeAttemptCount,
                lastResumeAtEpochMs,
                lastResumeErrorCode,
                null,
                lastProgressAtEpochMs,
                safeFailure.kind().name(),
                safeFailure.code(),
                safeFailure.message(),
                nowEpochMs
        );
    }

    public boolean isResumeEligible(long nowEpochMs) {
        if (status != FlowInstanceStatus.WAITING_EVENT) {
            return false;
        }
        if (nextEligibleResumeAtEpochMs == null) {
            return true;
        }
        return nextEligibleResumeAtEpochMs <= nowEpochMs;
    }

    public FlowInstance(
            String executionId,
            String flowName,
            String correlationId,
            int currentStepIndex,
            FlowInstanceStatus status,
            Map<String, Object> state,
            String waitingForEventName,
            long createdAtEpochMs,
            long updatedAtEpochMs
    ) {
        this(
                executionId,
                flowName,
                correlationId,
                null,
                null,
                currentStepIndex,
                status,
                state,
                waitingForEventName,
                createdAtEpochMs,
                updatedAtEpochMs,
                0,
                null,
                null,
                null,
                updatedAtEpochMs,
                null,
                null,
                null,
                null
        );
    }

    public FlowInstance(
            String executionId,
            String flowName,
            String correlationId,
            String tenantId,
            String actorId,
            int currentStepIndex,
            FlowInstanceStatus status,
            Map<String, Object> state,
            String waitingForEventName,
            long createdAtEpochMs,
            long updatedAtEpochMs
    ) {
        this(
                executionId,
                flowName,
                correlationId,
                tenantId,
                actorId,
                currentStepIndex,
                status,
                state,
                waitingForEventName,
                createdAtEpochMs,
                updatedAtEpochMs,
                0,
                null,
                null,
                null,
                updatedAtEpochMs,
                null,
                null,
                null,
                null
        );
    }

    public FlowInstance(
            String executionId,
            String flowName,
            String correlationId,
            String tenantId,
            String actorId,
            int currentStepIndex,
            FlowInstanceStatus status,
            Map<String, Object> state,
            String waitingForEventName,
            long createdAtEpochMs,
            long updatedAtEpochMs,
            int resumeAttemptCount,
            Long lastResumeAtEpochMs,
            String lastResumeErrorCode,
            Long nextEligibleResumeAtEpochMs,
            Long lastProgressAtEpochMs
    ) {
        this(
                executionId,
                flowName,
                correlationId,
                tenantId,
                actorId,
                currentStepIndex,
                status,
                state,
                waitingForEventName,
                createdAtEpochMs,
                updatedAtEpochMs,
                resumeAttemptCount,
                lastResumeAtEpochMs,
                lastResumeErrorCode,
                nextEligibleResumeAtEpochMs,
                lastProgressAtEpochMs,
                null,
                null,
                null,
                null
        );
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must be non-blank");
        }
        return value;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String sanitizeErrorMessage(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}
