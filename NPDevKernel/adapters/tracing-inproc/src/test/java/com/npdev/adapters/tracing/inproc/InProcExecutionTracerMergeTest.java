package com.npdev.adapters.tracing.inproc;

import com.npdev.kernel.ports.TraceQuery;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import com.npdev.kernel.trace.TraceSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FLOW-1: the in-memory trace store (the default storage mode) merges a resumed segment into the stored trace. */
class InProcExecutionTracerMergeTest {

    private static StepTrace step(int index, String name, long start, long end, StepOutcome outcome, Map<String, Object> info) {
        return new StepTrace(index, name, "CAPABILITY_CALL", start, end, outcome, info, List.of(), null);
    }

    private static FlowTrace segment(long start, long end, StepOutcome outcome, Map<String, Object> tags, StepTrace... steps) {
        return new FlowTrace(new FlowTraceMeta("exec-1", "corr-1", "ApproveInvoice", "tenant-a", "actor", tags),
                start, end, outcome, List.of(steps));
    }

    @Test
    void resumedSegmentIsMergedAndStatusFollowsTheMergedTrace() {
        InProcExecutionTracer tracer = new InProcExecutionTracer();
        tracer.onFlowEnd(segment(1000, 1300, StepOutcome.FAILED, Map.of("startStepIndex", 0),
                step(0, "prepare", 1000, 1100, StepOutcome.OK, Map.of()),
                step(1, "wait-approval", 1200, 1300, StepOutcome.FAILED, Map.of("awaitedEventStatus", "WAITING"))));
        assertEquals("WAITING", tracer.searchSummaries(TraceQuery.empty()).get(0).outcome());

        tracer.onFlowEnd(segment(9000, 9100, StepOutcome.OK, Map.of("startStepIndex", 1, "resumed", true),
                step(1, "wait-approval", 9000, 9010, StepOutcome.OK, Map.of()),
                step(2, "ship", 9010, 9100, StepOutcome.OK, Map.of())));

        FlowTrace merged = tracer.findByExecutionId("exec-1").orElseThrow();
        assertEquals(List.of("prepare", "wait-approval", "ship"), merged.steps().stream().map(StepTrace::stepName).toList());
        assertEquals(1000L, merged.startedAtEpochMs());
        assertEquals(7700L, ((Number) merged.steps().get(1).info().get("waitedMs")).longValue());
        TraceSummary summary = tracer.searchSummaries(TraceQuery.empty()).get(0);
        assertEquals("OK", summary.outcome(), "the resolved WAITING marker is gone once the wait completes");
        assertEquals(1, tracer.snapshotTraces().size(), "one trace per execution, never one per segment");
        // The time window filters on the ORIGINAL start, so the resumed flow is still found by it.
        assertTrue(tracer.search(new TraceQuery(null, null, null, 900L, 1100L, 10, 0)).size() == 1);
        assertTrue(tracer.search(new TraceQuery(null, null, null, 9000L, null, 10, 0)).isEmpty());
    }

    @Test
    void aNewRunUnderTheSameExecutionIdStillReplaces() {
        InProcExecutionTracer tracer = new InProcExecutionTracer();
        tracer.save(segment(1000, 1100, StepOutcome.OK, Map.of("startStepIndex", 0),
                step(0, "a", 1000, 1100, StepOutcome.OK, Map.of())));
        tracer.save(segment(5000, 5200, StepOutcome.FAILED, Map.of(),
                step(0, "b", 5000, 5200, StepOutcome.FAILED, Map.of())));
        FlowTrace stored = tracer.findByExecutionId("exec-1").orElseThrow();
        assertEquals(List.of("b"), stored.steps().stream().map(StepTrace::stepName).toList());
        assertEquals(5000L, stored.startedAtEpochMs());
    }
}
