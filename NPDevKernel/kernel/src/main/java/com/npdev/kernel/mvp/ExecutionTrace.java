package com.npdev.kernel.mvp;

import com.npdev.kernel.events.EventEnvelope;

import java.util.List;

public record ExecutionTrace(
        String flowName,
        List<StepExecutionTrace> steps,
        List<EventEnvelope> emittedEvents,
        List<InvariantTrace> invariantChecks,
        ExecutionTraceStatus status,
        ExecutionTraceFailure failure
) {
    public ExecutionTrace {
        if (flowName == null || flowName.isBlank()) {
            throw new IllegalArgumentException("flowName must be non-blank");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
        emittedEvents = emittedEvents == null ? List.of() : List.copyOf(emittedEvents);
        invariantChecks = invariantChecks == null ? List.of() : List.copyOf(invariantChecks);
        if (status == null) {
            throw new IllegalArgumentException("status must be non-null");
        }
    }
}
