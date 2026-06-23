package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.capability.CapabilityPolicyOverride;
import com.npdev.kernel.capability.CapabilityPolicyOverrides;
import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.CircuitState;
import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.kernel.capabilities.CapabilityExecutionPolicy;
import com.npdev.kernel.errors.ErrorKind;
import com.npdev.kernel.errors.FailureCodes;
import com.npdev.kernel.errors.FailureInfo;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.FlowDefinitionProvider;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdProvider;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.JsonCodec;
import com.npdev.kernel.ports.MetricsSink;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.SchemaValidator;
import com.npdev.kernel.ports.EventSchemaProvider;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.schema.SchemaObject;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import com.npdev.kernel.security.PermissionDecision;
import com.npdev.kernel.security.PermissionRequirement;
import com.npdev.kernel.security.PermissionSubject;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Sovereign kernel runner.
 * The runtime application delegates domain execution to this class.
 */
public final class KernelRunner implements FlowEngine {

    
    private static final Logger LOG = Logger.getLogger(KernelRunner.class.getName());
private final EventBus eventBus;
    private final InvariantEngine invariantEngine;
    private final FlowDefinitionProvider flowDefinitionProvider;
    private final CapabilityDispatcher capabilityDispatcher;
    private final ExecutionTracer executionTracer;
    private final EventStore eventStore;
    private final FlowInstanceStore flowInstanceStore;
    private final CorrelationOwnershipStore correlationOwnershipStore;
    private final CircuitBreakerStateStore circuitBreakerStateStore;
    private final BulkheadStore bulkheadStore;
    private final IdempotencyStore idempotencyStore;
    private final CapabilityPolicyOverrides capabilityPolicyOverrides;
    private final JsonCodec jsonCodec;
    private final SchemaValidator schemaValidator;
    private EventSchemaProvider eventSchemaProvider = EventSchemaProvider.noop();
    private PermissionEvaluator permissionEvaluator = PermissionEvaluator.allowAll();
    private final MetricsSink metricsSink;
    private final IdProvider idProvider;
    private final ThreadLocal<String> currentFlowContext = new ThreadLocal<>();
    private static final Object UNRESOLVED = new Object();
    private static final long RESUME_BASE_DELAY_MS = 5_000L;
    private static final long RESUME_MAX_DELAY_MS = 300_000L;
    private static final int RESUME_MAX_ATTEMPTS = 20;
    private static final int CIRCUIT_FAILURE_THRESHOLD = 5;
    private static final long CIRCUIT_OPEN_DURATION_MS = 30_000L;
    private static final int BULKHEAD_MAX_CONCURRENT = 10;
    private static final int IDEMPOTENCY_RESULT_MAX_CHARS = 16_384;
    private static final String AWAIT_STATE_KEY = "_npdev.await";
    private static final String AWAIT_FIELD_EVENT_NAME = "awaitEventName";
    private static final String AWAIT_FIELD_MATCH_CORRELATION = "matchCorrelation";
    private static final String AWAIT_FIELD_PAYLOAD_MATCH_REFS = "payloadMatchRefs";
    private static final String AWAIT_FIELD_STEP_INDEX = "stepIndex";
    private static final String AWAIT_FIELD_STEP_NAME = "stepName";
    private static final String AWAIT_FIELD_AWAIT_REF = "awaitRef";
    private static final String FLOW_RESUME_IDEMPOTENCY_CAPABILITY = "__flow_resume";

    public KernelRunner withEventSchemaProvider(EventSchemaProvider provider) {
        this.eventSchemaProvider = provider == null ? EventSchemaProvider.noop() : provider;
        return this;
    }

    public KernelRunner withPermissionEvaluator(PermissionEvaluator evaluator) {
        this.permissionEvaluator = evaluator == null ? PermissionEvaluator.allowAll() : evaluator;
        return this;
    }


    public KernelRunner(EventBus eventBus, InvariantEngine invariantEngine) {
        this(eventBus, invariantEngine, flowName -> Optional.empty(), (call, state) -> {
            return CapabilityResult.failure(
                    "CAPABILITY_DISPATCHER_NOT_CONFIGURED",
                    "No CapabilityDispatcher configured for kernel execution",
                    CapabilityErrorKind.NOT_FOUND,
                    Map.of(
                            "capability", call.capability(),
                            "operation", call.operation(),
                            "adapterId", call.adapterId() == null ? "<missing>" : call.adapterId()
                    )
            );
        }, ExecutionTracer.NOOP, eventBus instanceof EventStore store ? store : null, FlowInstanceStore.noop(), CorrelationOwnershipStore.noop());
    }

    public KernelRunner(EventBus eventBus, InvariantEngine invariantEngine, IdProvider idProvider) {
        this(
                eventBus,
                invariantEngine,
                flowName -> Optional.empty(),
                (call, state) -> CapabilityResult.failure(
                        "CAPABILITY_DISPATCHER_NOT_CONFIGURED",
                        "No CapabilityDispatcher configured for kernel execution",
                        CapabilityErrorKind.NOT_FOUND,
                        Map.of(
                                "capability", call.capability(),
                                "operation", call.operation(),
                                "adapterId", call.adapterId() == null ? "<missing>" : call.adapterId()
                        )
                ),
                ExecutionTracer.NOOP,
                eventBus instanceof EventStore store ? store : null,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                CapabilityPolicyOverrides.empty(),
                JsonCodec.noop(),
                SchemaValidator.noop(),
                MetricsSink.noop(),
                idProvider
        );
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher
    ) {
        this(eventBus, invariantEngine, flowDefinitionProvider, capabilityDispatcher, ExecutionTracer.NOOP);
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer
    ) {
        this(
                eventBus,
                invariantEngine,
                flowDefinitionProvider,
                capabilityDispatcher,
                executionTracer,
                eventBus instanceof EventStore store ? store : null,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop()
        );
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            EventStore eventStore
    ) {
        this(
                eventBus,
                invariantEngine,
                flowDefinitionProvider,
                capabilityDispatcher,
                ExecutionTracer.NOOP,
                eventStore,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop()
        );
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            EventStore eventStore,
            IdProvider idProvider
    ) {
        this(
                eventBus,
                invariantEngine,
                flowDefinitionProvider,
                capabilityDispatcher,
                ExecutionTracer.NOOP,
                eventStore,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                CapabilityPolicyOverrides.empty(),
                JsonCodec.noop(),
                SchemaValidator.noop(),
                MetricsSink.noop(),
                idProvider
        );
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer,
            EventStore eventStore
    ) {
        this(
                eventBus,
                invariantEngine,
                flowDefinitionProvider,
                capabilityDispatcher,
                executionTracer,
                eventStore,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop()
        );
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore
    ) {
        this(
                eventBus,
                invariantEngine,
                flowDefinitionProvider,
                capabilityDispatcher,
                ExecutionTracer.NOOP,
                eventStore,
                flowInstanceStore,
                CorrelationOwnershipStore.noop()
        );
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore
    ) {
        this(
                eventBus,
                invariantEngine,
                flowDefinitionProvider,
                capabilityDispatcher,
                executionTracer,
                eventStore,
                flowInstanceStore,
                CorrelationOwnershipStore.noop()
        );
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            CorrelationOwnershipStore correlationOwnershipStore
    ) {
        this(
                eventBus,
                invariantEngine,
                flowDefinitionProvider,
                capabilityDispatcher,
                executionTracer,
                eventStore,
                flowInstanceStore,
                correlationOwnershipStore,
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                CapabilityPolicyOverrides.empty(),
                JsonCodec.noop(),
                SchemaValidator.noop()
        );
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            CorrelationOwnershipStore correlationOwnershipStore,
            CircuitBreakerStateStore circuitBreakerStateStore,
            BulkheadStore bulkheadStore,
            IdempotencyStore idempotencyStore
    ) {
        this(
                eventBus,
                invariantEngine,
                flowDefinitionProvider,
                capabilityDispatcher,
                executionTracer,
                eventStore,
                flowInstanceStore,
                correlationOwnershipStore,
                circuitBreakerStateStore,
                bulkheadStore,
                idempotencyStore,
                CapabilityPolicyOverrides.empty(),
                JsonCodec.noop(),
                SchemaValidator.noop(),
                MetricsSink.noop()
        );
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            CorrelationOwnershipStore correlationOwnershipStore,
            CircuitBreakerStateStore circuitBreakerStateStore,
            BulkheadStore bulkheadStore,
            IdempotencyStore idempotencyStore,
            JsonCodec jsonCodec
    ) {
        this(
                eventBus,
                invariantEngine,
                flowDefinitionProvider,
                capabilityDispatcher,
                executionTracer,
                eventStore,
                flowInstanceStore,
                correlationOwnershipStore,
                circuitBreakerStateStore,
                bulkheadStore,
                idempotencyStore,
                CapabilityPolicyOverrides.empty(),
                jsonCodec,
                SchemaValidator.noop(),
                MetricsSink.noop()
        );
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            CorrelationOwnershipStore correlationOwnershipStore,
            CircuitBreakerStateStore circuitBreakerStateStore,
            BulkheadStore bulkheadStore,
            IdempotencyStore idempotencyStore,
            JsonCodec jsonCodec,
            SchemaValidator schemaValidator
    ) {
        this(
                eventBus,
                invariantEngine,
                flowDefinitionProvider,
                capabilityDispatcher,
                executionTracer,
                eventStore,
                flowInstanceStore,
                correlationOwnershipStore,
                circuitBreakerStateStore,
                bulkheadStore,
                idempotencyStore,
                CapabilityPolicyOverrides.empty(),
                jsonCodec,
                schemaValidator,
                MetricsSink.noop()
        );
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            CorrelationOwnershipStore correlationOwnershipStore,
            CircuitBreakerStateStore circuitBreakerStateStore,
            BulkheadStore bulkheadStore,
            IdempotencyStore idempotencyStore,
            CapabilityPolicyOverrides capabilityPolicyOverrides,
            JsonCodec jsonCodec,
            SchemaValidator schemaValidator
    ) {
        this(
                eventBus,
                invariantEngine,
                flowDefinitionProvider,
                capabilityDispatcher,
                executionTracer,
                eventStore,
                flowInstanceStore,
                correlationOwnershipStore,
                circuitBreakerStateStore,
                bulkheadStore,
                idempotencyStore,
                capabilityPolicyOverrides,
                jsonCodec,
                schemaValidator,
                MetricsSink.noop()
        );
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            CorrelationOwnershipStore correlationOwnershipStore,
            CircuitBreakerStateStore circuitBreakerStateStore,
            BulkheadStore bulkheadStore,
            IdempotencyStore idempotencyStore,
            CapabilityPolicyOverrides capabilityPolicyOverrides,
            JsonCodec jsonCodec,
            SchemaValidator schemaValidator,
            MetricsSink metricsSink
    ) {
        this(
                eventBus,
                invariantEngine,
                flowDefinitionProvider,
                capabilityDispatcher,
                executionTracer,
                eventStore,
                flowInstanceStore,
                correlationOwnershipStore,
                circuitBreakerStateStore,
                bulkheadStore,
                idempotencyStore,
                capabilityPolicyOverrides,
                jsonCodec,
                schemaValidator,
                metricsSink,
                IdProvider.uuid()
        );
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            CorrelationOwnershipStore correlationOwnershipStore,
            CircuitBreakerStateStore circuitBreakerStateStore,
            BulkheadStore bulkheadStore,
            IdempotencyStore idempotencyStore,
            CapabilityPolicyOverrides capabilityPolicyOverrides,
            JsonCodec jsonCodec,
            SchemaValidator schemaValidator,
            MetricsSink metricsSink,
            IdProvider idProvider
    ) {
        this.eventBus = eventBus;
        this.invariantEngine = invariantEngine;
        this.flowDefinitionProvider = flowDefinitionProvider;
        this.capabilityDispatcher = capabilityDispatcher;
        this.executionTracer = executionTracer == null ? ExecutionTracer.NOOP : executionTracer;
        this.eventStore = eventStore;
        this.flowInstanceStore = flowInstanceStore == null ? FlowInstanceStore.noop() : flowInstanceStore;
        this.correlationOwnershipStore = correlationOwnershipStore == null
                ? CorrelationOwnershipStore.noop()
                : correlationOwnershipStore;
        this.circuitBreakerStateStore = circuitBreakerStateStore == null
                ? CircuitBreakerStateStore.noop()
                : circuitBreakerStateStore;
        this.bulkheadStore = bulkheadStore == null ? BulkheadStore.noop() : bulkheadStore;
        this.idempotencyStore = idempotencyStore == null ? IdempotencyStore.noop() : idempotencyStore;
        this.capabilityPolicyOverrides = capabilityPolicyOverrides == null
                ? CapabilityPolicyOverrides.empty()
                : capabilityPolicyOverrides;
        this.jsonCodec = jsonCodec == null ? JsonCodec.noop() : jsonCodec;
        this.schemaValidator = schemaValidator == null ? SchemaValidator.noop() : schemaValidator;
        this.metricsSink = metricsSink == null ? MetricsSink.noop() : metricsSink;
        this.idProvider = idProvider == null ? IdProvider.uuid() : idProvider;
    }

