package com.npdev.cli.runtime;

import com.npdev.adapters.circuit.inproc.InProcCircuitBreakerStateStore;
import com.npdev.adapters.events.inproc.InProcEventStore;
import com.npdev.adapters.flowinstance.inproc.InProcCorrelationOwnershipStore;
import com.npdev.adapters.flowinstance.inproc.InProcFlowInstanceStore;
import com.npdev.adapters.idempotency.inproc.InProcIdempotencyStore;
import com.npdev.adapters.tracing.inproc.InProcExecutionTracer;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExecutionDebugReportBuilderTest {

    @Test
    void shouldBuildDebugReportFromExecutionAndCorrelationArtifacts() {
        InProcEventStore eventStore = new InProcEventStore();
        InProcFlowInstanceStore flowInstanceStore = new InProcFlowInstanceStore();
        InProcExecutionTracer traceStore = new InProcExecutionTracer();

        FlowInstance execution = FlowInstance.start(
                "exec-1",
                "CreateUserFlow",
                "corr-1",
                "default",
                "tester",
                Map.of("email", "ana@example.com"),
                1000L
        ).markWaiting(1, "UserApproved", Map.of("email", "ana@example.com"), 1500L);
        flowInstanceStore.save(execution);

        FlowTrace trace = new FlowTrace(
                new FlowTraceMeta("exec-1", "corr-1", "CreateUserFlow", "default", "tester", Map.of()),
                1000L,
                1500L,
                StepOutcome.FAILED,
                List.of(
                        new StepTrace(0, "validate", "INVARIANT_CHECK", 1000L, 1100L, StepOutcome.OK, Map.of(), List.of(), null),
                        new StepTrace(1, "awaitApproval", "AWAIT_EVENT", 1100L, 1500L, StepOutcome.FAILED, Map.of("awaitedEventStatus", "WAITING"), List.of(), null)
                )
        );
        traceStore.save(trace);

        eventStore.append(new EventEnvelope(
                "evt-1",
                "UserRequested",
                1200L,
                Map.of("email", "ana@example.com"),
                "corr-1",
                "exec-1",
                "CreateUserFlow",
                0,
                "default",
                "tester"
        ));

        CliRuntime runtime = new CliRuntime(
                null,
                null,
                eventStore,
                flowInstanceStore,
                traceStore,
                new InProcCorrelationOwnershipStore(),
                new InProcCircuitBreakerStateStore(),
                new InProcIdempotencyStore()
        );

        ExecutionDebugReport report = new ExecutionDebugReportBuilder().build(runtime, "exec-1", 20);

        assertEquals("exec-1", report.executionId());
        assertNotNull(report.execution());
        assertNotNull(report.trace());
        assertEquals(1, report.correlationEvents().size());
        assertEquals(1, report.relatedExecutions().size());
        assertEquals("waiting_for_event:UserApproved", report.diagnosis());
    }
}
