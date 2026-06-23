package com.npdev.kernel;

import com.npdev.kernel.capabilities.CapabilityExecutionPolicy;
import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CapabilityPolicyOverride;
import com.npdev.kernel.capability.CapabilityPolicyOverrides;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.kernel.errors.ErrorKind;
import com.npdev.kernel.errors.FailureCodes;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.JsonCodec;
import com.npdev.kernel.ports.MetricsSink;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.trace.FlowTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KernelRunnerCapabilityPolicyTest {

    @Test
    void retriesTransientFailuresUntilSuccessWithinPolicyBudget() {
        AtomicInteger attempts = new AtomicInteger();
        KernelRunner runner = runnerWithCapabilityStep(
                new CapabilityExecutionPolicy(3, 0, 0, null, null),
                (call, state) -> {
                    int current = attempts.incrementAndGet();
                    if (current < 3) {
                        return CapabilityResult.failure(
                                "TRANSIENT_NETWORK",
                                "temporary outage",
                                CapabilityErrorKind.TRANSIENT,
                                Map.of("attempt", current)
                        );
                    }
                    return CapabilityResult.success(Map.of("id", "u-1"));
                }
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "a@b.com"));

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals(3, attempts.get());
        assertEquals("u-1", ((Map<?, ?>) result.getOutput()).get("id"));
    }

    @Test
    void doesNotRetryPermanentErrors() {
        AtomicInteger attempts = new AtomicInteger();
        KernelRunner runner = runnerWithCapabilityStep(
                new CapabilityExecutionPolicy(5, 0, 0, null, null),
                (call, state) -> {
                    attempts.incrementAndGet();
                    return CapabilityResult.failure(
                            "DB_DOWN",
                            "database unavailable",
                            CapabilityErrorKind.PERMANENT,
                            Map.of()
                    );
                }
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "a@b.com"));

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertNotNull(result.getCapabilityError());
        assertEquals(1, attempts.get());
    }

    @Test
    void abortsWhenCapabilityCallTimesOut() {
        AtomicInteger attempts = new AtomicInteger();
        KernelRunner runner = runnerWithCapabilityStep(
                new CapabilityExecutionPolicy(3, 0, 40, null, null),
                (call, state) -> {
                    attempts.incrementAndGet();
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    return CapabilityResult.success(Map.of("id", "u-timeout"));
                }
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "a@b.com"));

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertNotNull(result.getCapabilityError());
        assertEquals("CAPABILITY_TIMEOUT", result.getCapabilityError().code());
        assertNotNull(result.getFailureInfo());
        assertEquals(ErrorKind.TIMEOUT, result.getFailureInfo().kind());
        assertEquals(FailureCodes.CAPABILITY_TIMEOUT, result.getFailureInfo().code());
        assertEquals(1, attempts.get());
    }

    @Test
    void propagatesIdempotencyKeyFromStateToCapabilityCall() {
        AtomicReference<String> seenIdempotencyKey = new AtomicReference<>();
        KernelRunner runner = runnerWithCapabilityStep(
                new CapabilityExecutionPolicy(1, 0, 0, "$input.requestId", null),
                (call, state) -> {
                    seenIdempotencyKey.set(call.idempotencyKey());
                    return CapabilityResult.success(Map.of("id", "u-2"));
                }
        );

        ExecutionResult result = runner.execute(
                "CreateUser",
                Map.of("email", "a@b.com", "requestId", "idem-123")
        );

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals("idem-123", seenIdempotencyKey.get());
    }

    @Test
    void stampsCallerTenantIntoFlowDrivenPersistenceSavePayload() {
        AtomicReference<Object> seenEntity = new AtomicReference<>();
        KernelRunner runner = runnerWithCapabilityStep(
                new CapabilityExecutionPolicy(1, 0, 0, null, null),
                (call, state) -> {
                    seenEntity.set(call.args().get(0));
                    return CapabilityResult.success(Map.of("id", "u-9"));
                }
        );

        ExecutionResult result = runner.execute(
                "CreateUser",
                Map.of("email", "a@b.com"),
                ExecutionContext.of("tenant-xyz", "actor-1")
        );

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertTrue(seenEntity.get() instanceof Map, "save entity should be a Map");
        assertEquals("tenant-xyz", ((Map<?, ?>) seenEntity.get()).get("tenantId"),
                "flow-driven persistence save must carry the caller's tenant from the execution context");
    }

    @Test
    void stampsCallerTenantIntoFlowDrivenPersistenceQueryCriteria() {
        AtomicReference<Object> seenConcept = new AtomicReference<>();
        AtomicReference<Object> seenCriteria = new AtomicReference<>();
        KernelRunner runner = new KernelRunner(
                event -> {
                },
                (entityName, payload) -> List.of(),
                new InMemoryFlowDefinitionProvider()
                        .register(new FlowDefinition(
                                "ListUsers",
                                "User",
                                List.of(
                                        FlowStepDefinition.capabilityCall(
                                                "query",
                                                "persistence",
                                                "PersistenceCapability",
                                                "inmemory",
                                                "query",
                                                List.of("User", "$input"),
                                                "$found",
                                                new CapabilityExecutionPolicy(1, 0, 0, null, null)
                                        ),
                                        FlowStepDefinition.returnValue("return-found", "$found")
                                )
                        )),
                (call, state) -> {
                    seenConcept.set(call.args().get(0));
                    seenCriteria.set(call.args().get(1));
                    return CapabilityResult.success(List.of());
                },
                null,
                null,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                CapabilityPolicyOverrides.empty(),
                new InMemoryJsonCodec(),
                null,
                MetricsSink.noop()
        );

        ExecutionResult result = runner.execute(
                "ListUsers",
                Map.of("status", "active"),
                ExecutionContext.of("tenant-xyz", "actor-1")
        );

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals("User", seenConcept.get());
        assertTrue(seenCriteria.get() instanceof Map, "query criteria should be a Map");
        Map<?, ?> criteria = (Map<?, ?>) seenCriteria.get();
        assertEquals("active", criteria.get("status"), "the flow author's own criteria must still be present");
        assertEquals("tenant-xyz", criteria.get("tenantId"),
                "flow-driven persistence query/list must carry the caller's tenant from the execution context");
    }

    @Test
    void opensCircuitAfterRepeatedTransientFailuresAndShortCircuitsNextCall() {
        AtomicInteger attempts = new AtomicInteger();
        CircuitBreakerStateStore circuitStore = new InMemoryCircuitBreakerStore();
        KernelRunner runner = runnerWithCapabilityStep(
                new CapabilityExecutionPolicy(1, 0, 0, null, null),
                (call, state) -> {
                    attempts.incrementAndGet();
                    return CapabilityResult.failure(
                            "NETWORK_DOWN",
                            "temporary outage",
                            CapabilityErrorKind.TRANSIENT,
                            Map.of()
                    );
                },
                circuitStore,
                BulkheadStore.noop(),
                IdempotencyStore.noop()
        );

        for (int index = 0; index < 5; index++) {
            ExecutionResult failed = runner.execute("CreateUser", Map.of("email", "a@b.com"));
            assertEquals(ExecutionStatus.CAPABILITY_FAILED, failed.getStatus());
        }

        ExecutionResult shortCircuited = runner.execute("CreateUser", Map.of("email", "a@b.com"));
        assertEquals(ExecutionStatus.CAPABILITY_FAILED, shortCircuited.getStatus());
        assertNotNull(shortCircuited.getCapabilityError());
        assertEquals("CAPABILITY_CIRCUIT_OPEN", shortCircuited.getCapabilityError().code());
        assertNotNull(shortCircuited.getFailureInfo());
        assertEquals(ErrorKind.TRANSIENT, shortCircuited.getFailureInfo().kind());
        assertEquals(FailureCodes.CIRCUIT_OPEN, shortCircuited.getFailureInfo().code());
        assertEquals(5, attempts.get());
    }

    @Test
    void returnsCachedIdempotencySuccessWithoutInvokingCapabilityTwice() {
        AtomicInteger attempts = new AtomicInteger();
        IdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();
        KernelRunner runner = runnerWithCapabilityStep(
                new CapabilityExecutionPolicy(1, 0, 0, "$input.requestId", null),
                (call, state) -> {
                    attempts.incrementAndGet();
                    return CapabilityResult.success(Map.of("id", "u-100"));
                },
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                idempotencyStore
        );

        ExecutionResult first = runner.execute("CreateUser", Map.of("email", "a@b.com", "requestId", "idem-100"));
        ExecutionResult second = runner.execute("CreateUser", Map.of("email", "a@b.com", "requestId", "idem-100"));

        assertEquals(ExecutionStatus.OK, first.getStatus());
        assertEquals(ExecutionStatus.OK, second.getStatus());
        assertEquals(1, attempts.get());
        assertEquals("u-100", ((Map<?, ?>) second.getOutput()).get("id"));
    }

    @Test
    void recordsCapabilityMetricsForSuccessAndFailurePaths() {
        RecordingMetricsSink metricsSink = new RecordingMetricsSink();
        AtomicInteger attempts = new AtomicInteger();
        CircuitBreakerStateStore circuitStore = new InMemoryCircuitBreakerStore();
        KernelRunner runner = runnerWithCapabilityStep(
                new CapabilityExecutionPolicy(1, 0, 0, null, null),
                (call, state) -> {
                    attempts.incrementAndGet();
                    return CapabilityResult.failure(
                            "NETWORK_DOWN",
                            "temporary outage",
                            CapabilityErrorKind.TRANSIENT,
                            Map.of()
                    );
                },
                circuitStore,
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                metricsSink
        );

        for (int index = 0; index < 6; index++) {
            runner.execute("CreateUser", Map.of("email", "a@b.com"));
        }

        assertTrue(metricsSink.counter("npdev.capability.call") >= 1, "Expected capability call metrics");
        assertTrue(metricsSink.counter("npdev.capability.failure") >= 1, "Expected capability failure metrics");
        assertTrue(
                metricsSink.anyTagValue("npdev.capability.failure", "circuitState", "OPEN"),
                "Expected circuitState OPEN tag in failure metrics"
        );
        assertEquals(5, attempts.get());
    }

    @Test
    void capabilityPolicyOverrideCanReduceRetryAttemptsPerOperation() {
        AtomicInteger attempts = new AtomicInteger();
        CapabilityPolicyOverrides overrides = new CapabilityPolicyOverrides(Map.of(
                "persistence",
                Map.of(
                        "save",
                        new CapabilityPolicyOverride(1, null, null, null, null, null, null, null)
                )
        ));
        KernelRunner runner = runnerWithCapabilityStep(
                new CapabilityExecutionPolicy(3, 0, 0, null, null),
                (call, state) -> {
                    int current = attempts.incrementAndGet();
                    if (current == 1) {
                        return CapabilityResult.failure(
                                "TRANSIENT_NETWORK",
                                "temporary outage",
                                CapabilityErrorKind.TRANSIENT,
                                Map.of("attempt", current)
                        );
                    }
                    return CapabilityResult.success(Map.of("id", "u-override"));
                },
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                MetricsSink.noop(),
                overrides
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "a@b.com"));

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertEquals(1, attempts.get());
    }

    @Test
    void effectiveCapabilityPolicyIsRecordedInStepTraceInfo() {
        AtomicReference<FlowTrace> capturedTrace = new AtomicReference<>();
        CapabilityPolicyOverrides overrides = new CapabilityPolicyOverrides(Map.of(
                "persistence",
                Map.of(
                        "save",
                        new CapabilityPolicyOverride(2, 7L, 11L, 100L, 2, 1500L, 3, true)
                )
        ));
        KernelRunner runner = new KernelRunner(
                event -> {
                },
                (entityName, payload) -> List.of(),
                new InMemoryFlowDefinitionProvider()
                        .register(new FlowDefinition(
                                "CreateUser",
                                "User",
                                List.of(
                                        FlowStepDefinition.capabilityCall(
                                                "save",
                                                "persistence",
                                                "PersistenceCapability",
                                                "inmemory",
                                                "save",
                                                List.of("$input"),
                                                "$saved",
                                                new CapabilityExecutionPolicy(3, 0, 0, null, null)
                                        ),
                                        FlowStepDefinition.returnValue("return-saved", "$saved")
                                )
                        )),
                (call, state) -> CapabilityResult.failure(
                        "TRANSIENT_NETWORK",
                        "temporary outage",
                        CapabilityErrorKind.TRANSIENT,
                        Map.of()
                ),
                new ExecutionTracer() {
                    @Override
                    public void onFlowEnd(FlowTrace flowTrace) {
                        capturedTrace.set(flowTrace);
                    }
                },
                null,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                overrides,
                new InMemoryJsonCodec(),
                null,
                MetricsSink.noop()
        );

        runner.execute("CreateUser", Map.of("email", "a@b.com"));

        FlowTrace flowTrace = capturedTrace.get();
        assertNotNull(flowTrace);
        assertFalse(flowTrace.steps().isEmpty());
        Map<String, Object> info = flowTrace.steps().get(0).info();
        assertEquals(2, info.get("retryCount"));
        assertEquals(7L, info.get("retryDelayMs"));
        assertEquals(11L, info.get("retryMaxDelayMs"));
        assertEquals(100L, info.get("timeoutMs"));
        assertEquals(2, info.get("circuitOpenAfterFailures"));
        assertEquals(1500L, info.get("circuitOpenMs"));
        assertEquals(3, info.get("bulkheadMaxConcurrent"));
        assertEquals(true, info.get("cacheIdempotencyFailures"));
    }

    private static KernelRunner runnerWithCapabilityStep(
            CapabilityExecutionPolicy policy,
            com.npdev.kernel.ports.CapabilityDispatcher dispatcher
    ) {
        return runnerWithCapabilityStep(
                policy,
                dispatcher,
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                MetricsSink.noop(),
                CapabilityPolicyOverrides.empty()
        );
    }

    private static KernelRunner runnerWithCapabilityStep(
            CapabilityExecutionPolicy policy,
            com.npdev.kernel.ports.CapabilityDispatcher dispatcher,
            CircuitBreakerStateStore circuitBreakerStateStore,
            BulkheadStore bulkheadStore,
            IdempotencyStore idempotencyStore
    ) {
        return runnerWithCapabilityStep(
                policy,
                dispatcher,
                circuitBreakerStateStore,
                bulkheadStore,
                idempotencyStore,
                MetricsSink.noop(),
                CapabilityPolicyOverrides.empty()
        );
    }

    private static KernelRunner runnerWithCapabilityStep(
            CapabilityExecutionPolicy policy,
            com.npdev.kernel.ports.CapabilityDispatcher dispatcher,
            CircuitBreakerStateStore circuitBreakerStateStore,
            BulkheadStore bulkheadStore,
            IdempotencyStore idempotencyStore,
            MetricsSink metricsSink
    ) {
        return runnerWithCapabilityStep(
                policy,
                dispatcher,
                circuitBreakerStateStore,
                bulkheadStore,
                idempotencyStore,
                metricsSink,
                CapabilityPolicyOverrides.empty()
        );
    }

    private static KernelRunner runnerWithCapabilityStep(
            CapabilityExecutionPolicy policy,
            com.npdev.kernel.ports.CapabilityDispatcher dispatcher,
            CircuitBreakerStateStore circuitBreakerStateStore,
            BulkheadStore bulkheadStore,
            IdempotencyStore idempotencyStore,
            MetricsSink metricsSink,
            CapabilityPolicyOverrides capabilityPolicyOverrides
    ) {
        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(
                                FlowStepDefinition.capabilityCall(
                                        "save",
                                        "persistence",
                                        "PersistenceCapability",
                                        "inmemory",
                                        "save",
                                        List.of("$input"),
                                        "$saved",
                                        policy
                                ),
                                FlowStepDefinition.returnValue("return-saved", "$saved")
                        )
                ));

        return new KernelRunner(
                event -> {
                },
                (entityName, payload) -> List.of(),
                flowProvider,
                dispatcher,
                null,
                null,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                circuitBreakerStateStore,
                bulkheadStore,
                idempotencyStore,
                capabilityPolicyOverrides,
                new InMemoryJsonCodec(),
                null,
                metricsSink
        );
    }

    private static final class RecordingMetricsSink implements MetricsSink {
        private final CopyOnWriteArrayList<Map<String, String>> increments = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> incrementNames = new CopyOnWriteArrayList<>();

        @Override
        public void inc(String name, Map<String, String> tags) {
            incrementNames.add(name);
            increments.add(tags == null ? Map.of() : Map.copyOf(tags));
        }

        @Override
        public void observeMs(String name, long durationMs, Map<String, String> tags) {
            // no-op for this test
        }

        long counter(String metricName) {
            return incrementNames.stream().filter(metricName::equals).count();
        }

        boolean anyTagValue(String metricName, String tagKey, String expectedValue) {
            for (int index = 0; index < incrementNames.size(); index++) {
                if (!metricName.equals(incrementNames.get(index))) {
                    continue;
                }
                String tag = increments.get(index).get(tagKey);
                if (expectedValue.equals(tag)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class InMemoryCircuitBreakerStore implements CircuitBreakerStateStore {
        private final Map<CapabilityOpKey, CircuitBreakerState> states = new ConcurrentHashMap<>();

        @Override
        public CircuitBreakerState get(CapabilityOpKey key) {
            return states.getOrDefault(key, CircuitBreakerState.closed());
        }

        @Override
        public void put(CapabilityOpKey key, CircuitBreakerState state) {
            states.put(key, state);
        }

        @Override
        public void reset(CapabilityOpKey key) {
            states.remove(key);
        }
    }

    private static final class InMemoryIdempotencyStore implements IdempotencyStore {
        private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

        @Override
        public Optional<IdempotencyRecord> find(String tenantId, String capability, String operation, String idempotencyKey) {
            return Optional.ofNullable(records.get(key(tenantId, capability, operation, idempotencyKey)));
        }

        @Override
        public void saveSuccess(
                String tenantId,
                String capability,
                String operation,
                String idempotencyKey,
                String resultJsonRedacted,
                long createdAtMs
        ) {
            records.put(
                    key(tenantId, capability, operation, idempotencyKey),
                    new IdempotencyRecord(
                            tenantId,
                            idempotencyKey,
                            capability,
                            operation,
                            createdAtMs,
                            IdempotencyRecord.STATUS_SUCCESS,
                            resultJsonRedacted,
                            null
                    )
            );
        }

        @Override
        public void saveFailure(
                String tenantId,
                String capability,
                String operation,
                String idempotencyKey,
                String errorCode,
                long createdAtMs
        ) {
            records.put(
                    key(tenantId, capability, operation, idempotencyKey),
                    new IdempotencyRecord(
                            tenantId,
                            idempotencyKey,
                            capability,
                            operation,
                            createdAtMs,
                            IdempotencyRecord.STATUS_FAILED,
                            null,
                            errorCode
                    )
            );
        }

        private static String key(String tenantId, String capability, String operation, String idempotencyKey) {
            return tenantId + "|" + capability + "|" + operation + "|" + idempotencyKey;
        }
    }

    private static final class InMemoryJsonCodec implements JsonCodec {
        private final Map<String, Object> values = new ConcurrentHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public String toJson(Object value) {
            String token = "token-" + sequence.incrementAndGet();
            values.put(token, value);
            return token;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T fromJson(String json, Class<T> clazz) {
            return (T) values.get(json);
        }

        @Override
        public Object fromJsonToObject(String json) {
            return values.get(json);
        }
    }
}
