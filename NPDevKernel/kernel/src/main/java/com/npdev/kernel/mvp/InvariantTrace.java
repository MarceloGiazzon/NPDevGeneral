package com.npdev.kernel.mvp;

public record InvariantTrace(
        String invariantRef,
        String stepId,
        boolean passed,
        String message
) {
    public InvariantTrace {
        if (invariantRef == null || invariantRef.isBlank()) {
            throw new IllegalArgumentException("invariantRef must be non-blank");
        }
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId must be non-blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must be non-blank");
        }
    }
}
