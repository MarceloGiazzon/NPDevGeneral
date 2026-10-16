package com.finalexec.scheduler;

import com.npdev.adapters.flowcompiled.CompiledModelFlowDefinitionProvider;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowSchedule;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.RegistryCapabilityDispatcher;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.JsonCodec;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** ENG-1: a 5-field cron is registered (not dropped); a malformed one shows as a failed schedule. */
class NpdevCronSchedulerServiceFiveFieldTest {

    @Test
    void fiveFieldCronIsRegisteredNormalized() {
        ScheduleOutcomeTracker tracker = new ScheduleOutcomeTracker();
        NpdevCronSchedulerService service = start("0 2 * * *", tracker);
        try {
            assertEquals(1, tracker.all().size());
            ScheduleOutcome outcome = tracker.all().get(0);
            assertEquals("0 0 2 * * *", outcome.cron());
            assertEquals(ScheduleOutcome.STATUS_PENDING, outcome.status());
        } finally {
            service.stop();
        }
    }

    @Test
    void malformedCronIsRecordedAsFailedSchedule() {
        ScheduleOutcomeTracker tracker = new ScheduleOutcomeTracker();
        NpdevCronSchedulerService service = start("x y z a b c", tracker);
        try {
            assertEquals(1, tracker.all().size());
            ScheduleOutcome outcome = tracker.all().get(0);
            assertEquals(ScheduleOutcome.STATUS_FAILURE, outcome.status());
            assertNotNull(outcome.lastError());
        } finally {
            service.stop();
        }
    }

    private static NpdevCronSchedulerService start(String cron, ScheduleOutcomeTracker tracker) {
        CompiledModel compiledModel = new CompiledModel(
                "demo", "1.0", Map.of(), List.of(), List.of(), List.of(),
                List.of(new CompiledFlow(
                        "FiveField", "Order", "sync",
                        List.of(new CompiledFlowStep("return", "return", null, null, List.of(), null, null, "$input", null)),
                        null, null, null, false,
                        new CompiledFlowSchedule(cron, List.of("tenant-a")))),
                List.of());
        CapabilityRegistry registry = new CapabilityRegistry();
        KernelRunner runner = new KernelRunner(
                event -> { },
                (entityName, payload) -> List.of(),
                new CompiledModelFlowDefinitionProvider(compiledModel),
                new RegistryCapabilityDispatcher(registry),
                ExecutionTracer.NOOP,
                null,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                JsonCodec.noop(),
                (schema, payload) -> List.of());
        NpdevCronSchedulerService service = new NpdevCronSchedulerService(compiledModel, runner, tracker, 1, (DataSource) null);
        service.start();
        return service;
    }
}
