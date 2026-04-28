package com.npdev.kernel.ports;

import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

public interface ExecutionTracer {

    ExecutionTracer NOOP = new ExecutionTracer() {
    };

    default void onFlowStart(FlowTraceMeta meta, long startedAtEpochMs) {
    }

    default void onStepStart(
            FlowTraceMeta meta,
            int stepIndex,
            String stepName,
            String stepType,
            long startedAtEpochMs
    ) {
    }

    default void onStepEnd(FlowTraceMeta meta, StepTrace stepTrace) {
    }

    default void onFlowEnd(FlowTrace flowTrace) {
    }
}
