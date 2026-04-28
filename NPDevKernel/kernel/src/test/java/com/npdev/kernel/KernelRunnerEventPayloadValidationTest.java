package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventSchemaProvider;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.JsonCodec;
import com.npdev.kernel.ports.SchemaValidator;
import com.npdev.kernel.schema.SchemaObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelRunnerEventPayloadValidationTest {

    @Test
    void emitEventRejectsInvalidPayloadShape() {
        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "EmitUserSaved",
                        "User",
                        List.of(
                                FlowStepDefinition.emitEvent("emit", "UserSaved", "$input", Map.of()),
                                FlowStepDefinition.returnValue("return", "$input")
                        ),
                        null,
                        null
                ));

        SchemaObject requiredIdSchema = new SchemaObject(
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

        EventSchemaProvider schemaProvider = eventName -> {
            if ("UserSaved".equals(eventName)) {
                return Optional.of(requiredIdSchema);
            }
            return Optional.empty();
        };

        SchemaValidator schemaValidator = (schema, payload) -> {
            if (payload instanceof Map<?, ?> map && map.containsKey("id")) {
                return List.of();
            }
            return List.of(new InputValidationError("$.id", "required_missing", "Required field is missing"));
        };

        EventBus eventBus = envelope -> {
        };

        EventStore eventStore = new InMemoryEventStore();

        InvariantEngine invariantEngine = (entityName, payload) -> List.of();

        CapabilityDispatcher capabilityDispatcher = (call, state) -> CapabilityResult.success(Map.of());

        KernelRunner runner = new KernelRunner(
                eventBus,
                invariantEngine,
                flowProvider,
                capabilityDispatcher,
                ExecutionTracer.NOOP,
                eventStore,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                JsonCodec.noop(),
                schemaValidator
        ).withEventSchemaProvider(schemaProvider);

        ExecutionResult result = runner.executeFlow("EmitUserSaved", Map.of("email", "ana@example.com"));

        assertEquals(ExecutionStatus.EVENT_PAYLOAD_INVALID, result.getStatus());
        assertNotNull(result.getFailureInfo());
        assertEquals("event_payload_invalid", result.getFailureInfo().code());
        assertTrue(result.getFailureInfo().details().containsKey("validationErrors"));
    }

    @Test
    void emitEventValidatesScalarPayloadBeforeEnvelopeNormalization() {
        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "EmitScalarPayload",
                        "User",
                        List.of(
                                FlowStepDefinition.emitEvent("emit", "CodePublished", "$input.code", Map.of()),
                                FlowStepDefinition.returnValue("return", "$input")
                        ),
                        null,
                        null
                ));

        SchemaObject stringPayloadSchema = new SchemaObject(
                "string",
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null
        );

        EventSchemaProvider schemaProvider = eventName -> {
            if ("CodePublished".equals(eventName)) {
                return Optional.of(stringPayloadSchema);
            }
            return Optional.empty();
        };

        AtomicReference<Object> validatedPayload = new AtomicReference<>();
        SchemaValidator schemaValidator = (schema, payload) -> {
            validatedPayload.set(payload);
            if (payload instanceof String) {
                return List.of();
            }
            return List.of(new InputValidationError("$", "type_mismatch", "Expected string payload"));
        };

        EventBus eventBus = envelope -> {
        };

        EventStore eventStore = new InMemoryEventStore();

        InvariantEngine invariantEngine = (entityName, payload) -> List.of();

        CapabilityDispatcher capabilityDispatcher = (call, state) -> CapabilityResult.success(Map.of());

        KernelRunner runner = new KernelRunner(
                eventBus,
                invariantEngine,
                flowProvider,
                capabilityDispatcher,
                ExecutionTracer.NOOP,
                eventStore,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                JsonCodec.noop(),
                schemaValidator
        ).withEventSchemaProvider(schemaProvider);

        ExecutionResult result = runner.executeFlow("EmitScalarPayload", Map.of("code", "ABC-123"));

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals("ABC-123", validatedPayload.get());
        assertEquals(1, result.getEmittedEvents().size());
        assertEquals("ABC-123", result.getEmittedEvents().get(0).payload().get("value"));
    }

    private static final class InMemoryEventStore implements EventStore {
        private final List<EventEnvelope> events = new ArrayList<>();

        @Override
        public void append(EventEnvelope event) {
            events.add(event);
        }

        @Override
        public List<EventEnvelope> readByCorrelation(String correlationId) {
            return events.stream().filter(e -> correlationId.equals(e.correlationId())).toList();
        }

        @Override
        public List<EventEnvelope> readByEventName(String eventName) {
            return events.stream().filter(e -> eventName.equals(e.eventName())).toList();
        }
    }
}
