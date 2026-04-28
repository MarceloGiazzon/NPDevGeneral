package com.npdev.kernel;

import com.npdev.kernel.inproc.InProcCapabilityInvoker;
import com.npdev.kernel.inproc.InProcEventBus;
import com.npdev.kernel.inproc.SimpleInvariantEngine;
import com.npdev.kernel.mvp.ExecutionTrace;
import com.npdev.kernel.mvp.ExecutionTraceStatus;
import com.npdev.kernel.mvp.MvpKernelRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MvpKernelRunnerTest {

    @Test
    void missingCapabilityBindingReturnsTypedFailure() {
        MvpKernelRunner runner = new MvpKernelRunner(
                new InProcEventBus(),
                new SimpleInvariantEngine(),
                new InProcCapabilityInvoker()
        );

        FlowDefinition flow = new FlowDefinition(
                "CreateUser",
                "User",
                List.of(FlowStepDefinition.capabilityCall(
                        "save",
                        "persistence",
                        "PersistenceCapability",
                        "save",
                        List.of("$input"),
                        "$saved"
                ))
        );

        ExecutionTrace trace = runner.run(flow, Map.of("email", "a@b.com"), ExecutionContext.anonymous());

        assertEquals(ExecutionTraceStatus.FAILURE, trace.status());
        assertNotNull(trace.failure());
        assertEquals("CAPABILITY_BINDING_MISSING", trace.failure().code());
        assertEquals("step-1-save", trace.failure().stepId());
        assertEquals(1, trace.steps().size());
        assertTrue(trace.steps().get(0).outputSummary().contains("missing adapter binding"));
    }

    @Test
    void invariantFailureStopsAtCorrectStep() {
        MvpKernelRunner runner = new MvpKernelRunner(
                new InProcEventBus(),
                new SimpleInvariantEngine(),
                new InProcCapabilityInvoker()
        );

        FlowDefinition flow = new FlowDefinition(
                "CreateUser",
                "User",
                List.of(
                        FlowStepDefinition.invariant(
                                "validate-user",
                                "User",
                                FlowStepDefinition.InvariantCheckpoint.PRE,
                                List.of("EmailRequired")
                        ),
                        FlowStepDefinition.emitEvent("emit-created", "UserCreated", "$input")
                )
        );

        Map<String, Object> input = Map.of(
                "name", "Ana",
                "__failInvariantRef", "EmailRequired",
                "__invariantFailureMessage", "Email is required"
        );
        ExecutionTrace trace = runner.run(flow, input, ExecutionContext.anonymous());

        assertEquals(ExecutionTraceStatus.FAILURE, trace.status());
        assertNotNull(trace.failure());
        assertEquals("INVARIANT_FAILED", trace.failure().code());
        assertEquals("step-1-validate-user", trace.failure().stepId());
        assertEquals(1, trace.steps().size());
        assertEquals(1, trace.invariantChecks().size());
        assertEquals("EmailRequired", trace.invariantChecks().get(0).invariantRef());
        assertTrue(!trace.invariantChecks().get(0).passed());
    }

    @Test
    void eventsAreEmittedInStableOrder() {
        InProcEventBus eventBus = new InProcEventBus();
        MvpKernelRunner runner = new MvpKernelRunner(
                eventBus,
                new SimpleInvariantEngine(),
                new InProcCapabilityInvoker()
        );

        FlowDefinition flow = new FlowDefinition(
                "IssueInvoice",
                "Invoice",
                List.of(
                        FlowStepDefinition.emitEvent("emit-draft", "InvoiceDrafted", "$input"),
                        FlowStepDefinition.emitEvent("emit-issued", "InvoiceIssued", "$input")
                )
        );

        ExecutionContext context = ExecutionContext.of("tenant-1", "actor-1").withTag("correlationId", "corr-100");
        ExecutionTrace first = runner.run(flow, Map.of("invoiceId", "inv-1"), context);
        ExecutionTrace second = runner.run(flow, Map.of("invoiceId", "inv-1"), context);

        assertEquals(ExecutionTraceStatus.SUCCESS, first.status());
        assertEquals(2, first.emittedEvents().size());
        assertEquals("InvoiceDrafted", first.emittedEvents().get(0).eventName());
        assertEquals("InvoiceIssued", first.emittedEvents().get(1).eventName());
        assertEquals(first.emittedEvents().get(0).eventId(), second.emittedEvents().get(0).eventId());
        assertEquals(first.emittedEvents().get(1).eventId(), second.emittedEvents().get(1).eventId());
    }

    @Test
    void mapStepCopiesValueIntoState() {
        MvpKernelRunner runner = new MvpKernelRunner(
                new InProcEventBus(),
                new SimpleInvariantEngine(),
                new InProcCapabilityInvoker()
        );

        FlowDefinition flow = new FlowDefinition(
                "MapInvoiceTotal",
                "Invoice",
                List.of(
                        FlowStepDefinition.map("copy-total", "$input.total", "$total"),
                        FlowStepDefinition.returnValue("return-total", "$total")
                )
        );

        ExecutionTrace trace = runner.run(flow, Map.of("total", 42), ExecutionContext.anonymous());
        assertEquals(ExecutionTraceStatus.SUCCESS, trace.status());
        assertEquals(2, trace.steps().size());
        assertEquals("42", trace.steps().get(1).outputSummary());
    }
}
