package com.npdev.kernel.mvp;

public record StepExecutionTrace(
        String stepId,
        String stepType,
        String inputSummary,
        String outputSummary,
        StepExecutionStatus status
) {
    public StepExecutionTrace {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId must be non-blank");
        }
        if (stepType == null || stepType.isBlank()) {
            throw new IllegalArgumentException("stepType must be non-blank");
        }
        if (inputSummary == null) {
            inputSummary = "null";
        }
        if (outputSummary == null) {
            outputSummary = "null";
        }
        if (status == null) {
            throw new IllegalArgumentException("status must be non-null");
        }
    }
}
