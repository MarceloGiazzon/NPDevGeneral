package com.npdev.kernel;

import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.capability.CapabilityPolicyOverrides;
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
import com.npdev.kernel.ports.DeferredEventScheduler;
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
import com.npdev.kernel.procedures.ProcedureDefinition;
import com.npdev.kernel.procedures.ProcedureExecutor;
import com.npdev.kernel.schema.SchemaObject;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import com.npdev.kernel.security.PermissionDecision;
import com.npdev.kernel.security.PermissionRequirement;
import com.npdev.kernel.security.PermissionSubject;


import java.util.AbstractMap;
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
import java.util.logging.Logger;

/**
 * Sovereign kernel runner.
 * The runtime application delegates domain execution to this class.
 */
public final class KernelRunner implements FlowEngine {

    
    static final Logger LOG = Logger.getLogger(KernelRunner.class.getName());
final EventBus eventBus;
    final InvariantEngine invariantEngine;
    private final FlowDefinitionProvider flowDefinitionProvider;
    private final ExecutionTracer executionTracer;
    final EventStore eventStore;
    final FlowInstanceStore flowInstanceStore;
    private final CorrelationOwnershipStore correlationOwnershipStore;
    final IdempotencyStore idempotencyStore;
    final SchemaValidator schemaValidator;
    EventSchemaProvider eventSchemaProvider = EventSchemaProvider.noop();
    private PermissionEvaluator permissionEvaluator = PermissionEvaluator.allowAll();
    private final IdProvider idProvider;
    // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): optional, set-after-construction like
    // eventSchemaProvider/permissionEvaluator above -- a callProcedure flow step is a no-op-safe
    // addition that most existing KernelRunner callers (and all 16 of its constructors) never need
    // to know about; CallProcedureStep fails cleanly with NO_PROCEDURE_RUNNER when unset.
    private ProcedureExecutor procedureExecutor;
    private Map<String, ProcedureDefinition> procedureRegistry = Map.of();
    // R2.4: optional, set-after-construction for the same reason procedureExecutor above is -- the
    // durable scheduled-event table lives in an adapter that depends on this module, so the binding
    // arrives from outside. Left null, a scheduleEvent step with delaySeconds > 0 fails cleanly
    // (see ScheduleEventStep); it is never treated as "publish now".
    DeferredEventScheduler deferredEventScheduler;
    private final ThreadLocal<String> currentFlowContext = new ThreadLocal<>();
    private static final Object UNRESOLVED = new Object();
    private final CapabilityInvoker capabilityInvoker;

    public KernelRunner withEventSchemaProvider(EventSchemaProvider provider) {
        this.eventSchemaProvider = provider == null ? EventSchemaProvider.noop() : provider;
        return this;
    }

    public KernelRunner withPermissionEvaluator(PermissionEvaluator evaluator) {
        this.permissionEvaluator = evaluator == null ? PermissionEvaluator.allowAll() : evaluator;
        return this;
    }

    /** Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): wires a {@code callProcedure} flow
     * step to a real {@link ProcedureExecutor} and its named procedure registry. Follow
     * {@link com.npdev.kernel.ports.FlowDefinitionProvider}'s own precedent -- the caller already
     * assembles a {@code Map<String, ProcedureDefinition>} for its own ProcedureExecutor
     * construction; this just shares that same map. */
    public KernelRunner withProcedureExecutor(
            ProcedureExecutor procedureExecutor,
            Map<String, ProcedureDefinition> procedureRegistry
    ) {
        this.procedureExecutor = procedureExecutor;
        this.procedureRegistry = procedureRegistry == null ? Map.of() : Map.copyOf(procedureRegistry);
        return this;
    }

    /**
     * R2.4: binds the durable scheduled-event table to the {@code scheduleEvent} flow step, so a
     * declared delay is honoured rather than stamped on an event that fires immediately. Follows
     * {@link #withProcedureExecutor}'s precedent -- an optional collaborator none of this class's
     * 16 constructors needs to know about.
     */
    public KernelRunner withDeferredEventScheduler(DeferredEventScheduler deferredEventScheduler) {
        this.deferredEventScheduler = deferredEventScheduler;
        return this;
    }

    ProcedureExecutor procedureExecutor() {
        return procedureExecutor;
    }

