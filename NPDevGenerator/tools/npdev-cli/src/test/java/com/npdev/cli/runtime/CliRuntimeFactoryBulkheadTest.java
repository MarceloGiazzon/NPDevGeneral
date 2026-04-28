package com.npdev.cli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.ExecutionStatus;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.StepTrace;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliRuntimeFactoryBulkheadTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void inprocBulkheadRejectsWhenConcurrencyLimitIsExceeded() throws Exception {
        Path permissions = Files.createTempFile("permissions-bulkhead", ".json");
        Path simulation = Files.createTempFile("sim-bulkhead", ".json");
        Files.writeString(permissions, """
                {
                  "grants": [
                    {
                      "permission": "flow.execute",
                      "tenantId": "tenant-bulkhead",
                      "actorId": "actor-bulkhead"
                    },
                    {
                      "permission": "capability.invoke",
                      "tenantId": "tenant-bulkhead",
                      "actorId": "actor-bulkhead"
                    }
                  ]
                }
                """);
        Files.writeString(simulation, """
                {
                  "operations": {
                    "persistence.unique": {
                      "default": {
                        "ok": true,
                        "value": true
                      }
                    },
                    "persistence.save": {
                      "default": {
                        "ok": true,
                        "value": {
                          "id": "u-1",
                          "email": "simulated@example.com"
                        },
                        "delayMs": 1500
                      }
                    }
                  }
                }
                """);

        CliRuntimeFactory factory = new CliRuntimeFactory(OBJECT_MAPPER);
        CliRuntime runtime = factory.create(new CliRuntimeOptions(
                modelPath(),
                simulation,
                null,
                permissions
        ));

        int concurrentExecutions = 24;
        CountDownLatch ready = new CountDownLatch(concurrentExecutions);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentExecutions);
        List<Future<ExecutionResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < concurrentExecutions; index++) {
                final int requestIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Start latch was not released");
                    }
                    return runtime.kernelRunner().executeFlow(
                            "CreateUser",
                            Map.of(
                                    "email", "bulkhead-" + requestIndex + "@example.com",
                                    "name", "Bulkhead " + requestIndex
                            ),
                            ExecutionContext.of("tenant-bulkhead", "actor-bulkhead")
                    );
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "Workers did not become ready in time");
            start.countDown();

            List<ExecutionResult> results = new ArrayList<>();
            for (Future<ExecutionResult> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }

            long successCount = results.stream()
                    .filter(result -> result.getStatus() == ExecutionStatus.OK)
                    .count();
            List<ExecutionResult> rejected = results.stream()
                    .filter(result -> result.getStatus() == ExecutionStatus.CAPABILITY_FAILED)
                    .filter(result -> result.getCapabilityError() != null)
                    .filter(result -> "CAPABILITY_BULKHEAD_FULL".equals(result.getCapabilityError().code()))
                    .toList();

            assertTrue(successCount > 0, "Expected at least one successful execution");
            assertTrue(!rejected.isEmpty(), "Expected at least one bulkhead rejection");

            ExecutionResult bulkheadRejected = rejected.get(0);
            assertEquals("persistence", bulkheadRejected.getCapabilityName());
            assertEquals("save", bulkheadRejected.getCapabilityOperation());
            assertNotNull(bulkheadRejected.getExecutionId());

            FlowTrace trace = runtime.traceStore()
                    .findByExecutionId(bulkheadRejected.getExecutionId())
                    .orElseThrow(() -> new AssertionError("Expected trace for rejected execution"));
            StepTrace saveStep = trace.steps().stream()
                    .filter(step -> "save-user".equals(step.stepName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected save-user trace step"));
            assertEquals("REJECTED", saveStep.info().get("bulkheadState"));
            assertEquals("CLOSED", saveStep.info().get("circuitState"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate cleanly");
        }
    }

    private static Path modelPath() {
        Path direct = Path.of("test-models", "user-minimal", "model.json");
        if (Files.exists(direct)) {
            return direct.toAbsolutePath().normalize();
        }
        Path nested = Path.of("..", "..", "test-models", "user-minimal", "model.json").normalize();
        if (Files.exists(nested)) {
            return nested.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Could not locate test model");
    }
}
