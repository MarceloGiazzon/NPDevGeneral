package com.npdev.kernel;

import com.npdev.kernel.errors.ErrorKind;
import com.npdev.kernel.errors.FailureCodes;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.JsonCodec;
import com.npdev.kernel.ports.SchemaValidator;
import com.npdev.kernel.schema.SchemaObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelRunnerInputValidationTest {

    @Test
    void executeFlowRejectsInvalidInputWithStructuredValidationError() {
        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(FlowStepDefinition.returnValue("return", "$input")),
                        new SchemaObject(
                                "object",
                                Map.of(
                                        "email",
                                        new SchemaObject("string", Map.of(), List.of(), null, null, null, null, null, null)
                                ),
                                List.of("email"),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        ),
                        null
                ));

        SchemaValidator schemaValidator = (schema, payload) -> {
            if (payload instanceof Map<?, ?> map && map.containsKey("email")) {
                return List.of();
            }
            return List.of(new InputValidationError("$.email", "required_missing", "Required field is missing"));
        };

        KernelRunner runner = new KernelRunner(
                event -> {
                },
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> CapabilityResult.success(null),
                com.npdev.kernel.ports.ExecutionTracer.NOOP,
                null,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                JsonCodec.noop(),
                schemaValidator
        );

        ExecutionResult result = runner.executeFlow("CreateUser", Map.of("name", "Ana"));

        assertEquals(ExecutionStatus.INPUT_VALIDATION_FAILED, result.getStatus());
        assertEquals("INPUT_VALIDATION_FAILED", result.getErrorCode());
        assertEquals(ErrorKind.INPUT_VALIDATION, result.getFailureInfo().kind());
        assertEquals(FailureCodes.INPUT_VALIDATION_FAILED, result.getFailureInfo().code());
        assertEquals(1, result.getInputValidationErrors().size());
        assertEquals("$.email", result.getInputValidationErrors().get(0).field());
        assertEquals("required_missing", result.getInputValidationErrors().get(0).code());
        assertTrue(result.getError().contains("Input validation failed"));
    }
}