    ProcedureDefinition findProcedureDefinition(String name) {
        return name == null ? null : procedureRegistry.get(name);
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
        this.executionTracer = executionTracer == null ? ExecutionTracer.NOOP : executionTracer;
        this.eventStore = eventStore;
        this.flowInstanceStore = flowInstanceStore == null ? FlowInstanceStore.noop() : flowInstanceStore;
        this.correlationOwnershipStore = correlationOwnershipStore == null
                ? CorrelationOwnershipStore.noop()
                : correlationOwnershipStore;
        this.idempotencyStore = idempotencyStore == null ? IdempotencyStore.noop() : idempotencyStore;
        this.schemaValidator = schemaValidator == null ? SchemaValidator.noop() : schemaValidator;
        this.idProvider = idProvider == null ? IdProvider.uuid() : idProvider;
        this.capabilityInvoker = new CapabilityInvoker(
                capabilityDispatcher,
                circuitBreakerStateStore == null ? CircuitBreakerStateStore.noop() : circuitBreakerStateStore,
                bulkheadStore == null ? BulkheadStore.noop() : bulkheadStore,
                this.idempotencyStore,
                capabilityPolicyOverrides == null ? CapabilityPolicyOverrides.empty() : capabilityPolicyOverrides,
                jsonCodec == null ? JsonCodec.noop() : jsonCodec,
                metricsSink == null ? MetricsSink.noop() : metricsSink,
                currentFlowContext
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

    PermissionDecision evaluatePermission(ExecutionContext context, PermissionRequirement requirement) {
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
        ResumeCoordinator.resumeWaitingExecutionsFor(this, envelope, null, effectiveCorrelationId);
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
        // REG-238: stamp the flow's step SHAPE here, before the first save below, so every durable
        // instance carries the shape it was checkpointed against. resumeExecution compares it.
        initialState.put(FlowShapeFingerprint.STATE_KEY, flow.getShapeFingerprint());
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

        return executeFlowInstanceTracked(flow, input, initialInstance, 0, traceId, effectiveContext);
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
        // WAITING_EVENT is the normal "paused for an external event" case. RUNNING is also
        // accepted here for crash recovery: the step-level checkpoint written by
        // StepProgressRecorder (see executeFlowInstance) leaves the last-known-good
        // currentStepIndex/state in the store even if the process died mid-step (e.g. inside a
        // forEach loop body, LIFT-LOOP-P2) without ever reaching WAITING_EVENT.
        if (existing.status() != FlowInstanceStatus.WAITING_EVENT && existing.status() != FlowInstanceStatus.RUNNING) {
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

        FlowDefinition resumingFlow = flowOpt.get();
        ExecutionResult shapeRefusal = FlowRunOutcomes.refuseIfFlowShapeChanged(this, existing, resumingFlow);
        if (shapeRefusal != null) {
            return shapeRefusal;
        }

        Object input = existing.state().get("input");
        return executeFlowInstanceTracked(
                resumingFlow,
                input,
                existing,
                existing.currentStepIndex(),
                existing.executionId(),
                resumeContext
        );
    }

    /**
     * R2.2: return a STUCK execution to the resume sweep. This is the operator's recovery path for
     * the one status nothing else moves out of -- {@code ResumeCoordinator.resumeAllWaitingExecutions}
     * only ever claims WAITING_EVENT rows, and {@link #resumeExecution} refuses anything that is
     * neither WAITING_EVENT nor RUNNING, so before this method the only way out of STUCK was SQL.
     *
     * <p>It deliberately does NOT resume inline. The instance goes back to WAITING_EVENT with a
     * cleared eligibility timestamp, and the next sweep (2s by default) picks it up through the
     * ordinary path -- including its idempotency and correlation checks -- rather than this method
     * re-implementing any of that. A caller that wants the retry to happen now can publish the
     * awaited event, which resumes matching waiters synchronously.
     *
     * @return the persisted post-transition instance, or empty if no such execution exists
     * @throws IllegalStateException if the execution is not un-stickable -- see
     *         {@link FlowInstance#withUnstuck(long)} for the two cases
     */
    public Optional<FlowInstance> unstickExecution(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        Optional<FlowInstance> instanceOpt = flowInstanceStore.findByExecutionId(executionId);
        if (instanceOpt.isEmpty()) {
            return Optional.empty();
        }
        FlowInstance unstuck = instanceOpt.get().withUnstuck(nowEpochMillis());
        flowInstanceStore.update(unstuck);
        return Optional.of(unstuck);
    }

    /**
     * PROC-7: operator retry of a FAILED execution from the step that failed. The instance goes back
     * to RUNNING ({@link FlowInstance#withRetry}) and is then re-entered through
     * {@link #resumeExecution}, which re-runs {@code currentStepIndex} (the failed step) under the
     * same REG-238 flow-shape guard as any other resume.
     *
     * <p>Refused with an {@link IllegalStateException} whose message starts {@code NOT_RETRYABLE} for
     * anything but FAILED, and {@code NOT_RETRYABLE_COMPENSATED} when the flow declares {@code
     * onFailure} compensation: compensation already undid the earlier steps, so re-entering the
     * forward path would re-apply only the tail.
     *
     * @return empty if no such execution exists, else the result of the re-entered run
     */
    public Optional<ExecutionResult> retryFailedExecution(String executionId, ExecutionContext executionContext) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        Optional<FlowInstance> instanceOpt = flowInstanceStore.findByExecutionId(executionId);
        if (instanceOpt.isEmpty()) {
            return Optional.empty();
        }
        FlowInstance existing = instanceOpt.get();
        if (existing.status() != FlowInstanceStatus.FAILED) {
            throw new IllegalStateException(
                    "NOT_RETRYABLE: only a FAILED execution can be retried; " + executionId + " is " + existing.status());
        }
        Optional<FlowDefinition> flowOpt = flowDefinitionProvider.findFlow(existing.flowName());
        if (flowOpt.isPresent() && CompensationRunner.hasAnyOnFailure(flowOpt.get())) {
            throw new IllegalStateException(
                    "NOT_RETRYABLE_COMPENSATED: flow " + existing.flowName() + " declares onFailure compensation, "
                            + "which already undid the earlier steps of " + executionId);
        }
        flowInstanceStore.update(existing.withRetry(nowEpochMillis()));
        // Re-enter as the instance's OWN tenant/actor with the trusted resume role, exactly like the
        // resume sweep does (ExecutionContext#resuming): the operator who triggered the retry (a
        // super-key under tenant __system__) is the requester, not the identity the flow runs as.
        ExecutionContext runAs = existing.tenantId() == null || existing.tenantId().isBlank()
                ? executionContext
                : ExecutionContext.resuming(existing.tenantId(), existing.actorId());
        return Optional.of(resumeExecution(executionId, runAs));
    }

    /**
     * PROC-7: operator cancel of a WAITING_EVENT or STUCK execution (see {@link FlowInstance#markCancelled}
     * for the representation). Persists the cancelled instance and returns it.
     *
     * @return the persisted instance, or empty if no such execution exists
     * @throws IllegalStateException {@code NOT_CANCELLABLE} if the execution is in any other status
     */
    public Optional<FlowInstance> cancelExecution(String executionId, String actorId) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        Optional<FlowInstance> instanceOpt = flowInstanceStore.findByExecutionId(executionId);
        if (instanceOpt.isEmpty()) {
            return Optional.empty();
        }
        FlowInstance cancelled = instanceOpt.get().markCancelled(actorId, nowEpochMillis());
        flowInstanceStore.update(cancelled);
        return Optional.of(cancelled);
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
        return ResumeCoordinator.resumeWaitingExecutionsFor(this, eventEnvelope, null, lookupCorrelationId);
    }

    public int resumeAllWaitingExecutions() {
        return resumeAllWaitingExecutions(500);
    }

    public int resumeAllWaitingExecutions(int limit) {
        return ResumeCoordinator.resumeAllWaitingExecutions(this, limit);
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

            // LNCH-17: a crash mid-compensation leaves the instance RUNNING (resumeExecution
            // already accepts that status for crash recovery) with this marker still set --
            // finish compensating instead of re-running the flow's normal forward step loop.
            if (Boolean.TRUE.equals(state.get(CompensationRunner.COMPENSATION_ACTIVE_KEY))) {
                flowOutcome = StepOutcome.FAILED;
                Object storedNextIndex = state.get(CompensationRunner.COMPENSATION_NEXT_INDEX_KEY);
                int resumeIndex = storedNextIndex instanceof Number number ? number.intValue() : -1;
                CompensationRunner.runCompensations(this, flow, input, state, resumeIndex, traceMeta, stepTraces, executionId, correlationId, currentInstance, executionContext);
                FlowInstance failed = CompensationRunner.finalizeCompensatedFailure(currentInstance[0], state, nowEpochMillis());
                flowInstanceStore.update(failed);
                currentInstance[0] = failed;
                emitOperationalFailureEvent(failed);
                return ExecutionResult.failed(
                        flow.getName(),
                        List.of(),
                        emittedEvents,
                        failed.lastErrorMessage(),
                        executionId,
                        correlationId,
                        traceId
                );
            }

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
                    currentInstance[0] = failedMarker;

                    // LNCH-17: compensate steps 0..marker-1 (already completed before this
                    // failure) in reverse order, before finalizing the terminal status. Routes
                    // through the same beginCompensation/finalizeCompensatedFailure pair the
                    // crash-resume path uses, so there is exactly one place that decides the
                    // terminal FlowInstanceStatus/FailureInfo shape either way.
                    FlowInstance failed;
                    if (CompensationRunner.hasAnyOnFailure(flow)) {
                        CompensationRunner.beginCompensation(state, FlowRunOutcomes.resolveFailureTerminalStatus(failure), failure.getFailureInfo());
                        CompensationRunner.runCompensations(this, flow, input, state, marker - 1, traceMeta, stepTraces, executionId, correlationId, currentInstance, executionContext);
                        failed = CompensationRunner.finalizeCompensatedFailure(currentInstance[0], state, nowEpochMillis());
                    } else {
                        failed = switch (FlowRunOutcomes.resolveFailureTerminalStatus(failure)) {
                            case FAILED_PERMANENT -> currentInstance[0].markFailedPermanent(
                                    state, nowEpochMillis(), failure.getFailureInfo());
                            case STUCK -> currentInstance[0].markStuck(
                                    state, nowEpochMillis(), failure.getFailureInfo());
                            default -> currentInstance[0].markFailed(
                                    state, nowEpochMillis(), failure.getFailureInfo());
                        };
                    }
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
            FailureInfo failureInfo = FailureInfo.of(
                    ErrorKind.SYSTEM,
                    FailureCodes.SYSTEM_EXCEPTION,
                    runtimeException.getMessage() == null
                            ? "Unhandled kernel execution error"
                            : runtimeException.getMessage(),
                    Map.of("exceptionType", runtimeException.getClass().getSimpleName())
            );
            // LNCH-17: same compensate-then-finalize routing as the modeled-failure branch above;
            // currentStepIndex() here already reflects the last successfully-checkpointed step
            // (StepProgressRecorder only advances it after a step completes), so steps
            // 0..currentStepIndex()-1 are exactly the ones that finished before this exception.
            FlowInstance failed;
            if (CompensationRunner.hasAnyOnFailure(flow)) {
                CompensationRunner.beginCompensation(state, FlowInstanceStatus.FAILED, failureInfo);
                CompensationRunner.runCompensations(this, flow, input, state, currentInstance[0].currentStepIndex() - 1, traceMeta, stepTraces, executionId, correlationId, currentInstance, executionContext);
                failed = CompensationRunner.finalizeCompensatedFailure(currentInstance[0], state, nowEpochMillis());
            } else {
                failed = currentInstance[0].markFailed(state, nowEpochMillis(), failureInfo);
            }
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

    StepExecutionOutcome executeSteps(
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
        return executeSteps(flow, steps, input, state, emittedEvents, traceMeta, stepTraces, executionId,
                defaultCorrelationId, stepIndexOffset, progressRecorder, executionContext, null);
    }

    /**
     * RUN-3: {@code completedSink}, when non-null, receives every step of THIS list that ran to
     * completion, in order. A nested body (branch then/else, one forEach iteration) uses it to know
     * which of its steps to compensate if a later one fails. Null for every other caller.
     */
    StepExecutionOutcome executeSteps(
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
            ExecutionContext executionContext,
            List<FlowStepDefinition> completedSink
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
            StepExecutionRequest req = new StepExecutionRequest(
                    flow,
                    step,
                    input,
                    state,
                    emittedEvents,
                    traceMeta,
                    stepTraces,
                    executionId,
                    defaultCorrelationId,
                    stepIndexOffset,
                    progressRecorder,
                    effectiveContext,
                    traceStepIndex,
                    stepStartedAt,
                    stateBefore,
                    stepInfo
            );
            try {
                switch (step.getType()) {
                    case INVARIANT_CHECK -> {
                        StepExecutionOutcome outcome = InvariantCheckStep.execute(this, req);
                        if (outcome != null) {
                            return outcome;
                        }
                    }
                    case CAPABILITY_CALL -> {
                        StepExecutionOutcome outcome = CapabilityCallStep.execute(this, req);
                        if (outcome != null) {
                            return outcome;
                        }
                    }
                    case EMIT_EVENT -> {
                        StepExecutionOutcome outcome = EmitEventStep.execute(this, req);
                        if (outcome != null) {
                            return outcome;
                        }
                    }
                    case SCHEDULE_EVENT -> {
                        StepExecutionOutcome outcome = ScheduleEventStep.execute(this, req);
                        if (outcome != null) {
                            return outcome;
                        }
                    }
                    case BRANCH -> {
                        StepExecutionOutcome outcome = BranchStep.execute(this, req);
                        if (outcome != null) {
                            return outcome;
                        }
                    }
                    case FOR_EACH -> {
                        StepExecutionOutcome outcome = ForEachStep.execute(this, req);
                        if (outcome != null) {
                            return outcome;
                        }
                    }
                    case AWAIT_EVENT -> {
                        StepExecutionOutcome outcome = AwaitEventStep.execute(this, req);
                        if (outcome != null) {
                            return outcome;
                        }
                    }
                    case MAP -> {
                        StepExecutionOutcome outcome = MapStep.execute(this, req);
                        if (outcome != null) {
                            return outcome;
                        }
                    }
                    case RETURN -> {
                        return ReturnStep.execute(this, req);
                    }
                    case CALL_PROCEDURE -> {
                        StepExecutionOutcome outcome = CallProcedureStep.execute(this, req);
                        if (outcome != null) {
                            return outcome;
                        }
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
            if (completedSink != null) {
                completedSink.add(step);
            }
        }
        return StepExecutionOutcome.continueFlow();
    }

    // LIFT-LOOP-P2 forEach execution now lives in ForEachStep (see its class javadoc for the
    // durable-checkpoint rationale this comment used to carry).
    static final StepProgressRecorder NOOP_STEP_PROGRESS_RECORDER = (nextStepIndex, currentState) -> { };

    void emitOperationalFailureEvent(FlowInstance instance) {
        FlowRunOutcomes.emitOperationalFailureEvent(this, instance);
    }

    static String resolveInvariantConceptName(FlowStepDefinition step, FlowDefinition flow) {
        String scope = step.getInvariantScope();
        if (scope != null && !scope.isBlank()) {
            return scope;
        }
        return flow.getEntityName();
    }

    EffectiveCapabilityPolicy resolveEffectiveCapabilityPolicy(
            FlowStepDefinition step,
            CapabilityExecutionPolicy basePolicy,
            String tenantId
    ) {
        return capabilityInvoker.resolveEffectiveCapabilityPolicy(step, basePolicy, tenantId);
    }

    static CapabilityError reclassifyIntegrityViolation(CapabilityError error) {
        return CapabilityInvoker.reclassifyIntegrityViolation(error);
    }

    CapabilityResult invokeCapabilityWithPolicy(
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
        return capabilityInvoker.invokeCapabilityWithPolicy(
                step, args, state, correlationId, idempotencyKey, policy, effectivePolicy, traceInfo, tenantId);
    }

    // LNCH-8: single choke point for both fresh-start (execute) and resumed (resumeExecution)
    // flow runs, so a flow/procedure OUTCOME metric + a single-line structured log line are
    // recorded exactly once per terminal-or-waiting return, without touching executeFlowInstance's
    // large existing body (which already emits its own per-step debug logs).
    private ExecutionResult executeFlowInstanceTracked(
            FlowDefinition flow,
            Object input,
            FlowInstance initialInstance,
            int startStepIndex,
            String traceId,
            ExecutionContext executionContext
    ) {
        long startedAtMs = nowEpochMillis();
        ExecutionResult result = executeFlowInstance(flow, input, initialInstance, startStepIndex, traceId, executionContext);
        long durationMs = nowEpochMillis() - startedAtMs;
        String outcome = FlowRunOutcomes.flowOutcomeTag(result.getStatus());
        FlowRunOutcomes.logFlowOutcome(flow.getName(), result, outcome, durationMs);
        recordFlowOutcomeMetric(flow.getName(), outcome, durationMs);
        return result;
    }

    private void recordFlowOutcomeMetric(String flowName, String outcome, long durationMs) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("flowName", CapabilityInvoker.safeTag(flowName, "<none>"));
        tags.put("outcome", outcome);
        capabilityInvoker.safeMetricInc("npdev.flow.outcome", tags);
        capabilityInvoker.safeMetricObserveMs("npdev.flow.duration_ms", durationMs, tags);
    }

    static String resolveIdempotencyKey(
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

    void traceSuccessfulStep(
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

    void traceFailedStep(
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

    List<InputValidationError> validateInput(SchemaObject inputSchema, Object input) {
        if (inputSchema == null) {
            return List.of();
        }
        List<InputValidationError> errors = schemaValidator.validate(inputSchema, input);
        if (errors == null || errors.isEmpty()) {
            return List.of();
        }
        return List.copyOf(errors);
    }

    static long nowEpochMillis() {
        return System.currentTimeMillis();
    }

    private void assertCrudInvariantPathAllowed() {
        String flowName = currentFlowContext.get();
        if (flowName != null && !flowName.isBlank()) {
            throw new IllegalStateException("CRUD invariant path invoked during flow execution");
        }
    }

    static List<InvariantEngine.Violation> enrichInvariantViolations(
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

    static EventEnvelope newEnvelope(
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

    EventEnvelope awaitEvent(
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
                FlowStateCodec.normalizeAwaitRef(step.getAwaitRef())
        );
        return ResumeCoordinator.findAwaitedEvent(
                this,
                waitCriteria,
                executionId,
                currentCorrelationId,
                tenantId,
                state,
                input,
                true
        ).orElse(null);
    }

    /**
     * F9 (FIRST_IMPRESSION_SPEC.md I7): every caller (ResumeCoordinator) only reaches this when its
     * own {@code matchCorrelation()} flag is true -- the author explicitly asked for correlation
     * matching. A blank/null correlation id cannot SATISFY that request, so it must not match
     * anything; the old {@code return true} here was exactly backwards (a blank id resumed a flow
     * the event was never meant for). B15(A), B15(B), and Wave 3 each separately declined to touch
     * this guard and instead guaranteed a non-blank correlation id on every path they built --
     * narrowing this to fail-closed is a no-op if those guarantees hold, and surfaces a real bug via
     * the named {@code AWAIT_CORRELATION_UNRESOLVABLE} shape (see {@link ForEachStep}/{@link
     * ParallelAwaitForEachStep}) if one of them does not.
     */
    static boolean matchesCorrelation(EventEnvelope event, String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return false;
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

    static boolean matchesAwaitPayload(
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

    static Object buildEventPayload(FlowStepDefinition step, Map<String, Object> state, Object input) {
        if (step.getEventDataRefs() != null && !step.getEventDataRefs().isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : step.getEventDataRefs().entrySet()) {
                payload.put(entry.getKey(), resolveReference(entry.getValue(), state, input));
            }
            return payload;
        }
        return resolveReference(step.getPayloadRef(), state, input);
    }

    static Map<String, Object> toEventPayloadMap(Object rawPayload) {
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

    /**
     * R4.2 (roadmap): a BRANCH step's {@code condition} used to be a hand-rolled {@code ==}/
     * {@code !=}-only matcher -- no {@code &&}, {@code ||}, {@code >}, or arithmetic. Now tries the
     * full {@link ComputedExpression} grammar FIRST, falling through to the legacy matcher below on
     * {@link ComputedExpression.ExpressionException} (a parse failure, or a top-level result that
     * isn't a {@code Boolean} -- e.g. a bare truthy field reference whose value is a non-boolean
     * String) -- the same ordering {@code CelInvariantEngine.evaluateExpression} already established
     * for invariant expressions (LIFT-EXPR-P2), so a condition this evaluator cannot make sense of
     * always falls back rather than throwing. {@link ConditionScope} resolves each {@code $ref}/
     * bare-token through the SAME {@link #resolveReferenceStrict} the legacy matcher below uses, so
     * a condition that parses under both engines resolves its operands identically either way.
     */
    static boolean evaluateCondition(String expression, Map<String, Object> state, Object input) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String trimmed = expression.trim();

        try {
            return ComputedExpression.evaluateBoolean(trimmed, new ConditionScope(state, input));
        } catch (ComputedExpression.ExpressionException notComputedExpression) {
            // fall through to the legacy matcher below.
        }

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

    /**
     * R4.2: bridges a {@link ComputedExpression} variable lookup to the SAME {@link
     * #resolveReferenceStrict} the legacy condition matcher uses, so {@code $saved.status} /
     * {@code $input.qty} / a bare state key resolve identically under either engine. {@code
     * containsKey} always answers {@code true} (mirroring {@code CelInvariantEngine.FieldPathScope})
     * so {@link ComputedExpression.Node#eval} always takes the {@code get} branch instead of trying
     * its own dotted-map walk over this synthetic view.
     */
    private static final class ConditionScope extends AbstractMap<String, Object> {
        private final Map<String, Object> state;
        private final Object input;

        ConditionScope(Map<String, Object> state, Object input) {
            this.state = state;
            this.input = input;
        }

        @Override
        public Object get(Object key) {
            if (!(key instanceof String token)) {
                return null;
            }
            Object resolved = resolveReferenceStrict(token, state, input);
            return resolved == UNRESOLVED ? null : resolved;
        }

        @Override
        public boolean containsKey(Object key) {
            return true;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return Set.of();
        }
    }

    static Object resolveReference(String reference, Map<String, Object> state, Object input) {
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

    static List<Object> resolveArgs(FlowStepDefinition step, Map<String, Object> state, Object input) {
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

    static String normalizeRef(String value) {
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

    String nextId(String scope) {
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

    static boolean sameTenant(String eventTenantId, String instanceTenantId) {
        if (eventTenantId == null || eventTenantId.isBlank()) {
            return true;
        }
        if (instanceTenantId == null || instanceTenantId.isBlank()) {
            return true;
        }
        return eventTenantId.equals(instanceTenantId);
    }

    /**
     * REG-47: this is the single chokepoint every correlation id passes through on its way into
     * durable state, so it is where the length bound belongs. It used to only {@code trim()}, which
     * let a caller-supplied value of arbitrary length reach eight btree indexes across four tables --
     * see {@link CorrelationIds} for why that fails, and why it rejects rather than digests.
     */
    static String normalizeCorrelationId(String correlationId) {
        return CorrelationIds.require(correlationId);
    }

    static String normalizeTenantOrDefault(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ExecutionContext.anonymous().tenantId();
        }
        return tenantId.trim();
    }

    record WaitCriteria(
            String awaitEventName,
            boolean matchCorrelation,
            Map<String, String> payloadMatchRefs,
            int stepIndex,
            String awaitRef
    ) {
        WaitCriteria {
            payloadMatchRefs = payloadMatchRefs == null ? Map.of() : Map.copyOf(payloadMatchRefs);
            awaitRef = FlowStateCodec.normalizeAwaitRef(awaitRef);
        }
    }

    @FunctionalInterface
    interface StepProgressRecorder {
        void onStepCompleted(int nextStepIndex, Map<String, Object> state);
    }

    record StepExecutionOutcome(
            boolean returned,
            Object returnValue,
            ExecutionResult failedResult
    ) {
        static StepExecutionOutcome continueFlow() {
            return new StepExecutionOutcome(false, null, null);
        }

        static StepExecutionOutcome returned(Object value) {
            return new StepExecutionOutcome(true, value, null);
        }

        static StepExecutionOutcome failed(ExecutionResult result) {
            return new StepExecutionOutcome(false, null, result);
        }
    }

    record EffectiveCapabilityPolicy(
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







