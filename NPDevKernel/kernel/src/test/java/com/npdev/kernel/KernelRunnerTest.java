package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.IdProvider;
import com.npdev.kernel.ports.InvariantEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelRunnerTest {

    @Test
    void matchesCorrelationFailsClosedOnBlankOrNullCorrelationId() {
        // F9 (FIRST_IMPRESSION_SPEC.md I7): every real caller (ResumeCoordinator) only reaches
        // matchesCorrelation when its own matchCorrelation() flag is true -- a blank/null
        // correlation id cannot satisfy that request, so it must not match ANY event, including
        // one whose own correlationId happens to be non-blank (matching everything was the bug).
        EventEnvelope event = EventEnvelope.of("some.event", Map.of());

        assertFalse(KernelRunner.matchesCorrelation(event, null));
        assertFalse(KernelRunner.matchesCorrelation(event, ""));
        assertFalse(KernelRunner.matchesCorrelation(event, "   "));
    }

    @Test
    void matchesCorrelationStillMatchesOnlyTheEqualNonBlankCorrelationId() {
        EventEnvelope event = EventEnvelope.of("some.event", Map.of(), "corr-123", "cause-1", Map.of());

        assertTrue(KernelRunner.matchesCorrelation(event, "corr-123"));
        assertFalse(KernelRunner.matchesCorrelation(event, "corr-999"));
    }

    @Test
    void publishEventDelegatesEnvelopeToEventBus() {
        AtomicReference<EventEnvelope> envelopeRef = new AtomicReference<>();

        EventBus bus = envelopeRef::set;
        InvariantEngine engine = (entityName, payload) -> List.of();

        KernelRunner runner = new KernelRunner(bus, engine);
        runner.publishEvent("user.created", "payload");

        EventEnvelope envelope = envelopeRef.get();
        assertEquals("user.created", envelope.eventName());
        assertTrue(envelope.correlationId() != null && !envelope.correlationId().isBlank());
        assertEquals("payload", envelope.payload().get("value"));
        assertEquals("v1", envelope.version());
    }

    @Test
    void publishExternalEventPersistsPublishesAndReturnsEnvelope() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowName -> java.util.Optional.empty(),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure
        );

        EventEnvelope envelope = runner.publishExternalEvent(
                "PaymentConfirmed",
                Map.of("paymentId", "p-1"),
                "corr-pay-1",
                "cause-pay-1"
        );

        assertEquals("PaymentConfirmed", envelope.eventName());
        assertEquals("corr-pay-1", envelope.correlationId());
        assertEquals("cause-pay-1", envelope.causationId());
        assertEquals("external", envelope.flowName());
        assertEquals(-1, envelope.stepIndex());
        assertEquals(1, eventInfrastructure.stored.size());
        assertEquals(1, eventInfrastructure.published.size());
        assertEquals(envelope, eventInfrastructure.stored.get(0));
        assertEquals(envelope, eventInfrastructure.published.get(0));
    }

    @Test
    void publishExternalEventFailsWhenEventStoreIsMissing() {
        EventBus eventBus = event -> {
        };
        KernelRunner runner = new KernelRunner(eventBus, (entityName, payload) -> List.of());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> runner.publishExternalEvent(
                        "PaymentConfirmed",
                        Map.of("paymentId", "p-2"),
                        "corr-pay-2",
                        "cause-pay-2"
                )
        );

        assertEquals("EventStore is required for publishExternalEvent", exception.getMessage());
    }

    @Test
    void executeUsesInjectedIdProviderForExecutionAndEventIds() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "DeterministicIdsFlow",
                        "User",
                        List.of(FlowStepDefinition.emitEvent("emit-user-created", "UserCreated", "$input"))
                ));

        AtomicInteger sequence = new AtomicInteger(0);
        IdProvider deterministicIds = scope -> scope + "-id-" + sequence.incrementAndGet();
        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure,
                deterministicIds
        );

        ExecutionResult result = runner.execute("DeterministicIdsFlow", Map.of("name", "Ana"));

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals("execution-id-1", result.getExecutionId());
        assertEquals(1, eventInfrastructure.published.size());
        EventEnvelope envelope = eventInfrastructure.published.get(0);
        assertEquals("event-id-3", envelope.eventId());
        assertEquals("execution-id-1", envelope.causationId());
    }

    @Test
    void enforceInvariantsThrowsViolationException() {
        EventBus bus = envelope -> {
        };
        InvariantEngine engine = (entityName, payload) -> List.of("email is required", "email must be unique");

        KernelRunner runner = new KernelRunner(bus, engine);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> runner.enforceInvariants("User", new Object()));
        KernelRunner.InvariantViolationException violation =
                assertInstanceOf(KernelRunner.InvariantViolationException.class, ex);

        assertEquals("User", violation.getEntityName());
        assertEquals(2, violation.getViolations().size());
        assertTrue(violation.getMessage().contains("Invariant violations for User"));
    }

    @Test
    void crudInvariantPathThrowsIfInvokedDuringFlowExecution() {
        EventBus eventBus = envelope -> {
        };
        InvariantEngine invariantEngine = (entityName, payload) -> List.of();

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(FlowStepDefinition.capabilityCall(
                                "save",
                                "persistence",
                                "PersistenceCapability",
                                "inmemory",
                                "save",
                                List.of("$input"),
                                "$saved"
                        ))
                ));

        AtomicReference<KernelRunner> runnerRef = new AtomicReference<>();
        KernelRunner runner = new KernelRunner(
                eventBus,
                invariantEngine,
                flowProvider,
                (call, state) -> {
                    runnerRef.get().enforceInvariants("User", Map.of());
                    return CapabilityResult.success(Map.of());
                }
        );
        runnerRef.set(runner);

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "a@b.com"));
        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertNotNull(result.getCapabilityError());
        assertEquals("CAPABILITY_DISPATCHER_EXCEPTION", result.getCapabilityError().code());
        assertEquals("CRUD invariant path invoked during flow execution", result.getCapabilityError().message());
    }

    @Test
    void subscribeEventDelegatesToEventBus() throws Exception {
        AtomicReference<String> subscribedTopic = new AtomicReference<>();
        AtomicReference<EventEnvelope> received = new AtomicReference<>();
        AtomicReference<Boolean> unsubscribed = new AtomicReference<>(false);

        EventBus bus = new EventBus() {
            @Override
            public void publish(EventEnvelope event) {
            }

            @Override
            public AutoCloseable subscribe(String topic, EventHandler handler) {
                subscribedTopic.set(topic);
                handler.onEvent(EventEnvelope.of(topic, Map.of("x", 1)));
                return () -> unsubscribed.set(true);
            }
        };

        KernelRunner runner = new KernelRunner(bus, (entity, payload) -> List.of());
        AutoCloseable token = runner.subscribeEvent("topic.test", received::set);
        token.close();

        assertEquals("topic.test", subscribedTopic.get());
        assertEquals("topic.test", received.get().eventName());
        assertTrue(unsubscribed.get());
    }

    @Test
    void executeRunsInvariantCapabilityAndEventPipeline() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();

        InvariantEngine invariantEngine = new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }

            @Override
            public List<InvariantEngine.Violation> evaluate(List<String> invariants, InvariantEngine.EvaluationContext context) {
                return List.of();
            }
        };

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(
                                FlowStepDefinition.invariant("pre", FlowStepDefinition.InvariantCheckpoint.PRE, List.of("emailUnique")),
                                FlowStepDefinition.capabilityCall(
                                        "save",
                                        "persistence",
                                        "PersistenceCapability",
                                        "inmemory",
                                        "save",
                                        List.of("$input"),
                                        "$saved"
                                ),
                                FlowStepDefinition.emitEvent("emit-user-created", "UserCreated", "$saved"),
                                FlowStepDefinition.invariant("post", FlowStepDefinition.InvariantCheckpoint.POST, List.of("postCheck"))
                        )
                ));

        CapabilityDispatcherStub dispatcher = new CapabilityDispatcherStub();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                invariantEngine,
                flowProvider,
                dispatcher,
                eventInfrastructure
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "a@b.com", "name", "Ana"));

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertTrue(result.getInvariantViolations().isEmpty());
        assertEquals("saved-id-1", ((Map<?, ?>) result.getOutput()).get("id"));
        assertEquals("UserCreated", eventInfrastructure.published.get(0).eventName());
        assertTrue(eventInfrastructure.published.get(0).correlationId() != null
                && !eventInfrastructure.published.get(0).correlationId().isBlank());
        assertEquals(1, result.getEmittedEvents().size());
    }

    @Test
    void executeEmitsEventsWithCorrelationAndCausationLifecycle() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateInvoice",
                        "Invoice",
                        List.of(
                                FlowStepDefinition.emitEvent("evt-1", "InvoiceDrafted", "$input"),
                                FlowStepDefinition.emitEvent("evt-2", "InvoiceIssued", "$input")
                        )
                ));

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure
        );
        ExecutionResult result = runner.execute("CreateInvoice", Map.of("invoiceId", "inv-1", "correlationId", "corr-fixed"));

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals(2, result.getEmittedEvents().size());
        EventEnvelope first = result.getEmittedEvents().get(0);
        EventEnvelope second = result.getEmittedEvents().get(1);
        assertEquals("corr-fixed", first.correlationId());
        assertEquals("corr-fixed", second.correlationId());
        assertEquals(result.getExecutionId(), first.causationId());
        assertEquals(result.getExecutionId(), second.causationId());
        assertEquals("CreateInvoice", first.flowName());
        assertEquals(Integer.valueOf(0), first.stepIndex());
        assertEquals("CreateInvoice", second.flowName());
        assertEquals(Integer.valueOf(1), second.stepIndex());
        assertEquals("InvoiceIssued", eventInfrastructure.published.get(1).eventName());
    }

    @Test
    void executeReturnsFailedWhenInvariantStepFails() {
        EventBus eventBus = envelope -> {
        };

        InvariantEngine invariantEngine = new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }

            @Override
            public List<InvariantEngine.Violation> evaluate(
                    List<String> invariants,
                    InvariantEngine.EvaluationContext context
            ) {
                return List.of(new InvariantEngine.Violation("INVARIANT_FAIL", "email must be unique", "emailUnique"));
            }
        };

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(FlowStepDefinition.invariant("pre", FlowStepDefinition.InvariantCheckpoint.PRE, List.of("emailUnique")))
                ));

        KernelRunner runner = new KernelRunner(
                eventBus,
                invariantEngine,
                flowProvider,
                (call, contextState) -> CapabilityResult.success(Map.of())
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "taken@npdev.com"));

        assertEquals(ExecutionStatus.INVARIANT_FAILED, result.getStatus());
        assertEquals(1, result.getInvariantViolations().size());
        assertTrue(result.getError().contains("Invariant checkpoint failed"));
        assertFalse(result.getError().isBlank());
    }

    @Test
    void executePassesExactInvariantRefsAndPreservesViolationIdentity() {
        EventBus eventBus = envelope -> {
        };
        RecordingInvariantEngine invariantEngine = new RecordingInvariantEngine();

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(FlowStepDefinition.invariant(
                                "validate-user",
                                "User",
                                FlowStepDefinition.InvariantCheckpoint.PRE,
                                List.of("EmailRequired", "EmailUnique")
                        ))
                ));

        KernelRunner runner = new KernelRunner(
                eventBus,
                invariantEngine,
                flowProvider,
                (call, contextState) -> CapabilityResult.success(Map.of())
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "taken@npdev.com"));

        assertNotNull(invariantEngine.lastRequest);
        assertEquals(List.of("EmailRequired", "EmailUnique"), invariantEngine.lastRequest.invariantRefs());
        assertEquals("User", invariantEngine.lastRequest.conceptName());
        assertEquals("CreateUser", invariantEngine.lastRequest.metadata().flowName());
        assertEquals("validate-user", invariantEngine.lastRequest.metadata().stepName());
        assertEquals(0, invariantEngine.lastRequest.metadata().stepIndex());

        assertEquals(ExecutionStatus.INVARIANT_FAILED, result.getStatus());
        InvariantEngine.Violation violation = result.getInvariantViolations().get(0);
        assertEquals("EmailUnique", violation.invariantRef());
        assertTrue(!violation.invariantRef().isBlank());
        assertTrue(!"<unknown>".equalsIgnoreCase(violation.invariantRef()));
        assertEquals("User", violation.conceptName());
        assertEquals("CreateUser", violation.flowName());
        assertEquals("validate-user", violation.stepName());
        assertEquals(0, violation.stepIndex());
    }

    @Test
    void executeFailsFastWhenAdapterReturnsAmbiguousInvariantRefForMultiInvariantStep() {
        EventBus eventBus = envelope -> {
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
                                "ambiguous violation",
                                ""
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
                                List.of("EmailRequired", "EmailUnique")
                        ))
                ));

        KernelRunner runner = new KernelRunner(
                eventBus,
                invariantEngine,
                flowProvider,
                (call, contextState) -> CapabilityResult.success(Map.of())
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> runner.execute("CreateUser", Map.of("email", "a@b.com"))
        );
        assertEquals(
                "Invariant violation missing invariantRef for multi-invariant evaluation",
                error.getMessage()
        );
    }

    @Test
    void executeReturnsStructuredCapabilityFailureWhenBindingIsMissing() {
        EventBus eventBus = envelope -> {
        };

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
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
                ));

        CapabilityRegistry registry = new CapabilityRegistry();
        RegistryCapabilityDispatcher dispatcher = new RegistryCapabilityDispatcher(registry);

        KernelRunner runner = new KernelRunner(
                eventBus,
                (entityName, payload) -> List.of(),
                flowProvider,
                dispatcher
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "a@b.com"));

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertNotNull(result.getCapabilityError());
        assertEquals(CapabilityErrorKind.NOT_FOUND, result.getCapabilityError().kind());
        assertEquals("CAPABILITY_BINDING_MISSING", result.getCapabilityError().code());
        assertEquals("save", result.getCapabilityOperation());
        assertEquals("save", result.getCapabilityStepName());
        assertEquals(0, result.getCapabilityStepIndex());
    }

    @Test
    void executeReturnsStructuredCapabilityFailureWhenAdapterThrowsRuntimeException() {
        EventBus eventBus = envelope -> {
        };

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(FlowStepDefinition.capabilityCall(
                                "save",
                                "persistence",
                                "PersistenceCapability",
                                "inmemory",
                                "save",
                                List.of("$input"),
                                "$saved"
                        ))
                ));

        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("persistence", "PersistenceCapability", "inmemory", new ThrowingPersistenceAdapter());
        RegistryCapabilityDispatcher dispatcher = new RegistryCapabilityDispatcher(registry);

        KernelRunner runner = new KernelRunner(
                eventBus,
                (entityName, payload) -> List.of(),
                flowProvider,
                dispatcher
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "a@b.com"));

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertNotNull(result.getCapabilityError());
        assertEquals("CAPABILITY_INVOCATION_FAILED", result.getCapabilityError().code());
        assertEquals(CapabilityErrorKind.PERMANENT, result.getCapabilityError().kind());
        assertEquals("boom", result.getCapabilityError().message());
    }

    @Test
    void executeReturnsStructuredCapabilityFailureForContractViolation() {
        EventBus eventBus = envelope -> {
        };

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUser",
                        "User",
                        List.of(FlowStepDefinition.capabilityCall(
                                "save",
                                "persistence",
                                "PersistenceCapability",
                                "inmemory",
                                "save",
                                List.of("$input"),
                                "$saved"
                        ))
                ));

        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("persistence", "PersistenceCapability", "inmemory", new ContractValidationAdapter());
        RegistryCapabilityDispatcher dispatcher = new RegistryCapabilityDispatcher(registry);

        KernelRunner runner = new KernelRunner(
                eventBus,
                (entityName, payload) -> List.of(),
                flowProvider,
                dispatcher
        );

        ExecutionResult result = runner.execute("CreateUser", Map.of("email", "a@b.com"));

        assertEquals(ExecutionStatus.CAPABILITY_FAILED, result.getStatus());
        assertNotNull(result.getCapabilityError());
        assertEquals("CAPABILITY_INVOCATION_FAILED", result.getCapabilityError().code());
        assertEquals(CapabilityErrorKind.CONTRACT, result.getCapabilityError().kind());
        assertEquals("payload must contain name", result.getCapabilityError().message());
    }

    @Test
    void executeSupportsBranchEventDataAndReturnStep() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();

        InvariantEngine invariantEngine = new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }

            @Override
            public List<InvariantEngine.Violation> evaluate(List<String> invariants, InvariantEngine.EvaluationContext context) {
                return List.of();
            }
        };

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "CreateUserFlow",
                        "User",
                        List.of(
                                FlowStepDefinition.capabilityCall(
                                        "save",
                                        "persistence",
                                        "PersistenceCapability",
                                        "inmemory",
                                        "save",
                                        List.of("$input"),
                                        "$saved"
                                ),
                                FlowStepDefinition.branch(
                                        "branch-email",
                                        "$saved.email == 'a@b.com'",
                                        List.of(FlowStepDefinition.emitEvent(
                                                "emit-user-created",
                                                "UserCreated",
                                                null,
                                                Map.of("id", "$saved.id", "email", "$saved.email")
                                        )),
                                        List.of(FlowStepDefinition.emitEvent(
                                                "emit-user-rejected",
                                                "UserRejected",
                                                null,
                                                Map.of("email", "$saved.email")
                                        ))
                                ),
                                FlowStepDefinition.returnValue("return-id", "$saved.id")
                        )
                ));

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                invariantEngine,
                flowProvider,
                new CapabilityDispatcherStub(),
                eventInfrastructure
        );
        ExecutionResult result = runner.execute("CreateUserFlow", Map.of("email", "a@b.com", "name", "Ana"));

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals("saved-id-1", result.getOutput());
        assertEquals(1, eventInfrastructure.published.size());
        assertEquals("UserCreated", eventInfrastructure.published.get(0).eventName());
        assertEquals("saved-id-1", eventInfrastructure.published.get(0).payload().get("id"));
    }

    /**
     * R4.2 (roadmap): a BRANCH step's condition used to be a hand-rolled {@code ==}/{@code !=}-only
     * matcher -- no {@code &&} or ordered comparison. {@link KernelRunner#evaluateCondition} now
     * tries the {@code ComputedExpression} grammar first, so this is the DoD's own example: a
     * condition combining {@code &&} and {@code >} decides the branch correctly, and BOTH operands
     * of the {@code &&} are checked (not just the first) -- qty alone over the threshold with the
     * wrong status must still take the else branch.
     */
    @Test
    void executeSupportsBranchConditionWithLogicalAndAndOrderedComparison() {
        InvariantEngine noViolations = new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }

            @Override
            public List<InvariantEngine.Violation> evaluate(List<String> invariants, InvariantEngine.EvaluationContext context) {
                return List.of();
            }
        };
        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "BulkOrderFlow",
                        "Order",
                        List.of(
                                FlowStepDefinition.branch(
                                        "branch-bulk",
                                        "$input.qty > 5 && $input.status == 'open'",
                                        List.of(FlowStepDefinition.emitEvent(
                                                "emit-bulk", "BulkOrderFlagged", null, Map.of("qty", "$input.qty")
                                        )),
                                        List.of(FlowStepDefinition.emitEvent(
                                                "emit-standard", "StandardOrderFlagged", null, Map.of("qty", "$input.qty")
                                        ))
                                ),
                                FlowStepDefinition.returnValue("return-qty", "$input.qty")
                        )
                ));

        // qty > 5 AND status == 'open' -- both operands true -- takes "then".
        RecordingEventInfrastructure bulk = new RecordingEventInfrastructure();
        KernelRunner bulkRunner = new KernelRunner(bulk, noViolations, flowProvider, new CapabilityDispatcherStub(), bulk);
        ExecutionResult bulkResult = bulkRunner.execute("BulkOrderFlow", Map.of("qty", 9, "status", "open"));
        assertEquals(ExecutionStatus.OK, bulkResult.getStatus());
        assertEquals("BulkOrderFlagged", bulk.published.get(0).eventName());

        // qty > 5 is false -- takes "else" even though status matches.
        RecordingEventInfrastructure lowQty = new RecordingEventInfrastructure();
        KernelRunner lowQtyRunner = new KernelRunner(lowQty, noViolations, flowProvider, new CapabilityDispatcherStub(), lowQty);
        lowQtyRunner.execute("BulkOrderFlow", Map.of("qty", 3, "status", "open"));
        assertEquals("StandardOrderFlagged", lowQty.published.get(0).eventName());

        // qty > 5 is true but status doesn't match -- && must still require BOTH -- takes "else".
        RecordingEventInfrastructure wrongStatus = new RecordingEventInfrastructure();
        KernelRunner wrongStatusRunner = new KernelRunner(wrongStatus, noViolations, flowProvider, new CapabilityDispatcherStub(), wrongStatus);
        wrongStatusRunner.execute("BulkOrderFlow", Map.of("qty", 9, "status", "closed"));
        assertEquals("StandardOrderFlagged", wrongStatus.published.get(0).eventName());
    }

    /**
     * R4.2 (roadmap) regression half: the exact {@code condition} strings that appear in the
     * in-repo corpus today (grepped 2026-08-18 across NPDevSamples/NPDevGenerator resources --
     * {@code canonical-demo}, {@code durable-workflow-demo}, {@code medium-expense-approval}) must
     * still evaluate to the SAME boolean now that {@link KernelRunner#evaluateCondition} tries
     * {@code ComputedExpression} first. Every one of them is a plain {@code ==} comparison, so
     * {@code ComputedExpression} parses and evaluates them directly (never falls through to the
     * legacy matcher) -- this pins the exact value each one produces, both when the condition is
     * met and when it is not, which is the shape a silent value-level regression would hit.
     */
    @Test
    void existingCorpusBranchConditionsEvaluateIdenticallyUnderComputedExpression() {
        // canonical-demo: schedule-reminder step, "$saved.status == 'Scheduled'"
        assertTrue(KernelRunner.evaluateCondition(
                "$saved.status == 'Scheduled'", Map.of("saved", Map.of("status", "Scheduled")), null));
        assertFalse(KernelRunner.evaluateCondition(
                "$saved.status == 'Scheduled'", Map.of("saved", Map.of("status", "Cancelled")), null));

        // durable-workflow-demo / medium-expense-approval: "$saved.needsManagerApproval == true"
        assertTrue(KernelRunner.evaluateCondition(
                "$saved.needsManagerApproval == true", Map.of("saved", Map.of("needsManagerApproval", true)), null));
        assertFalse(KernelRunner.evaluateCondition(
                "$saved.needsManagerApproval == true", Map.of("saved", Map.of("needsManagerApproval", false)), null));
    }

    @Test
    void executeSupportsAwaitEventUsingEventStore() {
        InMemoryEventStore eventStore = new InMemoryEventStore();
        eventStore.append(EventEnvelope.of(
                "InvoiceApproved",
                Map.of("status", "APPROVED", "receipt", "rcpt-1"),
                "corr-123",
                null,
                "external",
                0,
                Map.of(),
                "default",
                "anonymous"
        ));

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "FinalizeInvoice",
                        "Invoice",
                        List.of(
                                FlowStepDefinition.awaitEvent("wait-approval", "InvoiceApproved", "$approval"),
                                FlowStepDefinition.returnValue("return-status", "$approval.status")
                        )
                ));

        KernelRunner runner = new KernelRunner(
                eventStore,
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> CapabilityResult.success(null),
                eventStore
        );

        ExecutionResult result = runner.execute("FinalizeInvoice", Map.of("correlationId", "corr-123"));
        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals("APPROVED", result.getOutput());
    }

    @Test
    void executeSupportsAwaitEventPayloadMatching() {
        InMemoryEventStore eventStore = new InMemoryEventStore();
        eventStore.append(EventEnvelope.of(
                "InvoiceApproved",
                Map.of("status", "APPROVED", "receipt", "rcpt-77"),
                "corr-xyz",
                null,
                "FinalizeInvoice",
                0,
                Map.of(),
                "default",
                "anonymous"
        ));

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "FinalizeInvoice",
                        "Invoice",
                        List.of(
                                FlowStepDefinition.awaitEvent(
                                        "wait-approval",
                                        "InvoiceApproved",
                                        "$approval",
                                        true,
                                        Map.of("receipt", "$input.expectedReceipt")
                                ),
                                FlowStepDefinition.returnValue("return-status", "$approval.status")
                        )
                ));

        KernelRunner runner = new KernelRunner(
                eventStore,
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> CapabilityResult.success(null),
                eventStore
        );

        ExecutionResult result = runner.execute(
                "FinalizeInvoice",
                Map.of("correlationId", "corr-xyz", "expectedReceipt", "rcpt-77")
        );
        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals("APPROVED", result.getOutput());
    }

    @Test
    void executeReturnsWaitingEventWhenAwaitEventIsNotAvailable() {
        EventBus eventBus = envelope -> {
        };

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "AwaitMissingEventFlow",
                        "Invoice",
                        List.of(
                                FlowStepDefinition.awaitEvent("wait-response", "GovernmentResponse", "$response"),
                                FlowStepDefinition.returnValue("return-response", "$response.status")
                        )
                ));

        KernelRunner runner = new KernelRunner(
                eventBus,
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> CapabilityResult.success(null)
        );
        ExecutionResult result = runner.execute("AwaitMissingEventFlow", Map.of("correlationId", "corr-missing"));

        assertEquals(ExecutionStatus.WAITING_EVENT, result.getStatus());
        assertEquals("wait-response", result.getAwaitedStepName());
        assertEquals(Integer.valueOf(0), result.getAwaitedStepIndex());
        assertEquals("GovernmentResponse", result.getAwaitedEventName());
        assertEquals("corr-missing", result.getAwaitedCorrelationId());
        assertTrue(result.getError().contains("Awaited event not found"));
    }

    @Test
    void emitEventStepPersistsAndPublishesFact() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();

        InMemoryFlowDefinitionProvider flowProvider = new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "IssueInvoice",
                        "Invoice",
                        List.of(
                                FlowStepDefinition.emitEvent("emit-issued", "InvoiceIssued", "$input"),
                                FlowStepDefinition.returnValue("return-input", "$input")
                        )
                ));

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                flowProvider,
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure
        );

        ExecutionResult result = runner.execute("IssueInvoice", Map.of("id", "inv-10", "correlationId", "corr-10"));

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertEquals(1, eventInfrastructure.published.size());
        assertEquals(1, eventInfrastructure.stored.size());
        assertEquals(eventInfrastructure.published.get(0), eventInfrastructure.stored.get(0));
        assertEquals("InvoiceIssued", eventInfrastructure.stored.get(0).eventName());
        assertEquals("corr-10", eventInfrastructure.stored.get(0).correlationId());
    }

    /**
     * R2.4. This test used to be named {@code scheduleEventStepPersistsAndPublishesScheduledIntentMetadata}
     * and asserted that a 300-second delay published IMMEDIATELY with the delay stamped in
     * {@code _meta} -- it was the pin holding the defect in place. A delay now means a durable row
     * and nothing else; the assertions below are the same envelope, checked at the scheduler
     * instead of at the bus.
     */
    @Test
    void scheduleEventStepWithADelayHandsTheEnvelopeToTheSchedulerAndPublishesNothingInline() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        RecordingDeferredEventScheduler scheduler = new RecordingDeferredEventScheduler(true);

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                scheduleReminderFlowProvider(300L),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure
        ).withDeferredEventScheduler(scheduler);

        long before = System.currentTimeMillis();
        ExecutionResult result = runner.execute(
                "ScheduleReminder",
                Map.of("appointmentId", "apt-1", "correlationId", "corr-reminder")
        );
        long after = System.currentTimeMillis();

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertTrue(eventInfrastructure.published.isEmpty(),
                "A delayed scheduleEvent must not publish inline, got: " + eventInfrastructure.published);
        assertTrue(eventInfrastructure.stored.isEmpty(),
                "A delayed scheduleEvent must not append to the event store either -- ResumeCoordinator's"
                        + " sweep reads the STORE, so a stored envelope would wake a waiter and defeat the delay");
        assertTrue(result.getEmittedEvents() == null || result.getEmittedEvents().isEmpty(),
                "An event that has not been published yet must not be reported as emitted");

        assertEquals(1, scheduler.calls.size());
        EventEnvelope envelope = scheduler.calls.get(0).envelope();
        assertEquals("AppointmentReminderDue", envelope.eventName());
        assertEquals("corr-reminder", envelope.correlationId());
        assertEquals("ScheduleReminder", envelope.flowName());
        assertEquals("scheduled", ((Map<?, ?>) envelope.payload().get("_meta")).get("deliveryMode"));
        assertEquals(300L, ((Map<?, ?>) envelope.payload().get("_meta")).get("delaySeconds"));

        long dueAt = scheduler.calls.get(0).dueAtEpochMs();
        assertTrue(dueAt >= before + 300_000L && dueAt <= after + 300_000L,
                "due-at must be now + delay, got " + dueAt + " for a window of ["
                        + (before + 300_000L) + ", " + (after + 300_000L) + "]");
    }

    /**
     * R2.4's back-compat half. A zero delay is NOT routed through the durable table: it publishes
     * on the same call, in the same order, so that no existing model pays a scheduler tick of
     * latency for spelling an immediate event as {@code delaySeconds: 0}. The scheduler is wired
     * here precisely so that "never called" is a real assertion rather than a vacuous one.
     */
    @Test
    void scheduleEventStepWithZeroDelayStillPublishesInlineAndNeverTouchesTheScheduler() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        RecordingDeferredEventScheduler scheduler = new RecordingDeferredEventScheduler(true);

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                scheduleReminderFlowProvider(0L),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure
        ).withDeferredEventScheduler(scheduler);

        ExecutionResult result = runner.execute(
                "ScheduleReminder",
                Map.of("appointmentId", "apt-1", "correlationId", "corr-reminder-now")
        );

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertTrue(scheduler.calls.isEmpty(), "delaySeconds=0 must never reach the durable scheduler");
        assertEquals(1, eventInfrastructure.published.size());
        assertEquals(1, eventInfrastructure.stored.size());
        EventEnvelope envelope = eventInfrastructure.published.get(0);
        assertEquals(envelope, eventInfrastructure.stored.get(0));
        assertEquals("AppointmentReminderDue", envelope.eventName());
        assertEquals("corr-reminder-now", envelope.correlationId());
        assertEquals("scheduled", ((Map<?, ?>) envelope.payload().get("_meta")).get("deliveryMode"));
        assertEquals(0L, ((Map<?, ?>) envelope.payload().get("_meta")).get("delaySeconds"));
        assertNotNull(((Map<?, ?>) envelope.payload().get("_meta")).get("scheduledForEpochMs"));
    }

    /**
     * R2.4: with no durable scheduler bound, a delayed step FAILS rather than falling back to
     * publishing now. The fallback is the defect -- it is invisible by construction, because the
     * event still arrives and only its timing is wrong.
     */
    @Test
    void scheduleEventStepWithADelayFailsWhenNoDeferredSchedulerIsConfigured() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                scheduleReminderFlowProvider(300L),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure
        );

        ExecutionResult result = runner.execute(
                "ScheduleReminder",
                Map.of("appointmentId", "apt-1", "correlationId", "corr-reminder-unconfigured")
        );

        assertEquals(ExecutionStatus.EVENT_PERSIST_FAILED, result.getStatus());
        assertTrue(eventInfrastructure.published.isEmpty(),
                "The whole point is that it does NOT publish, got: " + eventInfrastructure.published);
        assertNotNull(result.getError());
        assertTrue(result.getError().contains("DeferredEventScheduler"),
                "The failure must name the missing collaborator, got: " + result.getError());
    }

    /**
     * R2.4: a scheduler that cannot persist reports false, and the step fails on it. Same reason as
     * above -- an unwritable schedule row must not degrade into an immediate publish.
     */
    @Test
    void scheduleEventStepWithADelayFailsWhenTheSchedulerCannotPersist() {
        RecordingEventInfrastructure eventInfrastructure = new RecordingEventInfrastructure();
        RecordingDeferredEventScheduler scheduler = new RecordingDeferredEventScheduler(false);

        KernelRunner runner = new KernelRunner(
                eventInfrastructure,
                (entityName, payload) -> List.of(),
                scheduleReminderFlowProvider(300L),
                (call, state) -> CapabilityResult.success(null),
                eventInfrastructure
        ).withDeferredEventScheduler(scheduler);

        ExecutionResult result = runner.execute(
                "ScheduleReminder",
                Map.of("appointmentId", "apt-1", "correlationId", "corr-reminder-unwritable")
        );

        assertEquals(ExecutionStatus.EVENT_PERSIST_FAILED, result.getStatus());
        assertEquals(1, scheduler.calls.size());
        assertTrue(eventInfrastructure.published.isEmpty());
    }

    private static InMemoryFlowDefinitionProvider scheduleReminderFlowProvider(long delaySeconds) {
        return new InMemoryFlowDefinitionProvider()
                .register(new FlowDefinition(
                        "ScheduleReminder",
                        "Appointment",
                        List.of(
                                FlowStepDefinition.scheduleEvent(
                                        "queue-reminder",
                                        "AppointmentReminderDue",
                                        "$input",
                                        Map.of(),
                                        delaySeconds
                                ),
                                FlowStepDefinition.returnValue("return-input", "$input")
                        )
                ));
    }

    private record ScheduleCall(EventEnvelope envelope, long dueAtEpochMs) {
    }

    private static final class RecordingDeferredEventScheduler
            implements com.npdev.kernel.ports.DeferredEventScheduler {
        private final List<ScheduleCall> calls = new ArrayList<>();
        private final boolean persisted;

        private RecordingDeferredEventScheduler(boolean persisted) {
            this.persisted = persisted;
        }

        @Override
        public boolean scheduleForLaterDelivery(EventEnvelope envelope, long dueAtEpochMs) {
            calls.add(new ScheduleCall(envelope, dueAtEpochMs));
            return persisted;
        }
    }

    private static final class CapabilityDispatcherStub implements com.npdev.kernel.ports.CapabilityDispatcher {
        @Override
        public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
            if ("persistence".equalsIgnoreCase(call.capability())
                    && "PersistenceCapability".equalsIgnoreCase(call.capabilityType())
                    && "save".equalsIgnoreCase(call.operation())) {
                return CapabilityResult.success(Map.of("id", "saved-id-1", "email", ((Map<?, ?>) call.input()).get("email")));
            }
            return CapabilityResult.success(Map.of());
        }
    }

    // W3.3 (2026-08-25 remediation plan / QUAL-33): the private EventStoreStub that used to live here
    // was extracted to InMemoryEventStore.java (same package) so EventStorePerformanceRegressionTest
    // could use it too without duplicating it.

    private static final class RecordingEventInfrastructure implements EventBus, EventStore {
        private final List<EventEnvelope> published = new ArrayList<>();
        private final List<EventEnvelope> stored = new ArrayList<>();

        @Override
        public void publish(EventEnvelope event) {
            published.add(event);
        }

        @Override
        public void append(EventEnvelope event) {
            stored.add(event);
        }

        @Override
        public java.util.Optional<EventEnvelope> findFirst(String eventName, String correlationId) {
            return stored.stream()
                    .filter(event -> eventName.equals(event.eventName()) && correlationId.equals(event.correlationId()))
                    .findFirst();
        }

        @Override
        public List<EventEnvelope> readByCorrelation(String correlationId) {
            return stored.stream()
                    .filter(event -> correlationId.equals(event.correlationId()))
                    .toList();
        }

        @Override
        public List<EventEnvelope> readByEventName(String eventName) {
            return stored.stream()
                    .filter(event -> eventName.equals(event.eventName()))
                    .toList();
        }
    }

    private static final class ThrowingPersistenceAdapter {
        public Object save(Object payload) {
            throw new RuntimeException("boom");
        }
    }

    private static final class ContractValidationAdapter {
        public Object save(Object payload) {
            if (!(payload instanceof Map<?, ?> map) || !map.containsKey("name")) {
                throw new IllegalArgumentException("payload must contain name");
            }
            return payload;
        }
    }

    private static final class RecordingInvariantEngine implements InvariantEngine {
        private InvariantEvaluationRequest lastRequest;

        @Override
        public List<String> evaluate(String entityName, Object payload) {
            return List.of();
        }

        @Override
        public InvariantEvaluationResult evaluate(InvariantEvaluationRequest request) {
            lastRequest = request;
            return new InvariantEvaluationResult(List.of(
                    new Violation(
                            "INVARIANT_FAIL",
                            "email must be unique",
                            "EmailUnique"
                    )
            ));
        }
    }
}


