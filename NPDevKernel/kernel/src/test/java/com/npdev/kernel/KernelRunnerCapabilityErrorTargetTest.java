package com.npdev.kernel;

import com.npdev.kernel.capabilities.CapabilityExecutionPolicy;
import com.npdev.kernel.capability.CapabilityPolicyOverrides;
import com.npdev.kernel.ports.JsonCodec;
import com.npdev.kernel.ports.MetricsSink;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FORM P3-T5c: {@code capabilityCall.errorTarget} turns a failed call into a value the flow can branch on, and
 * {@code createConcept ifExists: skip} turns a unique-constraint conflict into a skipped step.
 */
class KernelRunnerCapabilityErrorTargetTest {

    private static KernelRunner runner(FlowStepDefinition callStep, CapabilityResult result) {
        return runner(callStep, result, "$answer");
    }

    private static KernelRunner runner(FlowStepDefinition callStep, CapabilityResult result, String returnRef) {
        return new KernelRunner(
                event -> { },
                (entityName, payload) -> List.of(),
                new InMemoryFlowDefinitionProvider().register(new FlowDefinition("F", "Order", List.of(
                        callStep,
                        FlowStepDefinition.returnValue("done", returnRef)))),
                (call, state) -> result,
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
                MetricsSink.noop());
    }

    private static FlowStepDefinition call() {
        return FlowStepDefinition.capabilityCall("call", "geo", "HttpCapability", "inmemory", "near",
                List.of("$input"), "$answer", new CapabilityExecutionPolicy(1, 0, 0, 0, 0, 0, null, null));
    }

    private static CapabilityResult failure(String message) {
        return CapabilityResult.failure("BOOM", message, CapabilityErrorKind.PERMANENT, Map.of("httpStatus", 503));
    }

    @Test
    void withoutErrorTargetAFailedCallFailsTheFlow() {
        ExecutionResult result = runner(call(), failure("down")).execute("F", Map.of());
        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
    }

    @Test
    void errorTargetStoresTheFailureAsAValueAndTheFlowContinues() {
        ExecutionResult result = runner(call().withCallOptions("$routeError", null), failure("down"), "$routeError").execute("F", Map.of());
        assertEquals(ExecutionStatus.OK, result.getStatus());
        Map<?, ?> error = (Map<?, ?>) result.getOutput();
        assertEquals("BOOM", error.get("code"));
        assertEquals("down", error.get("message"));
        assertEquals("PERMANENT", error.get("kind"));
        assertEquals(503, error.get("httpStatus"));
        assertEquals(true, error.get("retryable"));
    }

    @Test
    void errorTargetIsClearedWhenTheCallSucceeds() {
        ExecutionResult result = runner(call().withCallOptions("routeError", null), CapabilityResult.success("fine")).execute("F", Map.of());
        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals("fine", result.getOutput());
    }

    @Test
    void ifExistsSkipToleratesAUniqueConflictButNothingElse() {
        ExecutionResult skipped = runner(call().withCallOptions(null, "skip"),
                failure("Unique index or primary key violation: PUBLIC.ORDERS(NAME)")).execute("F", Map.of());
        assertEquals(ExecutionStatus.OK, skipped.getStatus());

        ExecutionResult other = runner(call().withCallOptions(null, "skip"), failure("connection refused")).execute("F", Map.of());
        assertEquals(ExecutionStatus.CAPABILITY_FAILED, other.getStatus(), "only a unique conflict is skipped");

        ExecutionResult noOption = runner(call(), failure("Unique index or primary key violation")).execute("F", Map.of());
        assertTrue(noOption.getStatus() == ExecutionStatus.CAPABILITY_FAILED, "without ifExists a conflict still fails");
    }

    private static final class InMemoryJsonCodec implements JsonCodec {
        private final Map<String, Object> values = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();

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
