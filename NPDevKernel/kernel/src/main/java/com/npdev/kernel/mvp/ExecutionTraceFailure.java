package com.npdev.kernel.mvp;

public record ExecutionTraceFailure(
        String code,
        String message,
        String stepId
) {
    public ExecutionTraceFailure {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must be non-blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must be non-blank");
        }
    }
}
