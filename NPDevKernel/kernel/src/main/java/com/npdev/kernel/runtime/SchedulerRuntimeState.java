package com.npdev.kernel.runtime;

public final class SchedulerRuntimeState {
    private volatile long lastTickAtEpochMs;
    private volatile String lastOutcome;
    private volatile String lastErrorCode;

    public SchedulerRuntimeState() {
        this.lastTickAtEpochMs = 0L;
        this.lastOutcome = "NOT_STARTED";
        this.lastErrorCode = null;
    }

    public long lastTickAtEpochMs() {
        return lastTickAtEpochMs;
    }

    public String lastOutcome() {
        return lastOutcome;
    }

    public String lastErrorCode() {
        return lastErrorCode;
    }

    public void markSuccess(long nowEpochMs) {
        this.lastTickAtEpochMs = nowEpochMs;
        this.lastOutcome = "SUCCESS";
        this.lastErrorCode = null;
    }

    public void markSkipped(long nowEpochMs, String reason) {
        this.lastTickAtEpochMs = nowEpochMs;
        this.lastOutcome = "SKIPPED";
        this.lastErrorCode = reason == null || reason.isBlank() ? null : reason.trim();
    }

    public void markFailure(long nowEpochMs, Throwable throwable) {
        this.lastTickAtEpochMs = nowEpochMs;
        this.lastOutcome = "FAILED";
        this.lastErrorCode = throwable == null ? "unknown" : throwable.getClass().getSimpleName();
    }
}