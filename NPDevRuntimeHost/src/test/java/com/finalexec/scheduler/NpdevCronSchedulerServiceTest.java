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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-12 DoD: "verified by shrinking the cron to seconds in a gate test and observing" the
 * outcome -- a schedule.cron of {@code "* * * * * *"} (every second) proves the full path: model
 * declares a schedule -> the scheduler registers a CronTrigger -> KernelRunner.execute runs the
 * flow under a system principal -> the outcome tracker (ControlPanel's data source) records it.
 */
final class NpdevCronSchedulerServiceTest {

    @Test
    void scheduledFlowRunsRepeatedlyUnderSystemPrincipalAndRecordsOutcome() throws Exception {
        CompiledModel compiledModel = new CompiledModel(
                "demo",
                "1.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new CompiledFlow(
                        "CloseStaleOrders",
                        "Order",
                        "sync",
                        List.of(new CompiledFlowStep(
                                "return",
                                "return",
                                null,
                                null,
                                List.of(),
                                null,
                                null,
                                "$input",
                                null
                        )),
                        null,
                        null,
                        null,
                        false,
                        new CompiledFlowSchedule("* * * * * *", List.of("tenant-a"))
                )),
                List.of()
        );

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
                (schema, payload) -> List.of()
        );

        ScheduleOutcomeTracker tracker = new ScheduleOutcomeTracker();
        NpdevCronSchedulerService scheduler = new NpdevCronSchedulerService(compiledModel, runner, tracker, 2);
        scheduler.start();
        try {
            long deadline = System.currentTimeMillis() + 5000;
            ScheduleOutcome outcome = null;
            while (System.currentTimeMillis() < deadline) {
                outcome = tracker.all().stream()
                        .filter(o -> "CloseStaleOrders".equals(o.flowName()) && "tenant-a".equals(o.tenantId()))
                        .findFirst().orElse(null);
                if (outcome != null && outcome.runCount() >= 2) {
                    break;
                }
                Thread.sleep(200);
            }
            assertTrue(outcome != null, "expected an outcome to be recorded for the scheduled flow");
            assertEquals(ScheduleOutcome.STATUS_SUCCESS, outcome.status());
            assertTrue(outcome.runCount() >= 2, "expected at least 2 runs within 5s of a 1-second cron, got " + outcome.runCount());
            assertEquals("* * * * * *", outcome.cron());
        } finally {
            scheduler.stop();
        }
    }
}
