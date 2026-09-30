package com.finalexec.config;

import com.finalexec.tracing.TracingPackBridge;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.TraceQuery;
import com.npdev.kernel.ports.TraceStore;
import com.npdev.kernel.ports.TraceSummaryStore;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-memory storage mode's TraceStore used to be {@code TraceStore.noop()}, so every trace the
 * tracer recorded was unreadable and {@code GET /api/v1/traces/{executionId}} 404'd for every
 * in-memory-mode app (AsyncWaitResumeE2EIT in CI, 2026-09-30: a COMPLETED execution's trace 404'd).
 * The store is now a view over the same recorder the tracer writes to.
 */
class NpdevRuntimeModeConfigInMemoryTraceStoreTest {

    private final NpdevRuntimeModeConfig config = new NpdevRuntimeModeConfig();
    private final ObjectProvider<TracingPackBridge> noBridge =
            new StaticListableBeanFactory().getBeanProvider(TracingPackBridge.class);

    @Test
    void aTraceTheTracerRecordsIsReadableThroughTheTraceStore() {
        NpdevRuntimeModeConfig.InProcTraceRecorder recorder = config.inProcTraceRecorder();
        ExecutionTracer tracer = config.inProcExecutionTracer(recorder, noBridge);
        TraceStore store = config.inProcTraceStore(recorder);

        FlowTrace trace = new FlowTrace(
                new FlowTraceMeta("exec-1", "corr-1", "TypedHappyPath", Map.of()),
                1_000L, 2_000L, StepOutcome.OK, List.of());
        tracer.onFlowEnd(trace);

        Optional<FlowTrace> found = store.findByExecutionId("exec-1");
        assertThat(found).contains(trace);
        assertThat(store.search(new TraceQuery("corr-1", null, null, null, null, 10, 0))).containsExactly(trace);
        assertThat(store.findByExecutionId("unknown")).isEmpty();
    }

    @Test
    void theTraceStoreViewAddsNoNewTracerOrSummaryCandidate() {
        // REG-229 / R1c: one object under two type-assignable beans breaks by-type injection. The
        // view must be a TraceStore and nothing else.
        TraceStore store = config.inProcTraceStore(config.inProcTraceRecorder());

        assertThat(store).isNotInstanceOf(ExecutionTracer.class);
        assertThat(store).isNotInstanceOf(TraceSummaryStore.class);
    }
}
