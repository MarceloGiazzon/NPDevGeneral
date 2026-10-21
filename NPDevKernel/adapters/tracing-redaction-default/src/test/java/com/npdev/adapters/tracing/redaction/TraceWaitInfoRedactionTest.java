package com.npdev.adapters.tracing.redaction;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** FLOW-1: the wait/attempt counters written by FlowTraceMerge must survive redaction for an ordinary viewer. */
class TraceWaitInfoRedactionTest {

    @Test
    void waitAndAttemptCountersReachANonDebugViewer() {
        FlowTrace trace = new FlowTrace(
                new FlowTraceMeta("exec-1", "corr-1", "ApproveInvoice", "tenant-1", "actor-1", Map.of()),
                1000L, 9000L, StepOutcome.OK,
                List.of(new StepTrace(1, "wait-approval", "AWAIT_EVENT", 8000L, 8010L, StepOutcome.OK,
                        Map.of("waitedMs", 6700L, "waitStartedAtEpochMs", 1300L, "stepAttempts", 2L,
                                "customerEmail", "ana@acme.com"),
                        List.of(), null)));
        ExecutionContext viewer = ExecutionContext.anonymous().withRoles(Set.of("USER"));

        Map<String, Object> info = new DefaultTraceRedactionPolicy().redact(trace, viewer).steps().get(0).info();

        assertEquals(6700L, info.get("waitedMs"));
        assertEquals(1300L, info.get("waitStartedAtEpochMs"));
        assertEquals(2L, info.get("stepAttempts"));
        assertFalse(info.containsKey("customerEmail"), "everything else stays off the allowlist");
    }
}
