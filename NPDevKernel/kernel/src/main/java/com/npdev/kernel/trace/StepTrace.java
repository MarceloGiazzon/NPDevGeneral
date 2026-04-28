package com.npdev.kernel.trace;

import com.npdev.kernel.CapabilityError;
import com.npdev.kernel.ports.InvariantEngine;

import java.util.List;
import java.util.Map;

public record StepTrace(
        int stepIndex,
        String stepName,
        String stepType,
        long startedAtEpochMs,
        long endedAtEpochMs,
        StepOutcome outcome,
        Map<String, Object> info,
        List<InvariantEngine.Violation> invariantViolations,
        CapabilityError capabilityError
) {
    public StepTrace {
        if (stepIndex < 0) {
            throw new IllegalArgumentException("stepIndex must be >= 0");
        }
        if (stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException("stepName must be non-blank");
        }
        if (stepType == null || stepType.isBlank()) {
            throw new IllegalArgumentException("stepType must be non-blank");
        }
        if (startedAtEpochMs <= 0) {
            throw new IllegalArgumentException("startedAtEpochMs must be > 0");
        }
        if (endedAtEpochMs < startedAtEpochMs) {
            throw new IllegalArgumentException("endedAtEpochMs must be >= startedAtEpochMs");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must be non-null");
        }
        info = info == null ? Map.of() : Map.copyOf(info);
        invariantViolations = invariantViolations == null ? List.of() : List.copyOf(invariantViolations);
    }
}
