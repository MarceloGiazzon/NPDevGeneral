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

    /**
     * R2.1: zero the resume streak WITHOUT touching status, state or timestamps. {@code
     * resumeAttemptCount} carries two different kinds of tally -- quiet "no event yet" misses,
     * which ResumeCoordinator deliberately never lets exhaust the cap, and real resume failures,
     * which must still end in STUCK. Since a quiet wait may legitimately run for weeks, the count
     * can sit far above any cap by the time something genuinely throws; the caller resets the
     * streak at that changeover so the real failure gets its full retry budget rather than an
     * instant, unrecoverable STUCK on the very first fault.
     */
    public FlowInstance withResumeStreakReset() {
        if (resumeAttemptCount == 0) {
            return this;
        }
        return new FlowInstance(
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
                lastResumeAtEpochMs,
                lastResumeErrorCode,
                nextEligibleResumeAtEpochMs,
                lastProgressAtEpochMs,
                lastErrorKind,
                lastErrorCode,
                lastErrorMessage,
                failedAtEpochMs
        );
    }

    /**
     * R2.2: the only coded transition OUT of {@link FlowInstanceStatus#STUCK}. Until this existed
     * the sole recovery was an UPDATE against {@code npdev_flow_instance} by hand, because nothing
     * in the engine ever moves an instance out of STUCK -- {@code ResumeCoordinator} only ever
     * sweeps WAITING_EVENT rows, so a stuck instance cannot self-heal.
     *
     * <p>Three field changes, and each one is load-bearing for the next sweep picking the instance
     * up: status back to WAITING_EVENT (what {@code findAllWaiting}/{@code
     * claimWaitingEligibleToResume} select on), {@code resumeAttemptCount} to 0 (otherwise the very
     * next real fault re-exhausts the cap immediately -- the same reasoning as {@link
     * #withResumeStreakReset}), and {@code nextEligibleResumeAtEpochMs} to null, which {@link
     * #isResumeEligible} reads as "eligible now" rather than making an operator who just clicked
     * un-stick wait out a 300s backoff window.
     *
     * <p>The terminal-failure quartet ({@code lastErrorKind}/{@code lastErrorCode}/{@code
     * lastErrorMessage}/{@code failedAtEpochMs}) is cleared because it describes a STUCK status the
     * instance no longer has, and {@code ExecutionSummary} exposes {@code failedAtEpochMs} to
     * monitors that would otherwise read a live waiter as having failed at some past instant. The
     * resume pair ({@code lastResumeAtEpochMs}/{@code lastResumeErrorCode}) is deliberately KEPT --
     * after this call it is the only surviving evidence on the row of why the instance got stuck.
     *
     * <p>R2.1 made STUCK mean something sharper: a quiet wait no longer counts toward the cap, so
     * every instance that reaches STUCK is one whose resume genuinely kept throwing. Un-sticking
     * therefore says "I believe the underlying fault is fixed", which is why the REST surface over
     * this is SUPERUSER-gated rather than a routine read.
     *
     * @throws IllegalStateException if the instance is not STUCK, or is STUCK without a
     *         {@code waitingForEventName} -- STUCK is also reachable from RUNNING (see {@code
     *         KernelRunner.resolveFailureTerminalStatus}), and such a row has no event to wait on,
     *         so WAITING_EVENT is not a state it can legally hold. Retry that one with a resume.
     */
    public FlowInstance withUnstuck(long nowEpochMs) {
        if (status != FlowInstanceStatus.STUCK) {
            throw new IllegalStateException(
                    "Only a STUCK execution can be un-stuck; " + executionId + " is " + status);
        }
        if (waitingForEventName == null || waitingForEventName.isBlank()) {
            throw new IllegalStateException(
                    "Execution " + executionId + " became stuck without an awaited event, so it "
                            + "cannot return to WAITING_EVENT; resume it instead");
        }
        return new FlowInstance(
                executionId,
                flowName,
                correlationId,
                tenantId,
                actorId,
                currentStepIndex,
                FlowInstanceStatus.WAITING_EVENT,
                state,
                waitingForEventName,
                createdAtEpochMs,
                nowEpochMs,
                0,
                lastResumeAtEpochMs,
                lastResumeErrorCode,
                null,
                lastProgressAtEpochMs,
                null,
                null,
                null,
                null
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
