package com.npdev.adapters.tracing.inproc;

import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcExecutionTracerTest {

    @Test
    void storesSignalsAndFinalFlowTraceByExecutionId() {
        InProcExecutionTracer tracer = new InProcExecutionTracer();
        FlowTraceMeta meta = new FlowTraceMeta(
                "exec-1",
                "corr-1",
                "CreateUser",
                Map.of("traceId", "exec-1")
        );

        long started = System.currentTimeMillis();
        tracer.onFlowStart(meta, started);
        tracer.onStepStart(meta, 0, "save-user", "CAPABILITY_CALL", started + 1);
        tracer.onStepEnd(meta, new StepTrace(
                0,
                "save-user",
                "CAPABILITY_CALL",
                started + 1,
                started + 2,
                StepOutcome.OK,
                Map.of("writtenStateKeys", List.of("saved")),
                List.of(),
                null
        ));
        FlowTrace finalTrace = new FlowTrace(
                meta,
                started,
                started + 3,
                StepOutcome.OK,
                List.of(new StepTrace(
                        0,
                        "save-user",
                        "CAPABILITY_CALL",
                        started + 1,
                        started + 2,
                        StepOutcome.OK,
                        Map.of("writtenStateKeys", List.of("saved")),
                        List.of(),
                        null
                ))
        );
        tracer.onFlowEnd(finalTrace);

        assertEquals(4, tracer.getSignalCount("exec-1"));
        assertEquals(4, tracer.getSignals("exec-1").size());
        assertEquals("FlowStart", tracer.getSignals("exec-1").get(0).type());
        assertEquals("FlowEnd", tracer.getSignals("exec-1").get(3).type());

        FlowTrace stored = tracer.getFlowTrace("exec-1");
        assertNotNull(stored);
        assertEquals("CreateUser", stored.meta().flowName());
        assertEquals(StepOutcome.OK, stored.outcome());
        assertEquals(1, stored.steps().size());
        assertTrue(stored.steps().get(0).info().containsKey("writtenStateKeys"));
    }

    @Test
    void supportsTraceSearchByCorrelationFlowStatusAndTimeRange() {
        InProcExecutionTracer tracer = new InProcExecutionTracer();
        long now = System.currentTimeMillis();

        FlowTrace okTrace = new FlowTrace(
                new FlowTraceMeta("exec-ok", "corr-1", "CreateUser", "tenant-a", "actor-a", Map.of()),
                now - 1000,
                now - 900,
                StepOutcome.OK,
                List.of(new StepTrace(
                        0,
                        "return",
                        "RETURN",
                        now - 1000,
                        now - 900,
                        StepOutcome.OK,
                        Map.of(),
                        List.of(),
                        null
                ))
        );
        FlowTrace waitingTrace = new FlowTrace(
                new FlowTraceMeta("exec-wait", "corr-1", "FinalizeInvoice", "tenant-b", "actor-b", Map.of()),
                now - 800,
                now - 700,
                StepOutcome.FAILED,
                List.of(new StepTrace(
                        0,
                        "await",
                        "AWAIT_EVENT",
                        now - 800,
                        now - 700,
                        StepOutcome.FAILED,
                        Map.of("awaitedEventStatus", "WAITING"),
                        List.of(),
                        null
                ))
        );

        tracer.save(okTrace);
        tracer.save(waitingTrace);

        List<FlowTrace> corrSearch = tracer.findByCorrelationId("corr-1", 10, 0);
        assertEquals(List.of("exec-wait", "exec-ok"),
                corrSearch.stream().map(t -> t.meta().executionId()).toList());

        List<FlowTrace> waitingSearch = tracer.search(
                new com.npdev.kernel.ports.TraceQuery("corr-1", null, "WAITING", null, null, 10, 0)
        );
        assertEquals(1, waitingSearch.size());
        assertEquals("exec-wait", waitingSearch.get(0).meta().executionId());

        List<FlowTrace> flowSearch = tracer.findByFlowName("CreateUser", 10, 0);
        assertEquals(1, flowSearch.size());
        assertEquals("exec-ok", flowSearch.get(0).meta().executionId());

        List<FlowTrace> tenantSearch = tracer.search(
                new com.npdev.kernel.ports.TraceQuery(null, null, null, null, null, 10, 0, "tenant-a", null)
        );
        assertEquals(1, tenantSearch.size());
        assertEquals("exec-ok", tenantSearch.get(0).meta().executionId());
    }
}
