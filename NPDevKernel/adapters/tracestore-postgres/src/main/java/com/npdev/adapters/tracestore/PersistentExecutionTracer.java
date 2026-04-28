package com.npdev.adapters.tracestore;

import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.TraceStore;
import com.npdev.kernel.trace.FlowTrace;

import java.util.Objects;

/**
 * Bridge adapter:
 * kernel emits execution traces via ExecutionTracer, and this adapter persists
 * finished traces using TraceStore.
 */
public final class PersistentExecutionTracer implements ExecutionTracer {
    private final TraceStore traceStore;

    public PersistentExecutionTracer(TraceStore traceStore) {
        this.traceStore = Objects.requireNonNull(traceStore, "traceStore");
    }

    @Override
    public void onFlowEnd(FlowTrace flowTrace) {
        if (flowTrace == null) {
            return;
        }
        traceStore.save(flowTrace);
    }
}