    public KernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            FlowDefinitionProvider flowDefinitionProvider,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            CorrelationOwnershipStore correlationOwnershipStore,
            CircuitBreakerStateStore circuitBreakerStateStore,
            BulkheadStore bulkheadStore,
            IdempotencyStore idempotencyStore,
            JsonCodec jsonCodec,
            SchemaValidator schemaValidator,
            MetricsSink metricsSink
    ) {
        this(
                eventBus,
                invariantEngine,
                flowDefinitionProvider,
                capabilityDispatcher,
                executionTracer,
                eventStore,
                flowInstanceStore,
                correlationOwnershipStore,
                circuitBreakerStateStore,
                bulkheadStore,
                idempotencyStore,
                CapabilityPolicyOverrides.empty(),
                jsonCodec,
                schemaValidator,
                metricsSink
        );
    }

    public void publishEvent(String topic, Object payload) {
        publishEvent(topic, payload, null, null, Map.of());
    }

    public void publishEvent(String topic, Object payload, Map<String, Object> metadata) {
        publishEvent(topic, payload, readMetaString(metadata, "correlationId"), readMetaString(metadata, "causationId"), metadata);
    }

    public void publishEvent(
            String topic,
            Object payload,
            String correlationId,
            String causationId,
            Map<String, Object> metadata
    ) {
        publishEventInternal(
                topic,
                payload,
                correlationId,
                causationId,
                metadata,
                0,
                ExecutionContext.anonymous()
        );
    }

    private PermissionSubject toPermissionSubject(ExecutionContext context) {
        ExecutionContext effectiveContext = normalizeExecutionContext(context);
        return new PermissionSubject(
                effectiveContext.actorId(),
                effectiveContext.tenantId(),
                new ArrayList<>(effectiveContext.roles()),
                List.of()
        );
    }

    private PermissionDecision evaluatePermission(ExecutionContext context, PermissionRequirement requirement) {
        PermissionDecision decision = permissionEvaluator.evaluate(toPermissionSubject(context), requirement);
        if (decision == null) {
            return PermissionDecision.deny(FailureCodes.FORBIDDEN, "Permission denied");
        }
        return decision;
    }

    public EventEnvelope publishExternalEvent(
            String eventName,
            Map<String, Object> payload,
            String correlationId,
            String causationId
    ) {
        return publishExternalEvent(
                eventName,
                payload,
                correlationId,
                causationId,
                ExecutionContext.anonymous()
        );
    }
    public EventEnvelope publishExternalEvent(
            String eventName,
            Map<String, Object> payload,
            String correlationId,
            String causationId,
            ExecutionContext executionContext
    ) {
        if (eventStore == null) {
            throw new IllegalStateException("EventStore is required for publishExternalEvent");
        }
        ExecutionContext effectiveContext = normalizeExecutionContext(executionContext);
        PermissionDecision eventDecision = evaluatePermission(
                effectiveContext,
                new PermissionRequirement("event.publish", "event", eventName)
        );
        if (!eventDecision.allowed()) {
            throw new IllegalStateException(eventDecision.message().isBlank()
                    ? "Event publication denied for '" + eventName + "'"
                    : eventDecision.message());
        }
        String effectiveCorrelationId = correlationId == null || correlationId.isBlank()
                ? nextId("correlation")
                : correlationId;
        return publishEventInternal(
                eventName,
                payload == null ? Map.of() : payload,
                effectiveCorrelationId,
                causationId,
                Map.of(),
                -1,
                effectiveContext
        );
    }

    private EventEnvelope publishEventInternal(
            String topic,
            Object payload,
            String correlationId,
            String causationId,
            Map<String, Object> metadata,
            int stepIndex,
            ExecutionContext executionContext
    ) {
        ExecutionContext effectiveContext = normalizeExecutionContext(executionContext);
        String effectiveCorrelationId = correlationId == null || correlationId.isBlank()
                ? nextId("correlation")
                : correlationId;
        String effectiveCausationId = causationId == null || causationId.isBlank()
                ? nextId("causation")
                : causationId;
        enforceCorrelationOwnership(effectiveCorrelationId, effectiveContext.tenantId());
        EventEnvelope envelope = newEnvelope(
                nextId("event"),
                topic,
                effectiveCorrelationId,
                effectiveCausationId,
                toEventPayloadMap(payload),
                metadata == null ? Map.of() : metadata,
                "external",
                stepIndex,
                effectiveContext.tenantId(),
                effectiveContext.actorId()
        );
        if (eventStore != null) {
            eventStore.append(envelope);
        }
        eventBus.publish(envelope);
        resumeWaitingExecutionsFor(envelope, null, effectiveCorrelationId, effectiveContext);
        return envelope;
    }

    public AutoCloseable subscribeEvent(String topic, EventBus.EventHandler handler) {
        return eventBus.subscribe(topic, handler);
    }

    /**
     * CRUD invariant path. Do not use in flow runtime.
     * Flows must call invariantEngine with explicit invariantRefs.
     */
    @Deprecated
    List<String> checkInvariants(String entityName, Object payload) {
        assertCrudInvariantPathAllowed();
        return invariantEngine.evaluate(entityName, payload);
    }

    /**
     * CRUD invariant path. Do not use in flow runtime.
     * Flows must call invariantEngine with explicit invariantRefs.
     */
    @Deprecated
    void enforceInvariants(String entityName, Object payload) {
        assertCrudInvariantPathAllowed();
        List<String> violations = checkInvariants(entityName, payload);
        if (!violations.isEmpty()) {
            throw new InvariantViolationException(entityName, violations);
        }
    }

    public ExecutionResult execute(String flowName, Object input) {
        return execute(flowName, input, ExecutionContext.anonymous());
    }

    public ExecutionResult execute(String flowName, Object input, ExecutionContext executionContext) {
        ExecutionContext effectiveContext = normalizeExecutionContext(executionContext);
        String executionId = nextId("execution");
        String correlationId = extractCorrelationId(input);
        String traceId = executionId;
        if (flowName == null || flowName.isBlank()) {
            return ExecutionResult.failed(
                    "<unknown>",
                    List.of(),
                    List.of(),
                    "Flow name must be non-blank",
                    executionId,
                    correlationId,
                    traceId
            );
        }

        Optional<FlowDefinition> flowOpt = flowDefinitionProvider.findFlow(flowName);
        if (flowOpt.isEmpty()) {
            return ExecutionResult.failed(
                    flowName,
                    List.of(),
                    List.of(),
                    "Flow not found: " + flowName,
                    executionId,
                    correlationId,
                    traceId
            );
        }

        FlowDefinition flow = flowOpt.get();
        PermissionDecision flowDecision = evaluatePermission(
                effectiveContext,
                new PermissionRequirement("flow.execute", "flow", flow.getName())
        );
        if (!flowDecision.allowed()) {
            String message = flowDecision.message().isBlank()
                    ? "Flow execution denied for '" + flow.getName() + "'"
                    : flowDecision.message();
            return ExecutionResult.forbidden(
                    flow.getName(),
                    message,
                    executionId,
                    correlationId,
                    traceId
            );
        }
        List<InputValidationError> validationErrors = validateInput(flow.getInputSchema(), input);
        if (!validationErrors.isEmpty()) {
            return ExecutionResult.inputValidationFailed(
                    flow.getName(),
                    validationErrors,
                    List.of(),
                    "Input validation failed",
                    executionId,
                    correlationId,
                    traceId
            );
        }
        enforceCorrelationOwnership(correlationId, effectiveContext.tenantId());
        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put("input", input);
        initialState.put("last", input);
        initialState.put("executionId", executionId);
        initialState.put("correlationId", correlationId);
        initialState.put("causationId", "flow:" + flow.getName());
        initialState.put("_npdevEntityName", flow.getEntityName());
        if (effectiveContext.tenantId() != null) {
            initialState.put("tenantId", effectiveContext.tenantId());
        }
        if (effectiveContext.actorId() != null) {
            initialState.put("actorId", effectiveContext.actorId());
        }
        long now = nowEpochMillis();
        FlowInstance initialInstance = FlowInstance.start(
                executionId,
                flow.getName(),
                correlationId,
                effectiveContext.tenantId(),
                effectiveContext.actorId(),
                initialState,
                now
        );
        flowInstanceStore.save(initialInstance);

        return executeFlowInstance(flow, input, initialInstance, 0, traceId, effectiveContext);
    }

    @Override
    public ExecutionResult startFlow(
            String flowName,
            Map<String, Object> input,
            ExecutionContext executionContext
    ) {
        return executeFlow(flowName, input, executionContext);
    }

    public ExecutionResult executeFlow(String flowName, Map<String, Object> input) {
        return executeFlow(flowName, input, ExecutionContext.anonymous());
    }

    public ExecutionResult executeFlow(
            String flowName,
            Map<String, Object> input,
            ExecutionContext executionContext
    ) {
        return execute(
                flowName,
                input == null ? Map.of() : input,
                executionContext == null ? ExecutionContext.anonymous() : executionContext
        );
    }

    public ExecutionResult resumeExecution(String executionId) {
        return resumeExecution(executionId, ExecutionContext.anonymous());
    }

    public ExecutionResult resumeExecution(String executionId, ExecutionContext executionContext) {
        ExecutionContext resumeContext = normalizeExecutionContext(executionContext);
        if (executionId == null || executionId.isBlank()) {
            return ExecutionResult.failed(
                    "<unknown>",
                    List.of(),
                    List.of(),
                    "Execution id must be non-blank",
                    executionId,
                    null,
                    executionId
            );
        }

        Optional<FlowInstance> instanceOpt = flowInstanceStore.findByExecutionId(executionId);
        if (instanceOpt.isEmpty()) {
            return ExecutionResult.failed(
                    "<unknown>",
                    List.of(),
                    List.of(),
                    "Flow instance not found for executionId: " + executionId,
                    executionId,
                    null,
                    executionId
            );
        }

        FlowInstance existing = instanceOpt.get();
        if (existing.status() != FlowInstanceStatus.WAITING_EVENT) {
            return ExecutionResult.failed(
                    existing.flowName(),
                    List.of(),
                    List.of(),
                    "Flow instance is not waiting for event: " + executionId,
                    existing.executionId(),
                    existing.correlationId(),
                    existing.executionId()
            );
        }

        Optional<FlowDefinition> flowOpt = flowDefinitionProvider.findFlow(existing.flowName());
        if (flowOpt.isEmpty()) {
            return ExecutionResult.failed(
                    existing.flowName(),
                    List.of(),
                    List.of(),
                    "Flow not found: " + existing.flowName(),
                    existing.executionId(),
                    existing.correlationId(),
                    existing.executionId()
            );
        }

        Object input = existing.state().get("input");
        return executeFlowInstance(
                flowOpt.get(),
                input,
                existing,
                existing.currentStepIndex(),
                existing.executionId(),
                resumeContext
        );
    }

    @Override
    public FlowEngine.ResumeOutcome resumeFlow(String correlationId, EventEnvelope eventEnvelope) {
        if (eventEnvelope == null) {
            return FlowEngine.ResumeOutcome.noMatch();
        }
        String lookupCorrelationId = normalizeCorrelationId(correlationId);
        if (lookupCorrelationId == null) {
            lookupCorrelationId = eventEnvelope.correlationId();
        }
        return resumeWaitingExecutionsFor(eventEnvelope, null, lookupCorrelationId, ExecutionContext.anonymous());
    }

    public int resumeAllWaitingExecutions() {
        return resumeAllWaitingExecutions(500);
    }

    public int resumeAllWaitingExecutions(int limit) {
        if (eventStore == null) {
            return 0;
        }
        int batchSize = limit <= 0 ? 500 : limit;
        long now = nowEpochMillis();
        List<FlowInstance> waitingSnapshot = flowInstanceStore.findAllWaiting(batchSize * 4);
        if (waitingSnapshot == null || waitingSnapshot.isEmpty()) {
            return 0;
        }

        Set<String> tenants = new LinkedHashSet<>();
        for (FlowInstance instance : waitingSnapshot) {
            if (instance == null) {
                continue;
            }
            tenants.add(normalizeTenantOrDefault(instance.tenantId()));
        }
        if (tenants.isEmpty()) {
            return 0;
        }

        List<FlowInstance> eligible = new ArrayList<>();
        for (String tenant : tenants) {
            eligible.addAll(flowInstanceStore.findWaitingEligibleToResume(tenant, now, batchSize));
        }
        eligible = eligible.stream()
                .sorted(Comparator
                        .comparingLong((FlowInstance instance) -> instance.nextEligibleResumeAtEpochMs() == null
                                ? 0L
                                : instance.nextEligibleResumeAtEpochMs())
                        .thenComparing(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).reversed())
                        .thenComparing(FlowInstance::executionId))
                .limit(batchSize)
                .toList();
        if (eligible.isEmpty()) {
            return 0;
        }

        int resumedCount = 0;
        for (FlowInstance waitingInstance : eligible) {
            if (waitingInstance == null || waitingInstance.status() != FlowInstanceStatus.WAITING_EVENT) {
                continue;
            }
            WaitCriteria waitCriteria = resolveWaitCriteria(waitingInstance);
            if (waitCriteria.awaitEventName() == null || waitCriteria.awaitEventName().isBlank()) {
                FlowInstance latest = flowInstanceStore.findByExecutionId(waitingInstance.executionId())
                        .orElse(waitingInstance);
                persistResumeBackoff(latest, "missing_event", nowEpochMillis());
                continue;
            }

            if (findAwaitedEventForInstance(waitingInstance, waitCriteria, false).isEmpty()) {
                FlowInstance latest = flowInstanceStore.findByExecutionId(waitingInstance.executionId())
                        .orElse(waitingInstance);
                persistResumeBackoff(latest, "missing_event", nowEpochMillis());
                continue;
            }

            try {
                // Resume under the waiting instance's own tenant/actor. The awaited event is
                // tenant-scoped, so resuming with an anonymous context would look it up under the
                // default tenant and never match a tenant-scoped event, leaving the flow stuck.
                ExecutionResult result = resumeExecution(
                        waitingInstance.executionId(),
                        ExecutionContext.of(waitingInstance.tenantId(), waitingInstance.actorId())
                );
                if (result.getStatus() == ExecutionStatus.WAITING_EVENT) {
                    FlowInstance latest = flowInstanceStore.findByExecutionId(waitingInstance.executionId())
                            .orElse(waitingInstance);
                    persistResumeBackoff(latest, "missing_event", nowEpochMillis());
                } else {
                    resumedCount++;
                }
            } catch (RuntimeException runtimeException) {
                FlowInstance latest = flowInstanceStore.findByExecutionId(waitingInstance.executionId())
                        .orElse(waitingInstance);
                persistResumeBackoff(
                        latest,
                        "exception:" + runtimeException.getClass().getSimpleName(),
                        nowEpochMillis()
                );
            }
        }

        return resumedCount;
    }

    public void onEventPersisted(EventEnvelope eventEnvelope) {
        resumeFlow(eventEnvelope == null ? null : eventEnvelope.correlationId(), eventEnvelope);
    }

    private ExecutionResult executeFlowInstance(
            FlowDefinition flow,
            Object input,
            FlowInstance initialInstance,
            int startStepIndex,
            String traceId,
            ExecutionContext executionContext
    ) {
        Map<String, Object> state = new LinkedHashMap<>(initialInstance.state());
        String executionId = initialInstance.executionId();
        String correlationId = initialInstance.correlationId();
        List<EventEnvelope> emittedEvents = new ArrayList<>();
        List<StepTrace> stepTraces = new ArrayList<>();
        long flowStartedAt = nowEpochMillis();
        int safeStartStepIndex = Math.max(0, startStepIndex);

        FlowTraceMeta traceMeta = new FlowTraceMeta(
                executionId,
                correlationId,
                flow.getName(),
                initialInstance.tenantId(),
                initialInstance.actorId(),
                Map.of("startStepIndex", safeStartStepIndex)
        );
        executionTracer.onFlowStart(traceMeta, flowStartedAt);

        final FlowInstance[] currentInstance = new FlowInstance[]{initialInstance};
        StepProgressRecorder progressRecorder = (nextStepIndex, currentState) -> {
            FlowInstance running = currentInstance[0].markRunning(nextStepIndex, currentState, nowEpochMillis());
            flowInstanceStore.update(running);
            currentInstance[0] = running;
        };

        StepOutcome flowOutcome = StepOutcome.OK;
        try {
            currentFlowContext.set(flow.getName());

            if (safeStartStepIndex >= flow.getSteps().size()) {
                FlowInstance completed = currentInstance[0].markCompleted(state, nowEpochMillis());
                flowInstanceStore.update(completed);
                currentInstance[0] = completed;
                Object output = state.get("last");
                return ExecutionResult.ok(
                        flow.getName(),
                        output,
                        emittedEvents,
                        executionId,
                        correlationId,
                        traceId
                );
            }

            StepExecutionOutcome outcome = executeSteps(
                    flow,
                    flow.getSteps().subList(safeStartStepIndex, flow.getSteps().size()),
                    input,
                    state,
                    emittedEvents,
                    traceMeta,
                    stepTraces,
                    executionId,
                    correlationId,
                    safeStartStepIndex,
                    progressRecorder,
                    executionContext
            );

            if (outcome.failedResult() != null) {
                flowOutcome = StepOutcome.FAILED;
                ExecutionResult failure = outcome.failedResult();
                if (failure.getStatus() == ExecutionStatus.WAITING_EVENT) {
                    int waitingStepIndex = failure.getAwaitedStepIndex() == null
                            ? safeStartStepIndex
                            : Math.max(0, failure.getAwaitedStepIndex());
                    FlowInstance waiting = currentInstance[0].markWaiting(
                            waitingStepIndex,
                            Objects.requireNonNull(failure.getAwaitedEventName(), "awaitedEventName is required"),
                            state,
                            nowEpochMillis()
                    );
                    flowInstanceStore.update(waiting);
                    currentInstance[0] = waiting;
                } else {
                    Integer failedIndex = failure.getCapabilityStepIndex();
                    if (failedIndex == null) {
                        failedIndex = failure.getAwaitedStepIndex();
                    }
                    if (failedIndex == null && !failure.getInvariantViolations().isEmpty()) {
                        failedIndex = failure.getInvariantViolations().get(0).stepIndex();
                    }
                    int marker = failedIndex == null ? currentInstance[0].currentStepIndex() : Math.max(0, failedIndex);
                    FlowInstance failedMarker = currentInstance[0].markRunning(marker, state, nowEpochMillis());
                    FlowInstance failed = switch (resolveFailureTerminalStatus(failure)) {
                        case FAILED_PERMANENT -> failedMarker.markFailedPermanent(
                                state,
                                nowEpochMillis(),
                                failure.getFailureInfo()
                        );
                        case STUCK -> failedMarker.markStuck(
                                state,
                                nowEpochMillis(),
                                failure.getFailureInfo()
                        );
                        case FAILED -> failedMarker.markFailed(
                                state,
                                nowEpochMillis(),
                                failure.getFailureInfo()
                        );
                        default -> failedMarker.markFailed(state, nowEpochMillis(), failure.getFailureInfo());
                    };
                    flowInstanceStore.update(failed);
                    currentInstance[0] = failed;
                    emitOperationalFailureEvent(failed);
                }
                return failure;
            }

            Object output = outcome.returned() ? outcome.returnValue() : state.get("last");
            FlowInstance completed = currentInstance[0].markCompleted(state, nowEpochMillis());
            flowInstanceStore.update(completed);
            currentInstance[0] = completed;

            return ExecutionResult.ok(
                    flow.getName(),
                    output,
                    emittedEvents,
                    executionId,
                    correlationId,
                    traceId
            );
        } catch (RuntimeException runtimeException) {
            flowOutcome = StepOutcome.FAILED;
            FlowInstance failed = currentInstance[0].markFailed(
                    state,
                    nowEpochMillis(),
                    FailureInfo.of(
                            ErrorKind.SYSTEM,
                            FailureCodes.SYSTEM_EXCEPTION,
                            runtimeException.getMessage() == null
                                    ? "Unhandled kernel execution error"
                                    : runtimeException.getMessage(),
                            Map.of("exceptionType", runtimeException.getClass().getSimpleName())
                    )
            );
            flowInstanceStore.update(failed);
            currentInstance[0] = failed;
            emitOperationalFailureEvent(failed);
            throw runtimeException;
        } finally {
            currentFlowContext.remove();
            executionTracer.onFlowEnd(
                    new FlowTrace(
                            traceMeta,
                            flowStartedAt,
                            nowEpochMillis(),
                            flowOutcome,
                            List.copyOf(stepTraces)
                    )
            );
        }
    }

    private StepExecutionOutcome executeSteps(
            FlowDefinition flow,
            List<FlowStepDefinition> steps,
            Object input,
            Map<String, Object> state,
            List<EventEnvelope> emittedEvents,
            FlowTraceMeta traceMeta,
            List<StepTrace> stepTraces,
            String executionId,
            String defaultCorrelationId,
            int stepIndexOffset,
            StepProgressRecorder progressRecorder,
            ExecutionContext executionContext
    ) {
        ExecutionContext effectiveContext = normalizeExecutionContext(executionContext);
        for (FlowStepDefinition step : steps) {
            int traceStepIndex = stepIndexOffset + stepTraces.size();
            long stepStartedAt = nowEpochMillis();
            executionTracer.onStepStart(
                    traceMeta,
                    traceStepIndex,
                    step.getName(),
                    step.getType().name(),
                    stepStartedAt
            );
            Map<String, Object> stateBefore = new LinkedHashMap<>(state);
            Map<String, Object> stepInfo = new LinkedHashMap<>();
            try {
                switch (step.getType()) {
                    case INVARIANT_CHECK -> {
                        Object payload = resolveReference(step.getInputRef(), state, input);
                        String conceptName = resolveInvariantConceptName(step, flow);
                        List<String> invariantRefs = step.getInvariants();
                        stepInfo.put("checkpoint", step.getCheckpoint() == null ? null : step.getCheckpoint().name());
                        stepInfo.put("requestedInvariantRefs", invariantRefs == null ? List.of() : List.copyOf(invariantRefs));
                        if (invariantRefs == null || invariantRefs.isEmpty()) {
                            InvariantEngine.Violation missingRefsViolation = new InvariantEngine.Violation(
                                    "MODEL_INVALID",
                                    "Invariant step must declare explicit invariant refs or be compiled with scope expansion",
                                    "<none>",
                                    conceptName,
                                    flow.getName(),
                                    step.getName(),
                                    traceStepIndex,
                                    Map.of("stepType", "INVARIANT_CHECK")
                            );
                            traceFailedStep(
                                    traceMeta,
                                    step,
                                    traceStepIndex,
                                    stepStartedAt,
                                    stateBefore,
                                    state,
                                    stepInfo,
                                    List.of(missingRefsViolation),
                                    null,
                                    stepTraces
                            );
                            return StepExecutionOutcome.failed(ExecutionResult.invariantFailed(
                                    flow.getName(),
                                    List.of(missingRefsViolation),
                                    emittedEvents,
                                    "Invariant checkpoint failed at step: " + step.getName(),
                                    executionId,
                                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                                    executionId
                            ));
                        }

                        InvariantEngine.InvariantEvaluationResult evalResult = invariantEngine.evaluate(
                                new InvariantEngine.InvariantEvaluationRequest(
                                        conceptName,
                                        payload,
                                        invariantRefs,
                                        new InvariantEngine.EvaluationMetadata(
                                                flow.getName(),
                                                step.getName(),
                                                traceStepIndex,
                                                Objects.requireNonNull(step.getCheckpoint(), "Invariant checkpoint is required"),
                                                Objects.toString(state.get("correlationId"), defaultCorrelationId)
                                        ),
                                        state
                                )
                        );
                        List<InvariantEngine.Violation> violations = enrichInvariantViolations(
                                evalResult.violations(),
                                conceptName,
                                flow.getName(),
                                step.getName(),
                                traceStepIndex,
                                invariantRefs
                        );
                        if (!violations.isEmpty()) {
                            traceFailedStep(
                                    traceMeta,
                                    step,
                                    traceStepIndex,
                                    stepStartedAt,
                                    stateBefore,
                                    state,
                                    stepInfo,
                                    violations,
                                    null,
                                    stepTraces
                            );
                            return StepExecutionOutcome.failed(ExecutionResult.invariantFailed(
                                    flow.getName(),
                                    violations,
                                    emittedEvents,
                                    "Invariant checkpoint failed at step: " + step.getName(),
                                    executionId,
                                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                                    executionId
                            ));
                        }
                    }
                    case CAPABILITY_CALL -> {
                        List<Object> args = resolveArgs(step, state, input);

                        PermissionDecision capabilityDecision = evaluatePermission(
                                effectiveContext,
                                new PermissionRequirement("capability.invoke", "capability", step.getCapability())
                        );
                        if (!capabilityDecision.allowed()) {
                            CapabilityError capabilityError = new CapabilityError(
                                    "CAPABILITY_PERMISSION_DENIED",
                                    capabilityDecision.message().isBlank()
                                            ? "Capability invocation denied"
                                            : capabilityDecision.message(),
                                    CapabilityErrorKind.AUTH,
                                    Map.of(
                                            "capability", step.getCapability(),
                                            "operation", step.getOperation(),
                                            "adapterId", step.getCapabilityAdapterId(),
                                            "permissionCode", capabilityDecision.code()
                                    )
                            );
                            traceFailedStep(
                                    traceMeta,
                                    step,
                                    traceStepIndex,
                                    stepStartedAt,
                                    stateBefore,
                                    state,
                                    stepInfo,
                                    List.of(),
                                    capabilityError,
                                    stepTraces
                            );
                            return StepExecutionOutcome.failed(ExecutionResult.capabilityFailed(
                                    flow.getName(),
                                    emittedEvents,
                                    step.getName(),
                                    traceStepIndex,
                                    step.getCapability(),
                                    step.getOperation(),
                                    step.getCapabilityAdapterId(),
                                    capabilityError,
                                    executionId,
                                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                                    executionId
                            ));
                        }

                        // Capability contract validation: input shape must be validated before dispatch.
                        List<InputValidationError> capabilityInputErrors = validateInput(step.getCapabilityInputSchema(),
                                args == null || args.isEmpty() ? null : args.get(0));
                        if (!capabilityInputErrors.isEmpty()) {
                            CapabilityError capabilityError = new CapabilityError(
                                    "CAPABILITY_CONTRACT_INPUT_INVALID",
                                    "Capability input validation failed",
                                    CapabilityErrorKind.CONTRACT,
                                    Map.of(
                                            "capability", step.getCapability(),
                                            "operation", step.getOperation(),
                                            "adapterId", step.getCapabilityAdapterId(),
                                            "validationErrors", capabilityInputErrors
                                    )
                            );
                            traceFailedStep(
                                    traceMeta,
                                    step,
                                    traceStepIndex,
                                    stepStartedAt,
                                    stateBefore,
                                    state,
                                    stepInfo,
                                    List.of(),
                                    capabilityError,
                                    stepTraces
                            );
                            return StepExecutionOutcome.failed(ExecutionResult.capabilityFailed(
                                    flow.getName(),
                                    emittedEvents,
                                    step.getName(),
                                    traceStepIndex,
                                    step.getCapability(),
                                    step.getOperation(),
                                    step.getCapabilityAdapterId(),
                                    capabilityError,
                                    executionId,
                                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                                    executionId
                            ));
                        }

                        String correlationId = Objects.toString(state.get("correlationId"), null);
                        stepInfo.put("capability", step.getCapability());
                        stepInfo.put("operation", step.getOperation());
                        stepInfo.put("adapterId", step.getCapabilityAdapterId());
                        CapabilityExecutionPolicy policy = step.getCapabilityExecutionPolicy() == null
                                ? CapabilityExecutionPolicy.defaults()
                                : step.getCapabilityExecutionPolicy();
                        EffectiveCapabilityPolicy effectivePolicy = resolveEffectiveCapabilityPolicy(
                                step,
                                policy,
                                normalizeTenantOrDefault(effectiveContext.tenantId())
                        );
                        stepInfo.put("retryCount", effectivePolicy.retryMaxAttempts());
                        stepInfo.put("retryDelayMs", effectivePolicy.retryBaseDelayMs());
                        stepInfo.put("retryMaxDelayMs", effectivePolicy.retryMaxDelayMs());
                        stepInfo.put("timeoutMs", effectivePolicy.timeoutMs());
                        stepInfo.put("circuitOpenAfterFailures", effectivePolicy.circuitOpenAfterFailures());
                        stepInfo.put("circuitOpenMs", effectivePolicy.circuitOpenMs());
                        stepInfo.put("bulkheadMaxConcurrent", effectivePolicy.bulkheadMaxConcurrent());
                        stepInfo.put("cacheIdempotencyFailures", effectivePolicy.cacheIdempotencyFailures());
                        stepInfo.put("failureClassification",
                                policy.failureClassification() == null ? null : policy.failureClassification().name());

                        String idempotencyKey = resolveIdempotencyKey(policy, state, input);
                        if (idempotencyKey != null) {
                            stepInfo.put("idempotencyKey", idempotencyKey);
                        }

                        CapabilityResult capabilityResult = invokeCapabilityWithPolicy(
                                step,
                                args,
                                state,
                                correlationId,
                                idempotencyKey,
                                policy,
                                effectivePolicy,
                                stepInfo,
                                normalizeTenantOrDefault(effectiveContext.tenantId())
                        );
                        if (!capabilityResult.ok()) {
                            CapabilityError capabilityError = capabilityResult.error();
                            if (capabilityError == null) {
                                capabilityError = new CapabilityError(
                                        "CAPABILITY_RESULT_INVALID",
                                        "Capability dispatcher returned failed result without error",
                                        CapabilityErrorKind.PERMANENT,
                                        Map.of(
                                                "capability", step.getCapability(),
                                                "operation", step.getOperation(),
                                                "adapterId", step.getCapabilityAdapterId()
                                        )
                                );
                            } else {
                                CapabilityErrorKind overriddenKind = policy.applyFailureClassification(capabilityError.kind());
                                if (overriddenKind != capabilityError.kind()) {
                                    capabilityError = new CapabilityError(
                                            capabilityError.code(),
                                            capabilityError.message(),
                                            overriddenKind,
                                            capabilityError.details()
                                    );
                                }
                            }
                            // A database integrity violation (a bond/FK to a missing row, or a unique
                            // conflict) is a caller CONTRACT failure, not a system error. The persistence
                            // adapter surfaces it with a stable message signature; recover the CONTRACT
                            // classification here in case an intermediate sandbox/dispatch wrapper widened
                            // it to a generic kind (which would otherwise report system_exception).
                            capabilityError = reclassifyIntegrityViolation(capabilityError);
                            traceFailedStep(
                                    traceMeta,
                                    step,
                                    traceStepIndex,
                                    stepStartedAt,
                                    stateBefore,
                                    state,
                                    stepInfo,
                                    List.of(),
                                    capabilityError,
                                    stepTraces
                            );
                            return StepExecutionOutcome.failed(ExecutionResult.capabilityFailed(
                                    flow.getName(),
                                    emittedEvents,
                                    step.getName(),
                                    traceStepIndex,
                                    step.getCapability(),
                                    step.getOperation(),
                                    step.getCapabilityAdapterId(),
                                    capabilityError,
                                    executionId,
                                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                                    executionId
                            ));
                        }
                        Object output = capabilityResult.value();

                        // Capability contract validation: output shape must be validated after dispatch.
                        List<InputValidationError> capabilityOutputErrors = validateInput(step.getCapabilityOutputSchema(), output);
                        if (!capabilityOutputErrors.isEmpty()) {
                            CapabilityError capabilityError = new CapabilityError(
                                    "CAPABILITY_CONTRACT_OUTPUT_INVALID",
                                    "Capability output validation failed",
                                    CapabilityErrorKind.CONTRACT,
                                    Map.of(
                                            "capability", step.getCapability(),
                                            "operation", step.getOperation(),
                                            "adapterId", step.getCapabilityAdapterId(),
                                            "validationErrors", capabilityOutputErrors
                                    )
                            );
                            traceFailedStep(
                                    traceMeta,
                                    step,
                                    traceStepIndex,
                                    stepStartedAt,
                                    stateBefore,
                                    state,
                                    stepInfo,
                                    List.of(),
                                    capabilityError,
                                    stepTraces
                            );
                            return StepExecutionOutcome.failed(ExecutionResult.capabilityFailed(
                                    flow.getName(),
                                    emittedEvents,
                                    step.getName(),
                                    traceStepIndex,
                                    step.getCapability(),
                                    step.getOperation(),
                                    step.getCapabilityAdapterId(),
                                    capabilityError,
                                    executionId,
                                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                                    executionId
                            ));
                        }


                        String outputRef = normalizeRef(step.getOutputRef());
                        if (!outputRef.isBlank()) {
                            state.put(outputRef, output);
                        }
                        state.put("last", output);
                    }
                    case EMIT_EVENT -> {
                        Object eventPayload = buildEventPayload(step, state, input);
                        if (step.getEventName() != null) {
                            java.util.Optional<com.npdev.kernel.schema.SchemaObject> eventSchemaOpt = eventSchemaProvider.findEventPayloadSchema(step.getEventName());
                            if (eventSchemaOpt.isPresent()) {
                                List<InputValidationError> errors = schemaValidator.validate(eventSchemaOpt.get(), eventPayload);
                                if (errors != null && !errors.isEmpty()) {
                                    traceFailedStep(
                                            traceMeta,
                                            step,
                                            traceStepIndex,
                                            stepStartedAt,
                                            stateBefore,
                                            state,
                                            stepInfo,
                                            List.of(),
                                            null,
                                            stepTraces
                                    );
                                    String currentCorrelationId = Objects.toString(state.get("correlationId"), defaultCorrelationId);
                                    return StepExecutionOutcome.failed(ExecutionResult.eventPayloadInvalid(
                                            flow.getName(),
                                            emittedEvents,
                                            step.getName(),
                                            traceStepIndex,
                                            step.getEventName(),
                                            errors,
                                            executionId,
                                            currentCorrelationId,
                                            executionId
                                    ));
                                }
                            }
                        }
                        Map<String, Object> envelopePayload = toEventPayloadMap(eventPayload);
                        String currentCorrelationId = Objects.toString(state.get("correlationId"), defaultCorrelationId);
                        String currentCausationId = executionId;
                        EventEnvelope envelope = newEnvelope(
                                nextId("event"),
                                Objects.requireNonNull(step.getEventName(), "eventName is required"),
                                currentCorrelationId,
                                currentCausationId,
                                envelopePayload,
                                Map.of(
                                        "flow", flow.getName(),
                                        "step", step.getName(),
                                        "stepIndex", traceStepIndex
                                ),
                                flow.getName(),
                                traceStepIndex,
                                effectiveContext.tenantId(),
                                effectiveContext.actorId()
                        );
                        if (eventStore == null) {
                            traceFailedStep(
                                    traceMeta,
                                    step,
                                    traceStepIndex,
                                    stepStartedAt,
                                    stateBefore,
                                    state,
                                    stepInfo,
                                    List.of(),
                                    null,
                                    stepTraces
                            );
                            return StepExecutionOutcome.failed(ExecutionResult.eventPersistFailed(
                                    flow.getName(),
                                    emittedEvents,
                                    step.getName(),
                                    traceStepIndex,
                                    step.getEventName(),
                                    "EventStore is required for emitEvent but was not configured",
                                    executionId,
                                    currentCorrelationId,
                                    executionId
                            ));
                        }
                        try {
                            eventStore.append(envelope);
                        } catch (RuntimeException runtimeException) {
                            traceFailedStep(
                                    traceMeta,
                                    step,
                                    traceStepIndex,
                                    stepStartedAt,
                                    stateBefore,
                                    state,
                                    stepInfo,
                                    List.of(),
                                    null,
                                    stepTraces
                            );
                            return StepExecutionOutcome.failed(ExecutionResult.eventPersistFailed(
                                    flow.getName(),
                                    emittedEvents,
                                    step.getName(),
                                    traceStepIndex,
                                    step.getEventName(),
                                    runtimeException.getMessage() == null
                                            ? "EventStore append failed"
                                            : runtimeException.getMessage(),
                                    executionId,
                                    currentCorrelationId,
                                    executionId
                            ));
                        }
                        eventBus.publish(envelope);
                        resumeWaitingExecutionsFor(envelope, executionId, envelope.correlationId(), effectiveContext);
                        emittedEvents.add(envelope);
                        state.put("lastEvent", envelope);
                        state.put("causationId", executionId);
                        stepInfo.put("emittedEventName", envelope.eventName());
                        stepInfo.put("emittedEventId", envelope.eventId());
                    }
                    case SCHEDULE_EVENT -> {
                        Object eventPayload = buildEventPayload(step, state, input);
                        if (step.getEventName() != null) {
                            java.util.Optional<com.npdev.kernel.schema.SchemaObject> eventSchemaOpt = eventSchemaProvider.findEventPayloadSchema(step.getEventName());
                            if (eventSchemaOpt.isPresent()) {
                                List<InputValidationError> errors = schemaValidator.validate(eventSchemaOpt.get(), eventPayload);
                                if (errors != null && !errors.isEmpty()) {
                                    traceFailedStep(
                                            traceMeta,
                                            step,
                                            traceStepIndex,
                                            stepStartedAt,
                                            stateBefore,
                                            state,
                                            stepInfo,
                                            List.of(),
                                            null,
                                            stepTraces
                                    );
                                    String currentCorrelationId = Objects.toString(state.get("correlationId"), defaultCorrelationId);
                                    return StepExecutionOutcome.failed(ExecutionResult.eventPayloadInvalid(
                                            flow.getName(),
                                            emittedEvents,
                                            step.getName(),
                                            traceStepIndex,
                                            step.getEventName(),
                                            errors,
                                            executionId,
                                            currentCorrelationId,
                                            executionId
                                    ));
                                }
                            }
                        }
                        Map<String, Object> envelopePayload = toEventPayloadMap(eventPayload);
                        String currentCorrelationId = Objects.toString(state.get("correlationId"), defaultCorrelationId);
                        String currentCausationId = executionId;
                        long safeDelaySeconds = step.getDelaySeconds() == null ? 0L : Math.max(0L, step.getDelaySeconds());
                        long scheduledForEpochMs = nowEpochMillis() + (safeDelaySeconds * 1000L);
                        EventEnvelope envelope = newEnvelope(
                                nextId("event"),
                                Objects.requireNonNull(step.getEventName(), "eventName is required"),
                                currentCorrelationId,
                                currentCausationId,
                                envelopePayload,
                                Map.of(
                                        "flow", flow.getName(),
                                        "step", step.getName(),
                                        "stepIndex", traceStepIndex,
                                        "deliveryMode", "scheduled",
                                        "delaySeconds", safeDelaySeconds,
                                        "scheduledForEpochMs", scheduledForEpochMs
                                ),
                                flow.getName(),
                                traceStepIndex,
                                effectiveContext.tenantId(),
                                effectiveContext.actorId()
                        );
                        if (eventStore == null) {
                            traceFailedStep(
                                    traceMeta,
                                    step,
                                    traceStepIndex,
                                    stepStartedAt,
                                    stateBefore,
                                    state,
                                    stepInfo,
                                    List.of(),
                                    null,
                                    stepTraces
                            );
                            return StepExecutionOutcome.failed(ExecutionResult.eventPersistFailed(
                                    flow.getName(),
                                    emittedEvents,
                                    step.getName(),
                                    traceStepIndex,
                                    step.getEventName(),
                                    "EventStore is required for scheduleEvent but was not configured",
                                    executionId,
                                    currentCorrelationId,
                                    executionId
                            ));
                        }
                        try {
                            eventStore.append(envelope);
                        } catch (RuntimeException runtimeException) {
                            traceFailedStep(
                                    traceMeta,
                                    step,
                                    traceStepIndex,
                                    stepStartedAt,
                                    stateBefore,
                                    state,
                                    stepInfo,
                                    List.of(),
                                    null,
                                    stepTraces
                            );
                            return StepExecutionOutcome.failed(ExecutionResult.eventPersistFailed(
                                    flow.getName(),
                                    emittedEvents,
                                    step.getName(),
                                    traceStepIndex,
                                    step.getEventName(),
                                    runtimeException.getMessage() == null
                                            ? "Scheduled event append failed"
                                            : runtimeException.getMessage(),
                                    executionId,
                                    currentCorrelationId,
                                    executionId
                            ));
                        }
                        eventBus.publish(envelope);
                        resumeWaitingExecutionsFor(envelope, executionId, envelope.correlationId(), effectiveContext);
                        emittedEvents.add(envelope);
                        state.put("lastEvent", envelope);
                        state.put("causationId", executionId);
                        stepInfo.put("emittedEventName", envelope.eventName());
                        stepInfo.put("emittedEventId", envelope.eventId());
                        stepInfo.put("deliveryMode", "scheduled");
                        stepInfo.put("delaySeconds", safeDelaySeconds);
                        stepInfo.put("scheduledForEpochMs", scheduledForEpochMs);
                    }
                    case BRANCH -> {
                        boolean branchResult = evaluateCondition(step.getCondition(), state, input);
                        stepInfo.put("branchResult", branchResult ? "then" : "else");
                        List<FlowStepDefinition> nestedSteps = branchResult ? step.getThenSteps() : step.getElseSteps();
                        if (!nestedSteps.isEmpty()) {
                            StepExecutionOutcome nested = executeSteps(
                                    flow,
                                    nestedSteps,
                                    input,
                                    state,
                                    emittedEvents,
                                    traceMeta,
                                    stepTraces,
                                    executionId,
                                    defaultCorrelationId,
                                    stepIndexOffset,
                                    progressRecorder,
                                    effectiveContext
                            );
                            if (nested.failedResult() != null) {
                                ExecutionResult nestedFailure = nested.failedResult();
                                traceFailedStep(
                                        traceMeta,
                                        step,
                                        traceStepIndex,
                                        stepStartedAt,
                                        stateBefore,
                                        state,
                                        stepInfo,
                                        nestedFailure.getInvariantViolations(),
                                        nestedFailure.getCapabilityError(),
                                        stepTraces
                                );
                                return nested;
                            }
                            if (nested.returned()) {
                                traceSuccessfulStep(
                                        traceMeta,
                                        step,
                                        traceStepIndex,
                                        stepStartedAt,
                                        stateBefore,
                                        state,
                                        stepInfo,
                                        stepTraces
                                );
                                progressRecorder.onStepCompleted(traceStepIndex + 1, state);
                                return nested;
                            }
                        }
                    }
                    case AWAIT_EVENT -> {
                        EventEnvelope awaited = awaitEvent(
                                step,
                                state,
                                defaultCorrelationId,
                                input,
                                effectiveContext.tenantId(),
                                executionId
                        );
                        stepInfo.put("awaitedEventName", step.getAwaitEventName());
                        if (awaited == null) {
                            String waitingCorrelationId = Objects.toString(state.get("correlationId"), defaultCorrelationId);
                            String awaitRef = normalizeRef(step.getAwaitRef());
                            if (awaitRef.isBlank()) {
                                awaitRef = "awaitedEvent";
                            }
                            state.put(
                                    AWAIT_STATE_KEY,
                                    buildAwaitState(
                                            step,
                                            traceStepIndex,
                                            awaitRef
                                    )
                            );
                            stepInfo.put("awaitedEventStatus", "WAITING");
                            traceFailedStep(
                                    traceMeta,
                                    step,
                                    traceStepIndex,
                                    stepStartedAt,
                                    stateBefore,
                                    state,
                                    stepInfo,
                                    List.of(),
                                    null,
                                    stepTraces
                            );
                            return StepExecutionOutcome.failed(ExecutionResult.waitingEvent(
                                    flow.getName(),
                                    emittedEvents,
                                    step.getName(),
                                    traceStepIndex,
                                    step.getAwaitEventName(),
                                    waitingCorrelationId,
                                    "Awaited event not found for step: " + step.getName()
                                            + " eventName=" + step.getAwaitEventName()
                                            + " correlationId=" + waitingCorrelationId
                                            + " matchCorrelation=" + step.isAwaitMatchCorrelation()
                                            + " payloadMatchRefs=" + step.getAwaitPayloadMatchRefs(),
                                    executionId,
                                    waitingCorrelationId,
                                    executionId
                            ));
                        }
                        stepInfo.put("awaitedEventFoundEventId", awaited.eventId());

                        String awaitRef = normalizeRef(step.getAwaitRef());
                        if (awaitRef.isBlank()) {
                            awaitRef = "awaitedEvent";
                        }
                        state.remove(AWAIT_STATE_KEY);
                        state.put(awaitRef, awaited.payload());
                        state.put(awaitRef + "Envelope", awaited);
                        state.put("last", awaited.payload());
                        state.put("lastEvent", awaited);
                        state.put("causationId", awaited.eventId());
                    }
                    case MAP -> {
                        Object mappedValue = resolveReference(step.getMapFromRef(), state, input);
                        String mapToRef = normalizeRef(step.getMapToRef());
                        if (mapToRef.isBlank()) {
                            traceFailedStep(
                                    traceMeta,
                                    step,
                                    traceStepIndex,
                                    stepStartedAt,
                                    stateBefore,
                                    state,
                                    stepInfo,
                                    List.of(),
                                    null,
                                    stepTraces
                            );
                            return StepExecutionOutcome.failed(ExecutionResult.failed(
                                    flow.getName(),
                                    List.of(),
                                    emittedEvents,
                                    "Map step missing output target: " + step.getName(),
                                    executionId,
                                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                                    executionId
                            ));
                        }
                        state.put(mapToRef, mappedValue);
                        state.put("last", mappedValue);
                        stepInfo.put("mapFromRef", step.getMapFromRef());
                        stepInfo.put("mapToRef", step.getMapToRef());
                    }
                    case RETURN -> {
                        Object returnValue = resolveReference(step.getReturnRef(), state, input);
                        state.put("last", returnValue);
                        stepInfo.put("returnRef", step.getReturnRef());
                        traceSuccessfulStep(
                                traceMeta,
                                step,
                                traceStepIndex,
                                stepStartedAt,
                                stateBefore,
                                state,
                                stepInfo,
                                stepTraces
                        );
                        progressRecorder.onStepCompleted(traceStepIndex + 1, state);
                        return StepExecutionOutcome.returned(returnValue);
                    }
                    default -> {
                        stepInfo.put("unsupportedType", String.valueOf(step.getType()));
                        traceFailedStep(
                                traceMeta,
                                step,
                                traceStepIndex,
                                stepStartedAt,
                                stateBefore,
                                state,
                                stepInfo,
                                List.of(),
                                null,
                                stepTraces
                        );
                        return StepExecutionOutcome.failed(ExecutionResult.failed(
                                flow.getName(),
                                List.of(),
                                emittedEvents,
                                "Unsupported step type: " + step.getType(),
                                executionId,
                                Objects.toString(state.get("correlationId"), defaultCorrelationId),
                                executionId
                        ));
                    }
                }
            } catch (RuntimeException runtimeException) {
                stepInfo.put("exceptionType", runtimeException.getClass().getName());
                if (runtimeException.getMessage() != null && !runtimeException.getMessage().isBlank()) {
                    stepInfo.put("exceptionMessage", runtimeException.getMessage());
                }
                traceFailedStep(
                        traceMeta,
                        step,
                        traceStepIndex,
                        stepStartedAt,
                        stateBefore,
                        state,
                        stepInfo,
                        List.of(),
                        null,
                        stepTraces
                );
                throw runtimeException;
            }
            traceSuccessfulStep(
                    traceMeta,
                    step,
                    traceStepIndex,
                    stepStartedAt,
                    stateBefore,
                    state,
                    stepInfo,
                    stepTraces
            );
            progressRecorder.onStepCompleted(traceStepIndex + 1, state);
        }
        return StepExecutionOutcome.continueFlow();
    }

    private FlowEngine.ResumeOutcome resumeWaitingExecutionsFor(
            EventEnvelope envelope,
            String currentExecutionId,
            String lookupCorrelationId,
            ExecutionContext resumeExecutionContext
    ) {
        if (envelope == null) {
            return FlowEngine.ResumeOutcome.noMatch();
        }
        long now = nowEpochMillis();
        List<FlowInstance> waitingInstances = collectWaitingCandidates(lookupCorrelationId, envelope.eventName());
        if (waitingInstances.isEmpty()) {
            return FlowEngine.ResumeOutcome.noMatch();
        }

        int matchedWaiters = 0;
        int resumedWaiters = 0;
        List<String> resumedExecutionIds = new ArrayList<>();
        for (FlowInstance instance : waitingInstances) {
            if (instance == null) {
                continue;
            }
            if (currentExecutionId != null && currentExecutionId.equals(instance.executionId())) {
                continue;
            }
            if (!instance.isResumeEligible(now)) {
                continue;
            }
            if (!matchesWaitingResumeCriteria(instance, envelope)) {
                continue;
            }
            matchedWaiters++;
            try {
                ExecutionResult result = resumeExecution(
                        instance.executionId(),
                        resumeExecutionContext == null ? ExecutionContext.anonymous() : resumeExecutionContext
                );
                if (result.getStatus() == ExecutionStatus.WAITING_EVENT) {
                    // Event-driven mismatches must be a no-op. Backoff is only for scheduled scans.
                    continue;
                }
                resumedWaiters++;
                resumedExecutionIds.add(instance.executionId());
            } catch (RuntimeException runtimeException) {
                FlowInstance latest = flowInstanceStore.findByExecutionId(instance.executionId()).orElse(instance);
                persistResumeBackoff(
                        latest,
                        "exception:" + runtimeException.getClass().getSimpleName(),
                        nowEpochMillis()
                );
            }
        }

        if (matchedWaiters == 0 && resumedWaiters == 0) {
            return FlowEngine.ResumeOutcome.noMatch();
        }
        return new FlowEngine.ResumeOutcome(matchedWaiters, resumedWaiters, resumedExecutionIds);
    }

    private List<FlowInstance> collectWaitingCandidates(String correlationId, String eventName) {
        Map<String, FlowInstance> byExecutionId = new LinkedHashMap<>();
        String normalizedCorrelationId = normalizeCorrelationId(correlationId);
        if (normalizedCorrelationId != null) {
            for (FlowInstance instance : flowInstanceStore.findWaitingByCorrelation(normalizedCorrelationId)) {
                if (instance != null) {
                    byExecutionId.put(instance.executionId(), instance);
                }
            }
        }
        if (eventName != null && !eventName.isBlank()) {
            for (FlowInstance instance : flowInstanceStore.findWaitingByEvent(eventName)) {
                if (instance != null) {
                    byExecutionId.put(instance.executionId(), instance);
                }
            }
        }
        if (byExecutionId.isEmpty()) {
            return List.of();
        }
        return byExecutionId.values().stream()
                .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).thenComparing(FlowInstance::executionId))
                .toList();
    }

    private boolean matchesWaitingResumeCriteria(FlowInstance waitingInstance, EventEnvelope envelope) {
        if (waitingInstance == null || envelope == null) {
            return false;
        }
        if (waitingInstance.status() != FlowInstanceStatus.WAITING_EVENT) {
            return false;
        }
        if (!sameTenant(envelope.tenantId(), waitingInstance.tenantId())) {
            return false;
        }
        WaitCriteria waitCriteria = resolveWaitCriteria(waitingInstance);
        if (waitCriteria.awaitEventName() == null || waitCriteria.awaitEventName().isBlank()) {
            return false;
        }
        if (!waitCriteria.awaitEventName().equals(envelope.eventName())) {
            return false;
        }
        if (waitCriteria.stepIndex() >= 0 && waitingInstance.currentStepIndex() != waitCriteria.stepIndex()) {
            return false;
        }
        if (waitCriteria.matchCorrelation() && !matchesCorrelation(envelope, waitingInstance.correlationId())) {
            return false;
        }
        if (!matchesAwaitPayload(
                waitCriteria.payloadMatchRefs(),
                envelope,
                waitingInstance.state(),
                waitingInstance.state().get("input")
        )) {
            return false;
        }
        return !isResumeEventAlreadyProcessed(
                waitingInstance.tenantId(),
                waitingInstance.executionId(),
                envelope.eventId()
        );
    }

    private Optional<EventEnvelope> findAwaitedEventForInstance(
            FlowInstance waitingInstance,
            WaitCriteria waitCriteria,
            boolean markProcessed
    ) {
        if (waitingInstance == null) {
            return Optional.empty();
        }
        return findAwaitedEvent(
                waitCriteria,
                waitingInstance.executionId(),
                waitingInstance.correlationId(),
                waitingInstance.tenantId(),
                waitingInstance.state(),
                waitingInstance.state().get("input"),
                markProcessed
        );
    }

    private Optional<EventEnvelope> findAwaitedEvent(
            WaitCriteria waitCriteria,
            String executionId,
            String correlationId,
            String tenantId,
            Map<String, Object> state,
            Object input,
            boolean markProcessed
    ) {
        if (eventStore == null || waitCriteria == null) {
            return Optional.empty();
        }
        String eventName = waitCriteria.awaitEventName();
        if (eventName == null || eventName.isBlank()) {
            return Optional.empty();
        }
        String effectiveTenantId = normalizeTenantOrDefault(tenantId);
        List<EventEnvelope> candidates = waitCriteria.matchCorrelation()
                ? eventStore.read(eventName, correlationId, effectiveTenantId)
                : eventStore.readByEventName(eventName, effectiveTenantId);
        Map<String, Object> effectiveState = state == null ? Map.of() : state;
        for (EventEnvelope candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (waitCriteria.matchCorrelation() && !matchesCorrelation(candidate, correlationId)) {
                continue;
            }
            if (!matchesAwaitPayload(waitCriteria.payloadMatchRefs(), candidate, effectiveState, input)) {
                continue;
            }
            if (isResumeEventAlreadyProcessed(tenantId, executionId, candidate.eventId())) {
                continue;
            }
            if (markProcessed) {
                markResumeEventProcessed(tenantId, executionId, candidate.eventId());
            }
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private boolean isResumeEventAlreadyProcessed(String tenantId, String executionId, String eventId) {
        if (eventId == null || eventId.isBlank() || executionId == null || executionId.isBlank()) {
            return false;
        }
        return idempotencyStore.find(
                normalizeTenantOrDefault(tenantId),
                FLOW_RESUME_IDEMPOTENCY_CAPABILITY,
                executionId,
                eventId
        ).isPresent();
    }

    private void markResumeEventProcessed(String tenantId, String executionId, String eventId) {
        if (eventId == null || eventId.isBlank() || executionId == null || executionId.isBlank()) {
            return;
        }
        if (isResumeEventAlreadyProcessed(tenantId, executionId, eventId)) {
            return;
        }
        idempotencyStore.saveSuccess(
                normalizeTenantOrDefault(tenantId),
                FLOW_RESUME_IDEMPOTENCY_CAPABILITY,
                executionId,
                eventId,
                "{\"status\":\"PROCESSED\"}",
                nowEpochMillis()
        );
    }

    private static Map<String, Object> buildAwaitState(
            FlowStepDefinition step,
            int stepIndex,
            String awaitRef
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(AWAIT_FIELD_EVENT_NAME, step.getAwaitEventName());
        out.put(AWAIT_FIELD_MATCH_CORRELATION, step.isAwaitMatchCorrelation());
        out.put(AWAIT_FIELD_PAYLOAD_MATCH_REFS, Map.copyOf(step.getAwaitPayloadMatchRefs()));
        out.put(AWAIT_FIELD_STEP_INDEX, Math.max(0, stepIndex));
        out.put(AWAIT_FIELD_STEP_NAME, step.getName());
        out.put(AWAIT_FIELD_AWAIT_REF, normalizeAwaitRef(awaitRef));
        return Map.copyOf(out);
    }

    private WaitCriteria resolveWaitCriteria(FlowInstance waitingInstance) {
        if (waitingInstance == null) {
            return new WaitCriteria(null, true, Map.of(), -1, "awaitedEvent");
        }
        Object rawWaitState = waitingInstance.state().get(AWAIT_STATE_KEY);
        if (rawWaitState instanceof Map<?, ?> waitMap) {
            String eventName = Objects.toString(waitMap.get(AWAIT_FIELD_EVENT_NAME), waitingInstance.waitingForEventName());
            boolean matchCorrelation = parseBoolean(waitMap.get(AWAIT_FIELD_MATCH_CORRELATION), true);
            int stepIndex = parseInt(waitMap.get(AWAIT_FIELD_STEP_INDEX), waitingInstance.currentStepIndex());
            String awaitRef = normalizeAwaitRef(Objects.toString(waitMap.get(AWAIT_FIELD_AWAIT_REF), "awaitedEvent"));
            return new WaitCriteria(
                    eventName,
                    matchCorrelation,
                    parseStringMap(waitMap.get(AWAIT_FIELD_PAYLOAD_MATCH_REFS)),
                    stepIndex,
                    awaitRef
            );
        }
        return new WaitCriteria(
                waitingInstance.waitingForEventName(),
                true,
                Map.of(),
                waitingInstance.currentStepIndex(),
                "awaitedEvent"
        );
    }

    private static Map<String, String> parseStringMap(Object rawValue) {
        if (!(rawValue instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey()).trim();
            String value = String.valueOf(entry.getValue()).trim();
            if (key.isBlank() || value.isBlank()) {
                continue;
            }
            out.put(key, value);
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static boolean parseBoolean(Object rawValue, boolean defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        if (rawValue instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(rawValue).trim();
        if (text.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(text);
    }

    private static int parseInt(Object rawValue, int defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        if (rawValue instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(rawValue).trim();
        if (text.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String normalizeAwaitRef(String awaitRef) {
        String normalized = normalizeRef(awaitRef);
        return normalized.isBlank() ? "awaitedEvent" : normalized;
    }

    private FlowInstance applyResumeBackoff(FlowInstance instance, String errorCode, long nowEpochMs) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must be non-null");
        }
        long delayMs = resumeDelayMillis(instance.resumeAttemptCount() + 1);
        long nextEligible = nowEpochMs + delayMs;
        return instance.markResumeFailure(errorCode, nowEpochMs, nextEligible, RESUME_MAX_ATTEMPTS);
    }

    private FlowInstance persistResumeBackoff(FlowInstance instance, String errorCode, long nowEpochMs) {
        FlowInstance updated = applyResumeBackoff(instance, errorCode, nowEpochMs);
        flowInstanceStore.update(updated);
        emitOperationalFailureEvent(updated);
        return updated;
    }

    private static long resumeDelayMillis(int nextAttempt) {
        int exponent = Math.max(0, nextAttempt - 1);
        long multiplier;
        if (exponent >= 20) {
            multiplier = 1L << 20;
        } else {
            multiplier = 1L << exponent;
        }
        long computed = RESUME_BASE_DELAY_MS * multiplier;
        if (computed < 0) {
            return RESUME_MAX_DELAY_MS;
        }
        return Math.min(computed, RESUME_MAX_DELAY_MS);
    }

    private static FlowInstanceStatus resolveFailureTerminalStatus(ExecutionResult failure) {
        if (failure == null || failure.getStatus() == null) {
            return FlowInstanceStatus.FAILED;
        }
        if (failure.getFailureInfo() != null
                && FailureCodes.RESUME_ATTEMPT_CAP.equals(failure.getFailureInfo().code())) {
            return FlowInstanceStatus.STUCK;
        }
        return switch (failure.getStatus()) {
            case INPUT_VALIDATION_FAILED, INVARIANT_FAILED, EVENT_PAYLOAD_INVALID -> FlowInstanceStatus.FAILED_PERMANENT;
            case CAPABILITY_FAILED -> {
                CapabilityError capabilityError = failure.getCapabilityError();
                if (capabilityError == null || capabilityError.kind() == null) {
                    yield FlowInstanceStatus.FAILED;
                }
                yield switch (capabilityError.kind()) {
                    case CONTRACT, AUTH, PERMANENT, NOT_FOUND -> FlowInstanceStatus.FAILED_PERMANENT;
                    case TRANSIENT, RATE_LIMIT, TIMEOUT -> FlowInstanceStatus.FAILED;
                };
            }
            case EVENT_PERSIST_FAILED, FAILED -> FlowInstanceStatus.FAILED;
            case WAITING_EVENT, OK -> FlowInstanceStatus.FAILED;
        };
    }

    private void emitOperationalFailureEvent(FlowInstance instance) {
        if (instance == null) {
            return;
        }
        if (instance.status() == FlowInstanceStatus.FAILED_PERMANENT) {
            publishOperationalEvent(
                    "ExecutionFailedPermanent",
                    Map.of(
                            "executionId", instance.executionId(),
                            "correlationId", instance.correlationId(),
                            "flowName", instance.flowName(),
                            "errorKind", instance.lastErrorKind() == null ? "SYSTEM" : instance.lastErrorKind(),
                            "errorCode", instance.lastErrorCode() == null ? FailureCodes.SYSTEM_EXCEPTION : instance.lastErrorCode(),
                            "failedAtEpochMs", instance.failedAtEpochMs() == null ? nowEpochMillis() : instance.failedAtEpochMs()
                    ),
                    instance
            );
            return;
        }
        if (instance.status() == FlowInstanceStatus.STUCK) {
            publishOperationalEvent(
                    "ExecutionStuck",
                    Map.of(
                            "executionId", instance.executionId(),
                            "correlationId", instance.correlationId(),
                            "flowName", instance.flowName(),
                            "resumeAttemptCount", instance.resumeAttemptCount(),
                            "lastResumeErrorCode", instance.lastResumeErrorCode() == null ? FailureCodes.RESUME_ATTEMPT_CAP : instance.lastResumeErrorCode(),
                            "lastProgressAtEpochMs", instance.lastProgressAtEpochMs() == null ? 0L : instance.lastProgressAtEpochMs()
                    ),
                    instance
            );
        }
    }

    private void publishOperationalEvent(
            String eventName,
            Map<String, Object> payload,
            FlowInstance instance
    ) {
        try {
            EventEnvelope envelope = newEnvelope(
                    nextId("event"),
                    eventName,
                    instance.correlationId(),
                    "system:" + instance.executionId(),
                    payload,
                    Map.of(
                            "flow", instance.flowName(),
                            "executionId", instance.executionId(),
                            "eventCategory", "ops"
                    ),
                    instance.flowName(),
                    Math.max(0, instance.currentStepIndex()),
                    instance.tenantId(),
                    instance.actorId()
            );
            if (eventStore != null) {
                eventStore.append(envelope);
            }
            eventBus.publish(envelope);
        } catch (RuntimeException ignored) {
            // Ops event emission must never alter execution outcome.
        }
    }

    private static String resolveInvariantConceptName(FlowStepDefinition step, FlowDefinition flow) {
        String scope = step.getInvariantScope();
        if (scope != null && !scope.isBlank()) {
            return scope;
        }
        return flow.getEntityName();
    }

    private static CapabilityErrorKind classifyCapabilityException(RuntimeException runtimeException) {
        if (runtimeException instanceof IllegalArgumentException
                || runtimeException instanceof ClassCastException
                || runtimeException instanceof UnsupportedOperationException) {
            return CapabilityErrorKind.CONTRACT;
        }
        String message = runtimeException.getMessage() == null ? "" : runtimeException.getMessage().toLowerCase();
        String typeName = runtimeException.getClass().getName().toLowerCase();
        if (typeName.contains("auth")
                || typeName.contains("forbidden")
                || message.contains("unauthorized")
                || message.contains("forbidden")
                || message.contains("access denied")) {
            return CapabilityErrorKind.AUTH;
        }
        if (typeName.contains("rate")
                || message.contains("rate limit")
                || message.contains("too many requests")) {
            return CapabilityErrorKind.RATE_LIMIT;
        }
        if (typeName.contains("timeout")
                || message.contains("timeout")) {
            return CapabilityErrorKind.TIMEOUT;
        }
        if (typeName.contains("transient")
                || typeName.contains("temporar")) {
            return CapabilityErrorKind.TRANSIENT;
        }
        return CapabilityErrorKind.PERMANENT;
    }

    private EffectiveCapabilityPolicy resolveEffectiveCapabilityPolicy(
            FlowStepDefinition step,
            CapabilityExecutionPolicy basePolicy,
            String tenantId
    ) {
        CapabilityExecutionPolicy safeBase = basePolicy == null
                ? CapabilityExecutionPolicy.defaults()
                : basePolicy;
        CapabilityPolicyOverride override = capabilityPolicyOverrides.find(
                        step.getCapability(),
                        step.getOperation()
                )
                .orElse(CapabilityPolicyOverride.empty());

        int retryMaxAttempts = positiveOrDefault(override.retryMaxAttempts(), safeBase.retryCount());
        long retryBaseDelayMs = nonNegativeOrDefault(override.retryBaseDelayMs(), safeBase.retryDelayMs());
        long retryMaxDelayMs = nonNegativeOrDefault(
                override.retryMaxDelayMs(),
                Math.max(retryBaseDelayMs, safeBase.retryDelayMs())
        );
        long timeoutMs = nonNegativeOrDefault(override.timeoutMs(), safeBase.timeoutMs());
        int circuitAfterFailures = positiveOrDefault(override.circuitOpenAfterFailures(), CIRCUIT_FAILURE_THRESHOLD);
        long circuitOpenMs = nonNegativeOrDefault(override.circuitOpenMs(), CIRCUIT_OPEN_DURATION_MS);
        int bulkheadMaxConcurrent = positiveOrDefault(override.bulkheadMaxConcurrent(), BULKHEAD_MAX_CONCURRENT);
        boolean cacheIdempotencyFailures = override.cacheIdempotencyFailures() != null
                && override.cacheIdempotencyFailures();

        return new EffectiveCapabilityPolicy(
                retryMaxAttempts,
                retryBaseDelayMs,
                Math.max(retryBaseDelayMs, retryMaxDelayMs),
                timeoutMs,
                circuitAfterFailures,
                circuitOpenMs,
                bulkheadMaxConcurrent,
                cacheIdempotencyFailures
        );
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }

    private static long nonNegativeOrDefault(Long value, long fallback) {
        if (value == null || value < 0L) {
            return fallback;
        }
        return value;
    }

    private static long retryDelayForAttempt(EffectiveCapabilityPolicy policy, int attempt) {
        if (policy == null || policy.retryBaseDelayMs() <= 0) {
            return 0L;
        }
        int exponent = Math.max(0, attempt - 1);
        long multiplier;
        if (exponent >= 20) {
            multiplier = 1L << 20;
        } else {
            multiplier = 1L << exponent;
        }
        long computed = policy.retryBaseDelayMs() * multiplier;
        if (computed < 0L) {
            return policy.retryMaxDelayMs();
        }
        return Math.min(computed, Math.max(policy.retryBaseDelayMs(), policy.retryMaxDelayMs()));
    }

    private static CapabilityError reclassifyIntegrityViolation(CapabilityError error) {
        if (error == null
                || error.kind() == CapabilityErrorKind.CONTRACT
                || !isIntegrityViolationMessage(error.message())) {
            return error;
        }
        return new CapabilityError(
                error.code(),
                error.message(),
                CapabilityErrorKind.CONTRACT,
                error.details()
        );
    }

    private static boolean isIntegrityViolationMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("referential integrity")
                || normalized.contains("integrity constraint")
                || normalized.contains("referential/constraint violation")
                || normalized.contains("foreign key")
                || normalized.contains("unique index or primary key")
                || normalized.contains("unique constraint");
    }

    private CapabilityResult invokeCapabilityWithPolicy(
            FlowStepDefinition step,
            List<Object> args,
            Map<String, Object> state,
            String correlationId,
            String idempotencyKey,
            CapabilityExecutionPolicy policy,
            EffectiveCapabilityPolicy effectivePolicy,
            Map<String, Object> traceInfo,
            String tenantId
    ) {
        long startedAtMs = nowEpochMillis();
        CapabilityOpKey opKey = new CapabilityOpKey(
                normalizeTenantOrDefault(tenantId),
                Objects.requireNonNull(step.getCapability(), "capability is required"),
                Objects.requireNonNull(step.getOperation(), "operation is required")
        );
        String idempotencyState = (idempotencyKey == null || idempotencyKey.isBlank()) ? "NONE" : "MISS";
        String bulkheadState = "NOT_USED";
        int attemptsUsed = 0;
        safeMetricInc("npdev.capability.call", capabilityMetricTags(
                opKey,
                null,
                CircuitState.CLOSED.name(),
                bulkheadState,
                idempotencyState
        ));
        long now = nowEpochMillis();
        CircuitBreakerState circuit = circuitBreakerStateStore.get(opKey);
        String circuitState = circuitStateName(circuit, CircuitState.CLOSED.name());
        updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
        CircuitGate gate = gateCircuit(opKey, circuit, now);
        if (!gate.allowed()) {
            circuitState = currentCircuitState(opKey, CircuitState.OPEN.name());
            updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
            CapabilityResult circuitFailure = circuitOpenFailure(step, opKey, circuit);
            safeMetricFailure(
                    opKey,
                    startedAtMs,
                    circuitFailure.error(),
                    circuitState,
                    bulkheadState,
                    idempotencyState
            );
            return circuitFailure;
        }

        int maxConcurrent = gate.halfOpenTrial() ? 1 : effectivePolicy.bulkheadMaxConcurrent();
        if (!bulkheadStore.tryAcquire(opKey, maxConcurrent, now)) {
            bulkheadState = "REJECTED";
            circuitState = currentCircuitState(opKey, circuitStateName(circuit, CircuitState.CLOSED.name()));
            updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
            CapabilityResult rejected = CapabilityResult.failure(
                    "CAPABILITY_BULKHEAD_FULL",
                    "Capability bulkhead is full for " + step.getCapability() + "." + step.getOperation(),
                    CapabilityErrorKind.TRANSIENT,
                    capabilityDetails(
                            "capability", step.getCapability(),
                            "operation", step.getOperation(),
                            "tenantId", opKey.tenantId(),
                            "maxConcurrent", maxConcurrent
                    )
            );
            safeMetricFailure(
                    opKey,
                    startedAtMs,
                    rejected.error(),
                    circuitState,
                    bulkheadState,
                    idempotencyState
            );
            return rejected;
        }
        bulkheadState = "ACQUIRED";
        updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);

        try {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                Optional<IdempotencyRecord> cachedRecord = idempotencyStore.find(
                        opKey.tenantId(),
                        opKey.capabilityName(),
                        opKey.operationName(),
                        idempotencyKey
                );
                if (cachedRecord.isPresent()) {
                    IdempotencyRecord record = cachedRecord.get();
                    idempotencyState = "HIT";
                    updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
                    if (record.success()) {
                        onCapabilitySuccess(opKey, now);
                        circuitState = currentCircuitState(opKey, CircuitState.CLOSED.name());
                        updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
                        CapabilityResult cachedSuccess = CapabilityResult.success(fromCachedSuccessRecord(record));
                        safeMetricSuccess(
                                opKey,
                                startedAtMs,
                                circuitState,
                                bulkheadState,
                                idempotencyState
                        );
                        return cachedSuccess;
                    }
                    CapabilityResult cachedFailure = fromCachedFailureRecord(step, record);
                    if (cachedFailure != null) {
                        onCapabilityFailure(
                                opKey,
                                cachedFailure.error(),
                                now,
                                effectivePolicy.circuitOpenAfterFailures(),
                                effectivePolicy.circuitOpenMs()
                        );
                        safeMetricFailure(
                                opKey,
                                startedAtMs,
                                cachedFailure.error(),
                                currentCircuitState(opKey, CircuitState.CLOSED.name()),
                                bulkheadState,
                                idempotencyState
                        );
                        return cachedFailure;
                    }
                } else {
                    idempotencyState = "MISS";
                    updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
                }
            }

            long startedAt = nowEpochMillis();
            int maxAttempts = Math.max(1, effectivePolicy.retryMaxAttempts());
            CapabilityResult lastFailure = null;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                attemptsUsed = attempt;
                updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
                long remainingTimeoutMs = remainingTimeout(effectivePolicy.timeoutMs(), startedAt);
                if (effectivePolicy.timeoutMs() > 0 && remainingTimeoutMs <= 0) {
                    lastFailure = timeoutCapabilityResult(step, attempt, effectivePolicy.timeoutMs());
                    break;
                }

                CapabilityResult attemptResult = invokeCapabilityOnce(
                        step,
                        args,
                        state,
                        correlationId,
                        idempotencyKey,
                        remainingTimeoutMs,
                        attempt
                );

                if (attemptResult.ok()) {
                    onCapabilitySuccess(opKey, nowEpochMillis());
                    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                        idempotencyStore.saveSuccess(
                                opKey.tenantId(),
                                opKey.capabilityName(),
                                opKey.operationName(),
                                idempotencyKey,
                                encodeIdempotencyResult(attemptResult.value()),
                                nowEpochMillis()
                        );
                    }
                    safeMetricSuccess(
                            opKey,
                            startedAtMs,
                            currentCircuitState(opKey, CircuitState.CLOSED.name()),
                            bulkheadState,
                            idempotencyState
                    );
                    updateCapabilityTraceInfo(
                            traceInfo,
                            attemptsUsed,
                            currentCircuitState(opKey, CircuitState.CLOSED.name()),
                            bulkheadState,
                            idempotencyState
                    );
                    return attemptResult;
                }

                CapabilityResult normalized = normalizeCapabilityFailure(step, attemptResult, attempt);
                CapabilityError error = normalized.error();
                if (error == null) {
                    lastFailure = normalized;
                    break;
                }
                CapabilityErrorKind kind = policy.applyFailureClassification(error.kind());
                if (kind == null) {
                    kind = error.kind();
                }
                if (kind != error.kind()) {
                    error = new CapabilityError(error.code(), error.message(), kind, error.details());
                    normalized = CapabilityResult.failure(error);
                }
                lastFailure = normalized;

                if (isRetryable(kind) && attempt < maxAttempts) {
                    long retryDelayMs = retryDelayForAttempt(effectivePolicy, attempt);
                    if (!sleepForRetry(retryDelayMs)) {
                        lastFailure = CapabilityResult.failure(
                                "CAPABILITY_RETRY_INTERRUPTED",
                                "Capability retry was interrupted",
                                CapabilityErrorKind.TRANSIENT,
                                capabilityDetails(
                                        "capability", step.getCapability(),
                                        "operation", step.getOperation(),
                                        "attempt", attempt,
                                        "maxAttempts", maxAttempts,
                                        "retryDelayMs", retryDelayMs
                                )
                        );
                        break;
                    }
                    continue;
                }
                break;
            }

            if (lastFailure == null) {
                lastFailure = CapabilityResult.failure(
                        "CAPABILITY_POLICY_EXHAUSTED",
                        "Capability invocation exhausted retry policy",
                        CapabilityErrorKind.PERMANENT,
                        capabilityDetails(
                                "capability", step.getCapability(),
                                "operation", step.getOperation(),
                                "retryCount", maxAttempts
                        )
                );
            }
            CapabilityError finalError = lastFailure.error();
            if (finalError != null) {
                onCapabilityFailure(
                        opKey,
                        finalError,
                        nowEpochMillis(),
                        effectivePolicy.circuitOpenAfterFailures(),
                        effectivePolicy.circuitOpenMs()
                );
                if (idempotencyKey != null
                        && !idempotencyKey.isBlank()
                        && shouldCacheFailure(finalError, effectivePolicy.cacheIdempotencyFailures())) {
                    idempotencyStore.saveFailure(
                            opKey.tenantId(),
                            opKey.capabilityName(),
                            opKey.operationName(),
                            idempotencyKey,
                            finalError.kind().name() + ":" + finalError.code(),
                            nowEpochMillis()
                    );
                }
                safeMetricFailure(
                        opKey,
                        startedAtMs,
                        finalError,
                        currentCircuitState(opKey, circuitStateName(circuit, CircuitState.CLOSED.name())),
                        bulkheadState,
                        idempotencyState
                );
                updateCapabilityTraceInfo(
                        traceInfo,
                        attemptsUsed,
                        currentCircuitState(opKey, circuitStateName(circuit, CircuitState.CLOSED.name())),
                        bulkheadState,
                        idempotencyState
                );
            } else {
                safeMetricFailure(
                        opKey,
                        startedAtMs,
                        null,
                        currentCircuitState(opKey, circuitStateName(circuit, CircuitState.CLOSED.name())),
                        bulkheadState,
                        idempotencyState
                );
                updateCapabilityTraceInfo(
                        traceInfo,
                        attemptsUsed,
                        currentCircuitState(opKey, circuitStateName(circuit, CircuitState.CLOSED.name())),
                        bulkheadState,
                        idempotencyState
                );
            }
            return lastFailure;
        } finally {
            bulkheadStore.release(opKey);
        }
    }

    private static void updateCapabilityTraceInfo(
            Map<String, Object> traceInfo,
            int attemptsUsed,
            String circuitState,
            String bulkheadState,
            String idempotencyState
    ) {
        if (traceInfo == null) {
            return;
        }
        traceInfo.put("attemptCount", attemptsUsed);
        traceInfo.put("circuitState", circuitState);
        traceInfo.put("bulkheadState", bulkheadState);
        traceInfo.put("idempotencyState", idempotencyState);
    }

    private CircuitGate gateCircuit(CapabilityOpKey key, CircuitBreakerState state, long now) {
        CircuitBreakerState current = state == null ? CircuitBreakerState.closed() : state;
        if (current.state() == CircuitState.OPEN) {
            if (now < current.halfOpenAllowedAtMs()) {
                return new CircuitGate(false, false);
            }
            CircuitBreakerState halfOpen = new CircuitBreakerState(
                    CircuitState.HALF_OPEN,
                    current.consecutiveFailures(),
                    current.openedAtMs(),
                    current.lastFailureAtMs(),
                    current.halfOpenAllowedAtMs(),
                    0
            );
            circuitBreakerStateStore.put(key, halfOpen);
            return new CircuitGate(true, true);
        }
        if (current.state() == CircuitState.HALF_OPEN) {
            if (current.halfOpenTrialCount() >= 1) {
                return new CircuitGate(false, true);
            }
            CircuitBreakerState trial = new CircuitBreakerState(
                    CircuitState.HALF_OPEN,
                    current.consecutiveFailures(),
                    current.openedAtMs(),
                    current.lastFailureAtMs(),
                    current.halfOpenAllowedAtMs(),
                    1
            );
            circuitBreakerStateStore.put(key, trial);
            return new CircuitGate(true, true);
        }
        return new CircuitGate(true, false);
    }

    private void safeMetricInc(String name, Map<String, String> tags) {
        try {
            metricsSink.inc(name, tags);
        } catch (RuntimeException ignored) {
            // Metrics must never break execution.
        }
    }

    private void safeMetricObserveMs(String name, long durationMs, Map<String, String> tags) {
        try {
            metricsSink.observeMs(name, durationMs, tags);
        } catch (RuntimeException ignored) {
            // Metrics must never break execution.
        }
    }

    private void safeMetricSuccess(
            CapabilityOpKey opKey,
            long startedAtMs,
            String circuitState,
            String bulkheadState,
            String idempotencyState
    ) {
        Map<String, String> tags = capabilityMetricTags(opKey, null, circuitState, bulkheadState, idempotencyState);
        safeMetricInc("npdev.capability.success", tags);
        safeMetricObserveMs("npdev.capability.duration_ms", nowEpochMillis() - startedAtMs, tags);
    }

    private void safeMetricFailure(
            CapabilityOpKey opKey,
            long startedAtMs,
            CapabilityError error,
            String circuitState,
            String bulkheadState,
            String idempotencyState
    ) {
        Map<String, String> tags = capabilityMetricTags(opKey, error, circuitState, bulkheadState, idempotencyState);
        safeMetricInc("npdev.capability.failure", tags);
        safeMetricObserveMs("npdev.capability.duration_ms", nowEpochMillis() - startedAtMs, tags);
    }

    private static Map<String, String> capabilityMetricTags(
            CapabilityOpKey opKey,
            CapabilityError error,
            String circuitState,
            String bulkheadState,
            String idempotencyState
    ) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("tenantId", safeTag(opKey == null ? null : opKey.tenantId(), "default"));
        tags.put("capabilityName", safeTag(opKey == null ? null : opKey.capabilityName(), "<none>"));
        tags.put("operationName", safeTag(opKey == null ? null : opKey.operationName(), "<none>"));
        tags.put("errorKind", error == null || error.kind() == null ? "NONE" : error.kind().name());
        tags.put("circuitState", safeTag(circuitState, CircuitState.CLOSED.name()));
        tags.put("bulkhead", safeTag(bulkheadState, "NOT_USED"));
        tags.put("idempotency", safeTag(idempotencyState, "NONE"));
        return Map.copyOf(tags);
    }

    private String currentCircuitState(CapabilityOpKey opKey, String fallback) {
        try {
            CircuitBreakerState current = circuitBreakerStateStore.get(opKey);
            if (current == null || current.state() == null) {
                return fallback;
            }
            return current.state().name();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String circuitStateName(CircuitBreakerState state, String fallback) {
        if (state == null || state.state() == null) {
            return fallback;
        }
        return state.state().name();
    }

    private static String safeTag(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? fallback : trimmed;
    }

    private void onCapabilitySuccess(CapabilityOpKey key, long now) {
        circuitBreakerStateStore.reset(key);
    }

    private void onCapabilityFailure(
            CapabilityOpKey key,
            CapabilityError error,
            long now,
            int circuitOpenAfterFailures,
            long circuitOpenMs
    ) {
        if (error == null) {
            return;
        }
        CircuitBreakerState current = circuitBreakerStateStore.get(key);
        if (!isCircuitFailure(error.kind())) {
            if (current != null && current.state() != CircuitState.CLOSED) {
                circuitBreakerStateStore.reset(key);
            }
            return;
        }

        int failures = current == null ? 1 : Math.max(1, current.consecutiveFailures() + 1);
        if (current != null && current.state() == CircuitState.HALF_OPEN) {
            failures = Math.max(1, failures);
        }
        int effectiveFailureThreshold = Math.max(1, circuitOpenAfterFailures);
        long effectiveOpenMs = Math.max(0L, circuitOpenMs);
        if (failures >= effectiveFailureThreshold || (current != null && current.state() == CircuitState.HALF_OPEN)) {
            circuitBreakerStateStore.put(
                    key,
                    new CircuitBreakerState(
                            CircuitState.OPEN,
                            failures,
                            now,
                            now,
                            now + effectiveOpenMs,
                            0
                    )
            );
            return;
        }
        circuitBreakerStateStore.put(
                key,
                new CircuitBreakerState(
                        CircuitState.CLOSED,
                        failures,
                        0L,
                        now,
                        0L,
                        0
                )
        );
    }

    private static boolean isCircuitFailure(CapabilityErrorKind kind) {
        return kind == CapabilityErrorKind.TRANSIENT
                || kind == CapabilityErrorKind.TIMEOUT
                || kind == CapabilityErrorKind.RATE_LIMIT;
    }

    private static boolean isRetryable(CapabilityErrorKind kind) {
        return kind == CapabilityErrorKind.TRANSIENT
                || kind == CapabilityErrorKind.TIMEOUT
                || kind == CapabilityErrorKind.RATE_LIMIT;
    }

    private static boolean shouldCacheFailure(CapabilityError error, boolean cacheIdempotencyFailures) {
        if (!cacheIdempotencyFailures) {
            return false;
        }
        if (error == null) {
            return false;
        }
        CapabilityErrorKind kind = error.kind();
        return kind == CapabilityErrorKind.PERMANENT
                || kind == CapabilityErrorKind.CONTRACT
                || kind == CapabilityErrorKind.AUTH
                || kind == CapabilityErrorKind.NOT_FOUND;
    }

    private CapabilityResult fromCachedFailureRecord(FlowStepDefinition step, IdempotencyRecord record) {
        if (record == null || record.success()) {
            return null;
        }
        String raw = record.errorCode();
        if (raw == null || raw.isBlank()) {
            return CapabilityResult.failure(
                    "CAPABILITY_IDEMPOTENCY_CACHED_FAILURE",
                    "Cached idempotency failure blocks capability execution",
                    CapabilityErrorKind.PERMANENT,
                    capabilityDetails(
                            "capability", step.getCapability(),
                            "operation", step.getOperation(),
                            "idempotencyKey", record.idempotencyKey()
                    )
            );
        }
        String kindPart = raw;
        String codePart = raw;
        int separator = raw.indexOf(':');
        if (separator > 0) {
            kindPart = raw.substring(0, separator);
            codePart = raw.substring(separator + 1);
        }
        CapabilityErrorKind kind = parseKind(kindPart);
        if (kind == CapabilityErrorKind.TRANSIENT
                || kind == CapabilityErrorKind.TIMEOUT
                || kind == CapabilityErrorKind.RATE_LIMIT) {
            return null;
        }
        return CapabilityResult.failure(
                codePart == null || codePart.isBlank() ? "CAPABILITY_IDEMPOTENCY_CACHED_FAILURE" : codePart,
                "Cached idempotency failure blocks capability execution",
                kind,
                capabilityDetails(
                        "capability", step.getCapability(),
                        "operation", step.getOperation(),
                        "idempotencyKey", record.idempotencyKey()
                )
        );
    }

    private Object fromCachedSuccessRecord(IdempotencyRecord record) {
        if (record == null) {
            return null;
        }
        String payload = record.resultJsonRedacted();
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return jsonCodec.fromJsonToObject(payload);
        } catch (RuntimeException exception) {
            return payload;
        }
    }

    private static CapabilityErrorKind parseKind(String value) {
        if (value == null || value.isBlank()) {
            return CapabilityErrorKind.PERMANENT;
        }
        try {
            return CapabilityErrorKind.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return CapabilityErrorKind.PERMANENT;
        }
    }

    private String encodeIdempotencyResult(Object value) {
        String serialized = jsonCodec.toJson(value);
        if (serialized == null) {
            return "null";
        }
        if (serialized.length() > IDEMPOTENCY_RESULT_MAX_CHARS) {
            return serialized.substring(0, IDEMPOTENCY_RESULT_MAX_CHARS);
        }
        return serialized;
    }

    private CapabilityResult circuitOpenFailure(
            FlowStepDefinition step,
            CapabilityOpKey opKey,
            CircuitBreakerState state
    ) {
        return CapabilityResult.failure(
                "CAPABILITY_CIRCUIT_OPEN",
                "Circuit breaker is open for " + step.getCapability() + "." + step.getOperation(),
                CapabilityErrorKind.TRANSIENT,
                capabilityDetails(
                        "tenantId", opKey.tenantId(),
                        "capability", opKey.capabilityName(),
                        "operation", opKey.operationName(),
                        "circuitState", state == null ? CircuitState.OPEN.name() : state.state().name(),
                        "halfOpenAllowedAtMs", state == null ? 0L : state.halfOpenAllowedAtMs()
                )
        );
    }

    private CapabilityResult invokeCapabilityOnce(
            FlowStepDefinition step,
            List<Object> args,
            Map<String, Object> state,
            String correlationId,
            String idempotencyKey,
            long timeoutMs,
            int attempt
    ) {
        List<Object> effectiveArgs = (args == null) ? new ArrayList<>() : args;
        List<Object> callArgs = args;
        Object conceptForDebug = null;

        if (args != null
                && step != null
                && step.getCapability() != null
                && step.getOperation() != null) {

            String capName = step.getCapability().trim().toLowerCase();
            String opName = step.getOperation().trim().toLowerCase();
            boolean isPersistence = "persistence".equals(capName);

            // NPDev convention (debug only for now): infer a concept name for persistence.save
            // from invariant scope or runtime entity name.
            if (isPersistence && "save".equals(opName) && args.size() == 1) {
                Object concept = step.getInvariantScope();
                if (concept == null || String.valueOf(concept).isBlank()) {
                    concept = state == null ? null : state.get("_npdevEntityName");
                }
                if (concept != null && !String.valueOf(concept).isBlank()) {
                    conceptForDebug = concept;
                }

                // Tenant isolation for the FLOW-DRIVEN persistence path: stamp the caller's tenant
                // (carried in flow state, seeded from the ExecutionContext at execute() time) into the
                // entity payload so the capability adapter writes the tenant_id column. The tenant is
                // taken from the execution context, never from caller-supplied data -- mirroring the
                // generated-CRUD path. Reads via capability findById (by globally-unique UUID) are
                // unaffected (IDs are globally-unique UUIDs, not enumerable); query()/list reads are
                // tenant-scoped below, the same way.
                if (args.get(0) instanceof Map<?, ?> entityMap) {
                    Map<String, Object> stamped = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : entityMap.entrySet()) {
                        stamped.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    stamped.put("tenantId", flowStateTenantId(state));
                    callArgs = List.of(stamped);
                    effectiveArgs = callArgs;
                }
            }

            // Tenant isolation for flow-driven persistence READS: query()/list() take
            // (concept, criteria) -- stamp the caller's tenant into the criteria map the same way
            // save() stamps it into the entity map, so a flow author gets row-scoped reads for free
            // instead of having to remember to add a tenantId criterion themselves.
            if (isPersistence && ("query".equals(opName) || "list".equals(opName)) && args.size() == 2) {
                Object concept = args.get(0);
                if (concept != null && !String.valueOf(concept).isBlank()) {
                    conceptForDebug = concept;
                }
                Map<String, Object> stamped = new LinkedHashMap<>();
                if (args.get(1) instanceof Map<?, ?> criteriaMap) {
                    for (Map.Entry<?, ?> entry : criteriaMap.entrySet()) {
                        stamped.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                stamped.put("tenantId", flowStateTenantId(state));
                callArgs = List.of(concept, stamped);
                effectiveArgs = callArgs;
            }
        }

        // Verbose capability debug so we can see which adapter is being used at runtime
        LOG.info(String.format(
                "NPDEV-CAPABILITY :: cap=%s op=%s adapterId=%s argsCount=%s concept=%s correlationId=%s attempt=%s",
                step == null ? null : step.getCapability(),
                step == null ? null : step.getOperation(),
                step == null ? null : step.getCapabilityAdapterId(),
                effectiveArgs == null ? 0 : effectiveArgs.size(),
                conceptForDebug,
                correlationId,
                attempt
        ));
CapabilityCall call = new CapabilityCall(
                Objects.requireNonNull(step.getCapability(), "capability is required"),
                step.getCapabilityType(),
                step.getCapabilityAdapterId(),
                Objects.requireNonNull(step.getOperation(), "operation is required"),
                callArgs,
                correlationId,
                idempotencyKey
        );
        Map<String, Object> contextState = new LinkedHashMap<>(state == null ? Map.of() : state);
        contextState.put("capabilityAttempt", attempt);
        if (conceptForDebug != null) {
            contextState.put("_npdevConcept", conceptForDebug);
        }
        Map<String, Object> finalContextState = Map.copyOf(contextState);

        if (timeoutMs <= 0) {
            try {
                return capabilityDispatcher.invoke(call, finalContextState);
            } catch (RuntimeException runtimeException) {
                return CapabilityResult.failure(
                        "CAPABILITY_DISPATCHER_EXCEPTION",
                        runtimeException.getMessage() == null
                                ? "Capability dispatcher threw runtime exception"
                                : runtimeException.getMessage(),
                        classifyCapabilityException(runtimeException),
                        capabilityDetails(
                                "capability", step.getCapability(),
                                "operation", step.getOperation(),
                                "adapterId", step.getCapabilityAdapterId(),
                                "attempt", attempt,
                                "exceptionType", runtimeException.getClass().getName()
                        )
                );
            }
        }

        CompletableFuture<CapabilityResult> future = CompletableFuture.supplyAsync(
                () -> capabilityDispatcher.invoke(call, finalContextState)
        );
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutException) {
            future.cancel(true);
            return timeoutCapabilityResult(step, attempt, timeoutMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return CapabilityResult.failure(
                    "CAPABILITY_DISPATCHER_INTERRUPTED",
                    "Capability invocation interrupted",
                    CapabilityErrorKind.TRANSIENT,
                    capabilityDetails(
                            "capability", step.getCapability(),
                            "operation", step.getOperation(),
                            "adapterId", step.getCapabilityAdapterId(),
                            "attempt", attempt
                    )
            );
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            RuntimeException runtime = cause instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new RuntimeException(cause == null ? "Capability dispatcher execution failed" : cause.getMessage(), cause);
            return CapabilityResult.failure(
                    "CAPABILITY_DISPATCHER_EXCEPTION",
                    runtime.getMessage() == null
                            ? "Capability dispatcher threw runtime exception"
                            : runtime.getMessage(),
                    classifyCapabilityException(runtime),
                    capabilityDetails(
                            "capability", step.getCapability(),
                            "operation", step.getOperation(),
                            "adapterId", step.getCapabilityAdapterId(),
                            "attempt", attempt,
                            "exceptionType", runtime.getClass().getName()
                    )
            );
        }
    }

    /**
     * The tenant a flow-driven persistence write is owned by: taken from flow state (seeded from the
     * caller's ExecutionContext at execute() time), falling back to "default" when no tenant claim is
     * present (e.g. an unauthenticated trial boot). Mirrors the generated-CRUD currentTenantId().
     */
    private static String flowStateTenantId(Map<String, Object> state) {
        Object tenant = state == null ? null : state.get("tenantId");
        String tenantId = tenant == null ? null : String.valueOf(tenant).trim();
        return (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
    }

    private static CapabilityResult normalizeCapabilityFailure(
            FlowStepDefinition step,
            CapabilityResult result,
            int attempt
    ) {
        if (result.error() != null) {
            CapabilityError error = result.error();
            Map<String, Object> details = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : error.details().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    details.put(entry.getKey(), entry.getValue());
                }
            }
            details.put("attempt", attempt);
            if (step.getCapability() != null) {
                details.put("capability", step.getCapability());
            }
            if (step.getOperation() != null) {
                details.put("operation", step.getOperation());
            }
            if (step.getCapabilityAdapterId() != null) {
                details.put("adapterId", step.getCapabilityAdapterId());
            }
            return CapabilityResult.failure(new CapabilityError(
                    error.code(),
                    error.message(),
                    error.kind(),
                    details
            ));
        }
        return CapabilityResult.failure(
                "CAPABILITY_RESULT_INVALID",
                "Capability dispatcher returned failed result without error",
                CapabilityErrorKind.PERMANENT,
                capabilityDetails(
                        "attempt", attempt,
                        "capability", step.getCapability(),
                        "operation", step.getOperation(),
                        "adapterId", step.getCapabilityAdapterId()
                )
        );
    }

    private static CapabilityResult timeoutCapabilityResult(
            FlowStepDefinition step,
            int attempt,
            long timeoutMs
    ) {
        return CapabilityResult.failure(
                "CAPABILITY_TIMEOUT",
                "Capability invocation timed out after " + timeoutMs + "ms",
                CapabilityErrorKind.TIMEOUT,
                capabilityDetails(
                        "capability", step.getCapability(),
                        "operation", step.getOperation(),
                        "adapterId", step.getCapabilityAdapterId(),
                        "attempt", attempt,
                        "timeoutMs", timeoutMs
                )
        );
    }

    private static boolean isTimeoutError(CapabilityError error) {
        return error != null && "CAPABILITY_TIMEOUT".equals(error.code());
    }

    private static long remainingTimeout(long timeoutMs, long startedAtEpochMs) {
        if (timeoutMs <= 0) {
            return 0;
        }
        long elapsed = nowEpochMillis() - startedAtEpochMs;
        return timeoutMs - elapsed;
    }

    private static boolean sleepForRetry(long retryDelayMs) {
        if (retryDelayMs <= 0) {
            return true;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(retryDelayMs);
            return true;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String resolveIdempotencyKey(
            CapabilityExecutionPolicy policy,
            Map<String, Object> state,
            Object input
    ) {
        if (policy == null || policy.idempotencyKeyField() == null || policy.idempotencyKeyField().isBlank()) {
            return null;
        }
        Object resolved = resolveReferenceStrict(policy.idempotencyKeyField(), state, input);
        if (resolved == UNRESOLVED || resolved == null) {
            return null;
        }
        return String.valueOf(resolved);
    }

    private static Map<String, Object> capabilityDetails(Object... pairs) {
        if (pairs == null || pairs.length == 0) {
            return Map.of();
        }
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("pairs length must be even");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object keyRaw = pairs[i];
            Object value = pairs[i + 1];
            if (keyRaw == null || value == null) {
                continue;
            }
            out.put(String.valueOf(keyRaw), value);
        }
        return Map.copyOf(out);
    }

    private void traceSuccessfulStep(
            FlowTraceMeta traceMeta,
            FlowStepDefinition step,
            int traceStepIndex,
            long stepStartedAt,
            Map<String, Object> stateBefore,
            Map<String, Object> stateAfter,
            Map<String, Object> stepInfo,
            List<StepTrace> stepTraces
    ) {
        Map<String, Object> info = new LinkedHashMap<>(sanitizeInfo(stepInfo));
        info.put("writtenStateKeys", resolveWrittenStateKeys(stateBefore, stateAfter));
        StepTrace stepTrace = new StepTrace(
                traceStepIndex,
                step.getName(),
                step.getType().name(),
                stepStartedAt,
                nowEpochMillis(),
                StepOutcome.OK,
                info,
                List.of(),
                null
        );
        stepTraces.add(stepTrace);
        executionTracer.onStepEnd(traceMeta, stepTrace);
    }

    private void traceFailedStep(
            FlowTraceMeta traceMeta,
            FlowStepDefinition step,
            int traceStepIndex,
            long stepStartedAt,
            Map<String, Object> stateBefore,
            Map<String, Object> stateAfter,
            Map<String, Object> stepInfo,
            List<InvariantEngine.Violation> invariantViolations,
            CapabilityError capabilityError,
            List<StepTrace> stepTraces
    ) {
        Map<String, Object> info = new LinkedHashMap<>(sanitizeInfo(stepInfo));
        info.put("writtenStateKeys", resolveWrittenStateKeys(stateBefore, stateAfter));
        StepTrace stepTrace = new StepTrace(
                traceStepIndex,
                step.getName(),
                step.getType().name(),
                stepStartedAt,
                nowEpochMillis(),
                StepOutcome.FAILED,
                info,
                invariantViolations,
                capabilityError
        );
        stepTraces.add(stepTrace);
        executionTracer.onStepEnd(traceMeta, stepTrace);
    }

    private static Map<String, Object> sanitizeInfo(Map<String, Object> info) {
        if (info == null || info.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : info.entrySet()) {
            if (entry.getValue() != null) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return sanitized;
    }

    private static List<String> resolveWrittenStateKeys(
            Map<String, Object> stateBefore,
            Map<String, Object> stateAfter
    ) {
        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : stateAfter.entrySet()) {
            String key = entry.getKey();
            Object before = stateBefore.get(key);
            if (!stateBefore.containsKey(key) || !Objects.equals(before, entry.getValue())) {
                changed.add(key);
            }
        }
        return List.copyOf(changed);
    }

    private List<InputValidationError> validateInput(SchemaObject inputSchema, Object input) {
        if (inputSchema == null) {
            return List.of();
        }
        List<InputValidationError> errors = schemaValidator.validate(inputSchema, input);
        if (errors == null || errors.isEmpty()) {
            return List.of();
        }
        return List.copyOf(errors);
    }

    private static long nowEpochMillis() {
        return System.currentTimeMillis();
    }

    private void assertCrudInvariantPathAllowed() {
        String flowName = currentFlowContext.get();
        if (flowName != null && !flowName.isBlank()) {
            throw new IllegalStateException("CRUD invariant path invoked during flow execution");
        }
    }

    private static List<InvariantEngine.Violation> enrichInvariantViolations(
            List<InvariantEngine.Violation> violations,
            String conceptName,
            String flowName,
            String stepName,
            int stepIndex,
            List<String> requestedInvariantRefs
    ) {
        if (violations == null || violations.isEmpty()) {
            return List.of();
        }

        List<String> requestedRefs = requestedInvariantRefs == null ? List.of() : List.copyOf(requestedInvariantRefs);
        List<InvariantEngine.Violation> enriched = new ArrayList<>();
        for (InvariantEngine.Violation violation : violations) {
            if (violation == null) {
                if (requestedRefs.size() > 1) {
                    throw new IllegalStateException(
                            "Invariant engine returned null violation for multi-invariant evaluation"
                    );
                }
                enriched.add(new InvariantEngine.Violation(
                        "INVARIANT_FAIL",
                        "Invariant engine returned null violation",
                        requestedRefs.isEmpty() ? "<unknown>" : requestedRefs.get(0),
                        conceptName,
                        flowName,
                        stepName,
                        stepIndex,
                        Map.of()
                ));
                continue;
            }

            String ref = violation.invariantRef();
            if (ref == null || ref.isBlank() || "<unknown>".equalsIgnoreCase(ref.trim())) {
                if (requestedRefs.size() == 1) {
                    ref = requestedRefs.get(0);
                } else {
                    throw new IllegalStateException(
                            "Invariant violation missing invariantRef for multi-invariant evaluation"
                    );
                }
            }
            String concept = (violation.conceptName() == null || violation.conceptName().isBlank())
                    ? conceptName
                    : violation.conceptName();
            String flow = (violation.flowName() == null || violation.flowName().isBlank())
                    ? flowName
                    : violation.flowName();
            String step = (violation.stepName() == null || violation.stepName().isBlank())
                    ? stepName
                    : violation.stepName();
            Integer index = violation.stepIndex() == null ? stepIndex : violation.stepIndex();

            enriched.add(new InvariantEngine.Violation(
                    violation.code(),
                    violation.message(),
                    ref,
                    concept,
                    flow,
                    step,
                    index,
                    violation.details()
            ));
        }
        return List.copyOf(enriched);
    }

    private static EventEnvelope newEnvelope(
            String id,
            String eventName,
            String correlationId,
            String causationId,
            Map<String, Object> payload,
            Map<String, Object> metadata,
            String flowName,
            Integer stepIndex,
            String tenantId,
            String actorId
    ) {
        Map<String, Object> envelopePayload = payload == null ? Map.of() : new LinkedHashMap<>(payload);
        if (metadata != null && !metadata.isEmpty()) {
            envelopePayload.put("_meta", Map.copyOf(metadata));
        }
        return new EventEnvelope(
                id,
                eventName,
                System.currentTimeMillis(),
                envelopePayload,
                correlationId,
                causationId,
                flowName,
                stepIndex == null ? 0 : stepIndex,
                tenantId,
                actorId
        );
    }

    private EventEnvelope awaitEvent(
            FlowStepDefinition step,
            Map<String, Object> state,
            String defaultCorrelationId,
            Object input,
            String tenantId,
            String executionId
    ) {
        String awaitEventName = Objects.requireNonNull(step.getAwaitEventName(), "awaitEventName is required");
        String currentCorrelationId = Objects.toString(state.get("correlationId"), defaultCorrelationId);
        WaitCriteria waitCriteria = new WaitCriteria(
                awaitEventName,
                step.isAwaitMatchCorrelation(),
                step.getAwaitPayloadMatchRefs(),
                0,
                normalizeAwaitRef(step.getAwaitRef())
        );
        return findAwaitedEvent(
                waitCriteria,
                executionId,
                currentCorrelationId,
                tenantId,
                state,
                input,
                true
        ).orElse(null);
    }

    private static boolean matchesCorrelation(EventEnvelope event, String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return true;
        }
        return correlationId.equals(event.correlationId());
    }

    private static boolean matchesAwaitPayload(
            FlowStepDefinition step,
            EventEnvelope event,
            Map<String, Object> state,
            Object input
    ) {
        return matchesAwaitPayload(step.getAwaitPayloadMatchRefs(), event, state, input);
    }

    private static boolean matchesAwaitPayload(
            Map<String, String> payloadMatchRefs,
            EventEnvelope event,
            Map<String, Object> state,
            Object input
    ) {
        if (event == null) {
            return false;
        }
        if (payloadMatchRefs == null || payloadMatchRefs.isEmpty()) {
            return true;
        }
        Map<String, Object> payloadMap = event.payload();

        for (Map.Entry<String, String> match : payloadMatchRefs.entrySet()) {
            Object expected = resolveReference(match.getValue(), state, input);
            Object actual = payloadMap.get(match.getKey());
            if (!Objects.equals(expected, actual)) {
                return false;
            }
        }
        return true;
    }

    private static Object buildEventPayload(FlowStepDefinition step, Map<String, Object> state, Object input) {
        if (step.getEventDataRefs() != null && !step.getEventDataRefs().isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : step.getEventDataRefs().entrySet()) {
                payload.put(entry.getKey(), resolveReference(entry.getValue(), state, input));
            }
            return payload;
        }
        return resolveReference(step.getPayloadRef(), state, input);
    }

    private static Map<String, Object> toEventPayloadMap(Object rawPayload) {
        if (rawPayload == null) {
            return Map.of();
        }
        if (rawPayload instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                converted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return converted;
        }
        return Map.of("value", rawPayload);
    }

    private static boolean evaluateCondition(String expression, Map<String, Object> state, Object input) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String trimmed = expression.trim();

        if ("true".equalsIgnoreCase(trimmed)) return true;
        if ("false".equalsIgnoreCase(trimmed)) return false;

        int equalsIndex = trimmed.indexOf("==");
        if (equalsIndex >= 0) {
            Object left = resolveConditionToken(trimmed.substring(0, equalsIndex), state, input);
            Object right = resolveConditionToken(trimmed.substring(equalsIndex + 2), state, input);
            return Objects.equals(left, right);
        }

        int notEqualsIndex = trimmed.indexOf("!=");
        if (notEqualsIndex >= 0) {
            Object left = resolveConditionToken(trimmed.substring(0, notEqualsIndex), state, input);
            Object right = resolveConditionToken(trimmed.substring(notEqualsIndex + 2), state, input);
            return !Objects.equals(left, right);
        }

        if (trimmed.startsWith("!")) {
            return !asBoolean(resolveConditionToken(trimmed.substring(1), state, input));
        }

        return asBoolean(resolveConditionToken(trimmed, state, input));
    }

    private static Object resolveConditionToken(String token, Map<String, Object> state, Object input) {
        String value = token == null ? "" : token.trim();
        if (value.isBlank()) return null;

        if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1);
        }
        if ("null".equalsIgnoreCase(value)) return null;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;

        Object numeric = parseNumericLiteral(value);
        if (numeric != null) return numeric;

        Object resolved = resolveReferenceStrict(value, state, input);
        return resolved == UNRESOLVED ? null : resolved;
    }

    private static Object parseNumericLiteral(String value) {
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean asBoolean(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.doubleValue() != 0d;

        String text = String.valueOf(value).trim();
        if (text.isBlank()) return false;
        return !("false".equalsIgnoreCase(text)
                || "0".equals(text)
                || "null".equalsIgnoreCase(text));
    }

    private static Object resolveReference(String reference, Map<String, Object> state, Object input) {
        Object resolved = resolveReferenceStrict(reference, state, input);
        if (resolved == UNRESOLVED) {
            return reference;
        }
        return resolved;
    }

    private static Object resolveReferenceStrict(String reference, Map<String, Object> state, Object input) {
        String ref = normalizeRef(reference);
        if (ref.isBlank() || "last".equals(ref)) {
            return state.get("last");
        }
        if ("input".equals(ref)) {
            return input;
        }
        if (state.containsKey(ref)) {
            return state.get(ref);
        }

        int pathSeparator = ref.indexOf('.');
        if (pathSeparator <= 0) {
            return UNRESOLVED;
        }

        String rootRef = ref.substring(0, pathSeparator);
        Object root = resolveReferenceStrict(rootRef, state, input);
        if (root == UNRESOLVED) {
            return UNRESOLVED;
        }
        return resolvePath(root, ref.substring(pathSeparator + 1));
    }

    private static Object resolvePath(Object root, String path) {
        Object current = root;
        String[] segments = path.split("\\.");
        for (String segment : segments) {
            if (!(current instanceof Map<?, ?> map)) {
                return UNRESOLVED;
            }
            if (!map.containsKey(segment)) {
                return UNRESOLVED;
            }
            current = map.get(segment);
        }
        return current;
    }

    private static List<Object> resolveArgs(FlowStepDefinition step, Map<String, Object> state, Object input) {
        List<String> argRefs = step.getArgsRefs();
        if (argRefs == null || argRefs.isEmpty()) {
            Object single = resolveReference(step.getInputRef(), state, input);
            return single == null ? List.of() : List.of(single);
        }

        List<Object> args = new ArrayList<>();
        for (String argRef : argRefs) {
            args.add(resolveReference(argRef, state, input));
        }
        return args;
    }

    private static String normalizeRef(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("$")) {
            return trimmed.substring(1);
        }
        return trimmed;
    }

    private static String readMetaString(Map<String, Object> metadata, String key) {
        if (metadata == null) return null;
        Object value = metadata.get(key);
        if (value == null) return null;
        return String.valueOf(value);
    }

    private String extractCorrelationId(Object input) {
        if (input instanceof Map<?, ?> map) {
            Object correlation = map.get("correlationId");
            if (correlation != null) {
                String value = String.valueOf(correlation).trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return nextId("correlation");
    }

    private String nextId(String scope) {
        String normalizedScope = (scope == null || scope.isBlank()) ? "default" : scope;
        String id = idProvider.nextId(normalizedScope);
        if (id == null || id.isBlank()) {
            return IdProvider.uuid().nextId(normalizedScope);
        }
        return id;
    }

    private static ExecutionContext normalizeExecutionContext(ExecutionContext executionContext) {
        return executionContext == null ? ExecutionContext.anonymous() : executionContext;
    }

    private void enforceCorrelationOwnership(String correlationId, String tenantId) {
        String effectiveCorrelationId = normalizeCorrelationId(correlationId);
        if (effectiveCorrelationId == null) {
            return;
        }
        String effectiveTenantId = normalizeTenantOrDefault(tenantId);
        Optional<String> owner = correlationOwnershipStore.findTenantByCorrelationId(effectiveCorrelationId);
        if (owner.isPresent() && !effectiveTenantId.equals(owner.get())) {
            throw new CorrelationOwnershipViolationException(
                    effectiveCorrelationId,
                    owner.get(),
                    effectiveTenantId
            );
        }
        correlationOwnershipStore.claimCorrelation(effectiveCorrelationId, effectiveTenantId);
    }

    private static boolean sameTenant(String eventTenantId, String instanceTenantId) {
        if (eventTenantId == null || eventTenantId.isBlank()) {
            return true;
        }
        if (instanceTenantId == null || instanceTenantId.isBlank()) {
            return true;
        }
        return eventTenantId.equals(instanceTenantId);
    }

    private static String normalizeCorrelationId(String correlationId) {
        if (correlationId == null) {
            return null;
        }
        String trimmed = correlationId.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String normalizeTenantOrDefault(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ExecutionContext.anonymous().tenantId();
        }
        return tenantId.trim();
    }

    private record WaitCriteria(
            String awaitEventName,
            boolean matchCorrelation,
            Map<String, String> payloadMatchRefs,
            int stepIndex,
            String awaitRef
    ) {
        private WaitCriteria {
            payloadMatchRefs = payloadMatchRefs == null ? Map.of() : Map.copyOf(payloadMatchRefs);
            awaitRef = normalizeAwaitRef(awaitRef);
        }
    }

    @FunctionalInterface
    private interface StepProgressRecorder {
        void onStepCompleted(int nextStepIndex, Map<String, Object> state);
    }

    private record StepExecutionOutcome(
            boolean returned,
            Object returnValue,
            ExecutionResult failedResult
    ) {
        private static StepExecutionOutcome continueFlow() {
            return new StepExecutionOutcome(false, null, null);
        }

        private static StepExecutionOutcome returned(Object value) {
            return new StepExecutionOutcome(true, value, null);
        }

        private static StepExecutionOutcome failed(ExecutionResult result) {
            return new StepExecutionOutcome(false, null, result);
        }
    }

    private record CircuitGate(
            boolean allowed,
            boolean halfOpenTrial
    ) {
    }

    private record EffectiveCapabilityPolicy(
            int retryMaxAttempts,
            long retryBaseDelayMs,
            long retryMaxDelayMs,
            long timeoutMs,
            int circuitOpenAfterFailures,
            long circuitOpenMs,
            int bulkheadMaxConcurrent,
            boolean cacheIdempotencyFailures
    ) {
    }

    public static final class InvariantViolationException extends RuntimeException {
        private final String entityName;
        private final List<String> violations;

        public InvariantViolationException(String entityName, List<String> violations) {
            super(buildMessage(entityName, violations));
            this.entityName = entityName;
            this.violations = Collections.unmodifiableList(new ArrayList<>(violations));
        }

        public String getEntityName() {
            return entityName;
        }

        public List<String> getViolations() {
            return violations;
        }

        private static String buildMessage(String entityName, List<String> violations) {
            return "Invariant violations for " + entityName + ": " + String.join("; ", violations);
        }
    }
}







