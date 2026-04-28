package com.npdev.kernel;

import com.npdev.kernel.capabilities.CapabilityExecutionPolicy;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.JsonCodec;
import com.npdev.kernel.ports.SchemaValidator;
import com.npdev.kernel.schema.SchemaObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KernelRunnerCapabilityShapeValidationTest {

    @Test
    void capabilityCallRejectsInvalidInputShapeBeforeDispatch() {
        SchemaObject inputSchema = new SchemaObject(
                "object",
                Map.of(
                        "email", new SchemaObject("string", Map.of(), List.of(), null, null, null, null, null, null)
                ),
                List.of("email"),
                null,
                null,
                null,
                null,
                null,
                null
        );

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(
                                FlowStepDefinition.capabilityCall(
                                        "persist",
                                        "PersistenceCapability",
                                        "PersistenceCapability",
                                        "test",
                                        "saveUser",
                                        List.of("$input"),
                                        "$saved",
                                        CapabilityExecutionPolicy.defaults(),
                                        inputSchema,
                                        null
                                ),
                                FlowStepDefinition.returnValue("return", "$saved")
                        ),
                        null,
                        null
                ));

        SchemaValidator schemaValidator = (schema, payload) -> {
            if (payload instanceof Map<?, ?> map && map.containsKey("email")) {
                return List.of();
            }
            return List.of(new InputValidationError("$.email", "required_missing", "Required field is missing"));
        };

        KernelRunner runner = new KernelRunner(
                event -> { },
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> {
                    throw new AssertionError("CapabilityDispatcher must not be invoked when input validation fails.");
                },
                com.npdev.kernel.ports.ExecutionTracer.NOOP,
                null,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                com.npdev.kernel.ports.IdempotencyStore.noop(),
                JsonCodec.noop(),
                schemaValidator
        );

        ExecutionResult result = runner.executeFlow("CreateUser", Map.of("name", "Ana"));

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertNotNull(result.getCapabilityError());
        assertEquals("CAPABILITY_CONTRACT_INPUT_INVALID", result.getCapabilityError().code());
        assertEquals(CapabilityErrorKind.CONTRACT, result.getCapabilityError().kind());
        assertTrue(result.getCapabilityError().details().containsKey("validationErrors"));
        assertEquals(com.npdev.kernel.errors.FailureCodes.CAPABILITY_CONTRACT, result.getFailureInfo().code());
    }

    @Test
    void capabilityCallRejectsInvalidOutputShapeAfterDispatch() {
        SchemaObject outputSchema = new SchemaObject(
                "object",
                Map.of(
                        "id", new SchemaObject("string", Map.of(), List.of(), null, null, null, null, null, null)
                ),
                List.of("id"),
                null,
                null,
                null,
                null,
                null,
                null
        );

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(
                                FlowStepDefinition.capabilityCall(
                                        "persist",
                                        "PersistenceCapability",
                                        "PersistenceCapability",
                                        "test",
                                        "saveUser",
                                        List.of("$input"),
                                        "$saved",
                                        CapabilityExecutionPolicy.defaults(),
                                        null,
                                        outputSchema
                                ),
                                FlowStepDefinition.returnValue("return", "$saved")
                        ),
                        null,
                        null
                ));

        SchemaValidator schemaValidator = (schema, payload) -> {
            if (payload instanceof Map<?, ?> map && map.containsKey("id")) {
                return List.of();
            }
            return List.of(new InputValidationError("$.id", "required_missing", "Required field is missing"));
        };

        KernelRunner runner = new KernelRunner(
                event -> { },
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> CapabilityResult.success(Map.of("email", "ana@example.com")),
                com.npdev.kernel.ports.ExecutionTracer.NOOP,
                null,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                com.npdev.kernel.ports.IdempotencyStore.noop(),
                JsonCodec.noop(),
                schemaValidator
        );

        ExecutionResult result = runner.executeFlow("CreateUser", Map.of("email", "ana@example.com"));

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertNotNull(result.getCapabilityError());
        assertEquals("CAPABILITY_CONTRACT_OUTPUT_INVALID", result.getCapabilityError().code());
        assertEquals(CapabilityErrorKind.CONTRACT, result.getCapabilityError().kind());
        assertTrue(result.getCapabilityError().details().containsKey("validationErrors"));
        assertEquals(com.npdev.kernel.errors.FailureCodes.CAPABILITY_CONTRACT, result.getFailureInfo().code());
    }
}
