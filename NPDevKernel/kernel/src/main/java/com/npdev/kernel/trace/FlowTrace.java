package com.npdev.kernel.trace;

import java.util.List;

public record FlowTrace(
        FlowTraceMeta meta,
        long startedAtEpochMs,
        long endedAtEpochMs,
        StepOutcome outcome,
        List<StepTrace> steps
) {
    public FlowTrace {
        if (meta == null) {
            throw new IllegalArgumentException("meta must be non-null");
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
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
