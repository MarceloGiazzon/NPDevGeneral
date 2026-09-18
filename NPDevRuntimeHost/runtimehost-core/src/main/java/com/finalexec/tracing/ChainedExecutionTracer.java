package com.finalexec.tracing;

import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

/**
 * S4 (Tracing pack): composes the storage-mode {@link ExecutionTracer} with the optional
 * {@link TracingPackBridge} into a single bean, so the kernel binder's one
 * {@code ExecutionTracer} injection point stays unambiguous.
 *
 * <p>The bridge is best-effort: when both the primary tracer and the bridge are registered, the
 * chain forwards every lifecycle callback to the primary and additionally pushes {@code onFlowEnd}
 * into the bridge (which writes the trace_entries business row). When the bridge is absent (no
 * tracing pack / {@code npdev.tracing.pack-bridge.enabled=false}), the chain IS the primary tracer
 * -- zero behaviour difference from pre-S4 wiring.</p>
 */
public final class ChainedExecutionTracer implements ExecutionTracer {

    private final ExecutionTracer primary;
    private final TracingPackBridge bridge;

    public ChainedExecutionTracer(ExecutionTracer primary, TracingPackBridge bridge) {
        this.primary = primary;
        this.bridge = bridge;
    }

    @Override
    public void onFlowStart(FlowTraceMeta meta, long startedAtEpochMs) {
        primary.onFlowStart(meta, startedAtEpochMs);
    }

    @Override
    public void onStepStart(
            FlowTraceMeta meta,
            int stepIndex,
            String stepName,
            String stepType,
            long startedAtEpochMs
    ) {
        primary.onStepStart(meta, stepIndex, stepName, stepType, startedAtEpochMs);
    }

    @Override
    public void onStepEnd(FlowTraceMeta meta, StepTrace stepTrace) {
        primary.onStepEnd(meta, stepTrace);
    }

    @Override
    public void onFlowEnd(FlowTrace flowTrace) {
        primary.onFlowEnd(flowTrace);
        if (bridge != null) {
            bridge.onFlowEnd(flowTrace);
        }
    }
}