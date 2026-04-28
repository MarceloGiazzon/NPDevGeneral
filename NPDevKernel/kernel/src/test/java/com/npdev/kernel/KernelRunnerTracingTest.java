package com.npdev.kernel;

import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelRunnerTracingTest {

    @Test
    void happyFlowProducesCompleteStructuredTrace() {
        RecordingExecutionTracer tracer = new RecordingExecutionTracer();
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        InvariantEngine invariantEngine = (entityName, payload) -> List.of();

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(
                                FlowStepDefinition.capabilityCall(
                                        "save-user",
                                        "persistence",
                                        "PersistenceCapability",
                                        "inmemory",
                                        "save",
                                        List.of("$input"),
                                        "$saved"
                                ),
                                FlowStepDefinition.emitEvent("emit-created", "UserCreated", "$saved"),
                                FlowStepDefinition.returnValue("return-user", "$saved")
                        )
                ));

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                invariantEngine,
                flowProvider,
                (call, state) -> CapabilityResult.success(Map.of("id", "u-1", "email", "a@b.com")),
                tracer,
                eventInfrastructure
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "a@b.com", "correlationId", "corr-1"));

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertNotNull(result.getExecutionId());
        assertNotNull(result.getCorrelationId());
        assertNotNull(result.getTraceId());
        assertEquals("corr-1", result.getCorrelationId());
        assertEquals(result.getExecutionId(), result.getTraceId());

        assertNotNull(tracer.flowTrace);
        assertEquals(StepOutcome.OK, tracer.flowTrace.outcome());
        assertEquals(3, tracer.flowTrace.steps().size());
        assertEquals(8, tracer.signals.size());
        assertEquals(List.of(
                "FlowStart",
                "StepStart",
                "StepEnd",
                "StepStart",
                "StepEnd",
                "StepStart",
                "StepEnd",
                "FlowEnd"
        ), tracer.signals);

        StepTrace first = tracer.flowTrace.steps().get(0);
        StepTrace second = tracer.flowTrace.steps().get(1);
        StepTrace third = tracer.flowTrace.steps().get(2);

        assertEquals(0, first.stepIndex());
        assertEquals(1, second.stepIndex());
        assertEquals(2, third.stepIndex());
        assertEquals(StepOutcome.OK, first.outcome());
        assertEquals(StepOutcome.OK, second.outcome());
        assertEquals(StepOutcome.OK, third.outcome());
        assertTrue(first.endedAtEpochMs() >= first.startedAtEpochMs());
        assertTrue(second.endedAtEpochMs() >= second.startedAtEpochMs());
        assertTrue(third.endedAtEpochMs() >= third.startedAtEpochMs());
    }

    @Test
    void invariantFailureIsTracedWithStructuredViolation() {
        RecordingExecutionTracer tracer = new RecordingExecutionTracer();
        EventBus eventBus = event -> {
        };
        InvariantEngine invariantEngine = new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }

            @Override
            public InvariantEvaluationResult evaluate(InvariantEvaluationRequest request) {
                return new InvariantEvaluationResult(List.of(
                        new Violation(
                                "INVARIANT_FAIL",
                                "Invariant failed: EmailRequired",
                                "EmailRequired"
                        )
                ));
            }
        };

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(FlowStepDefinition.invariant(
                                "validate-user",
                                "User",
                                FlowStepDefinition.InvariantCheckpoint.PRE,
                                List.of("EmailRequired")
                        ))
                ));

        KernelRunner runner = new KernelRunner(
                eventBus,
                invariantEngine,
                flowProvider,
                (call, state) -> CapabilityResult.success(Map.of()),
                tracer
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("name", "Ana"));

        assertEquals(ExecutionStatus.INVARIANT_FAILED, result.getStatus());
        assertNotNull(result.getExecutionId());
        assertNotNull(result.getCorrelationId());

        assertNotNull(tracer.flowTrace);
        assertEquals(StepOutcome.FAILED, tracer.flowTrace.outcome());
        assertEquals(1, tracer.flowTrace.steps().size());

        StepTrace failureStep = tracer.flowTrace.steps().get(0);
        assertEquals(StepOutcome.FAILED, failureStep.outcome());
        assertFalse(failureStep.invariantViolations().isEmpty());
        InvariantEngine.Violation violation = failureStep.invariantViolations().get(0);
        assertEquals("EmailRequired", violation.invariantRef());
        assertEquals("CreateUser", violation.flowName());
        assertEquals("validate-user", violation.stepName());
        assertEquals("User", violation.conceptName());
        assertEquals(0, violation.stepIndex());
    }

    @Test
    void capabilityFailureIsTracedWithStructuredCapabilityError() {
        RecordingExecutionTracer tracer = new RecordingExecutionTracer();
        EventBus eventBus = event -> {
        };
        InvariantEngine invariantEngine = (entityName, payload) -> List.of();

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(FlowStepDefinition.capabilityCall(
                                "save-user",
                                "persistence",
                                "PersistenceCapability",
                                "inmemory",
                                "save",
                                List.of("$input"),
                                "$saved"
                        ))
                ));

        KernelRunner runner = new KernelRunner(
                eventBus,
                invariantEngine,
                flowProvider,
                (call, state) -> CapabilityResult.failure(
                        "CAPABILITY_CONTRACT_VIOLATION",
                        "payload missing email",
                        CapabilityErrorKind.CONTRACT,
                        Map.of("field", "email")
                ),
                tracer
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("name", "Ana"));

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertNotNull(result.getCapabilityError());
        assertEquals(CapabilityErrorKind.CONTRACT, result.getCapabilityError().kind());

        assertNotNull(tracer.flowTrace);
        assertEquals(StepOutcome.FAILED, tracer.flowTrace.outcome());
        assertEquals(1, tracer.flowTrace.steps().size());
        StepTrace stepTrace = tracer.flowTrace.steps().get(0);
        assertEquals(StepOutcome.FAILED, stepTrace.outcome());
        assertNotNull(stepTrace.capabilityError());
        assertEquals("CAPABILITY_CONTRACT_VIOLATION", stepTrace.capabilityError().code());
    }

    @Test
    void traceIncludesWrittenStateKeysWithoutDumpingPayload() {
        RecordingExecutionTracer tracer = new RecordingExecutionTracer();
        EventBus eventBus = event -> {
        };
        InvariantEngine invariantEngine = (entityName, payload) -> List.of();

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(
                                FlowStepDefinition.capabilityCall(
                                        "save-user",
                                        "persistence",
                                        "PersistenceCapability",
                                        "inmemory",
                                        "save",
                                        List.of("$input"),
                                        "$saved"
                                ),
                                FlowStepDefinition.returnValue("return-user", "$saved")
                        )
                ));

        KernelRunner runner = new KernelRunner(
                eventBus,
                invariantEngine,
                flowProvider,
                (call, state) -> CapabilityResult.success(Map.of("id", "u-1")),
                tracer
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "a@b.com"));

        assertEquals(ExecutionStatus.OK, result.getStatus());
        StepTrace capabilityStep = tracer.flowTrace.steps().get(0);
        Object written = capabilityStep.info().get("writtenStateKeys");
        assertTrue(written instanceof List<?>);
        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) written;
        assertTrue(keys.contains("saved"));
    }

    private static final class RecordingExecutionTracer implements ExecutionTracer {
        private final List<String> signals = new ArrayList<>();
        private FlowTrace flowTrace;

        @Override
        public void onFlowStart(FlowTraceMeta meta, long startedAtEpochMs) {
            signals.add("FlowStart");
        }

        @Override
        public void onStepStart(
                FlowTraceMeta meta,
                int stepIndex,
                String stepName,
                String stepType,
                long startedAtEpochMs
        ) {
            signals.add("StepStart");
        }

        @Override
        public void onStepEnd(FlowTraceMeta meta, StepTrace stepTrace) {
            signals.add("StepEnd");
        }

        @Override
        public void onFlowEnd(FlowTrace flowTrace) {
            signals.add("FlowEnd");
            this.flowTrace = flowTrace;
        }
    }

    private static final class RecordingEventInfrastructure implements EventBus, EventStore {
        @Override
        public void publish(com.npdev.kernel.events.EventEnvelope event) {
        }

        @Override
        public void append(com.npdev.kernel.events.EventEnvelope event) {
        }

        @Override
        public java.util.Optional<com.npdev.kernel.events.EventEnvelope> findFirst(String eventName, String correlationId) {
            return java.util.Optional.empty();
        }

        @Override
        public List<com.npdev.kernel.events.EventEnvelope> readByCorrelation(String correlationId) {
            return List.of();
        }

        @Override
        public List<com.npdev.kernel.events.EventEnvelope> readByEventName(String eventName) {
            return List.of();
        }
    }
}
