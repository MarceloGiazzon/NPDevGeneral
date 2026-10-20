package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledOrchestration;
import com.npdev.dsl.v1.compiled.CompiledOrchestrationAction;
import com.npdev.dsl.v1.compiled.CompiledOrchestrationTrigger;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.runtime.support.crud.orchestration.EventCapabilityOrchestration;
import com.npdev.runtime.support.crud.orchestration.EventCreateOrchestration;
import com.npdev.runtime.support.crud.orchestration.EventScheduleOrchestration;
import com.npdev.runtime.support.crud.orchestration.OrchestrationActionExecutionResult;
import com.npdev.runtime.support.crud.orchestration.OrchestrationExecutionClaim;
import com.npdev.runtime.support.crud.orchestration.RuntimeOrchestration;
import com.npdev.runtime.support.crud.orchestration.RuntimeOrchestrationAction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import static com.npdev.runtime.support.crud.condition.ConditionValueSupport.asBoolean;
import static com.npdev.runtime.support.crud.condition.ConditionValueSupport.readPathValue;
import static com.npdev.runtime.support.crud.condition.ConditionValueSupport.resolveConditionValue;
import static com.npdev.runtime.support.crud.condition.ConditionValueSupport.valuesEqual;
import static com.npdev.runtime.support.crud.orchestration.OrchestrationSupport.coerceMappedValue;
import static com.npdev.runtime.support.crud.orchestration.OrchestrationSupport.extractResultAdapterId;
import static com.npdev.runtime.support.crud.orchestration.OrchestrationSupport.resolveOrchestrationSubjectId;
import static com.npdev.runtime.support.crud.orchestration.OrchestrationSupport.serializePayloadForIdempotency;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.hasMapKey;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.isLifecycleMissing;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.buildScheduleEvidencePayload;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.buildScheduleKey;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.nullToEmpty;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.columnName;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.tableName;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.normalizeType;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.readPayloadValue;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.OBJECT_MAPPER;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.isNotNullViolation;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.isUniqueViolation;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.normalize;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.readMapValue;

/**
 * CARVE-3: event-driven orchestration (subscribe/reload, create / callCapability / scheduleEvent actions,
 * execution claims, conditions) moved byte-faithfully out of GeneratedCrudRuntimeSupport. The class keeps
 * the same field names the moved bodies used, so those bodies are unchanged; the two RUN-2 collaborators
 * that are set after construction live here and are written through the setters below.
 */
final class CrudOrchestrationSupport {
    private static final Logger LOG = Logger.getLogger(GeneratedCrudRuntimeSupport.class.getName());
    private static final int DEFAULT_SCHEDULE_DELAY_SECONDS = 60;
    private static final String UNSET_TENANT_SENTINEL = "default";

    private final GeneratedCrudRuntimeSupport owner;
    private final Supplier<CompiledModel> modelSupplier;
    private final KernelRunner kernelRunner;
    private final EntityManager entityManager;
    private final CapabilityDispatcher capabilityDispatcher;
    private final CapabilityRegistry capabilityRegistry;
    private final DataSource dataSource;
    private final RuntimeClock runtimeClock;
    private final OrchestrationExecutionRegistry orchestrationExecutionRegistry;
    private final List<AutoCloseable> orchestrationSubscriptions = new ArrayList<>();
    private volatile String orchestrationFallbackTenant;
    private volatile CapabilityDispatcher orchestrationCapabilityDispatcher;

    CrudOrchestrationSupport(
            GeneratedCrudRuntimeSupport owner,
            Supplier<CompiledModel> modelSupplier,
            KernelRunner kernelRunner,
            EntityManager entityManager,
            CapabilityDispatcher capabilityDispatcher,
            CapabilityRegistry capabilityRegistry,
            DataSource dataSource,
            RuntimeClock runtimeClock,
            OrchestrationExecutionRegistry orchestrationExecutionRegistry
    ) {
        this.owner = owner;
        this.modelSupplier = modelSupplier;
        this.kernelRunner = kernelRunner;
        this.entityManager = entityManager;
        this.capabilityDispatcher = capabilityDispatcher;
        this.capabilityRegistry = capabilityRegistry;
        this.dataSource = dataSource;
        this.runtimeClock = runtimeClock;
        this.orchestrationExecutionRegistry = orchestrationExecutionRegistry;
    }

    void setOrchestrationFallbackTenant(String tenantId) {
        this.orchestrationFallbackTenant = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
    }

    void setOrchestrationCapabilityDispatcher(CapabilityDispatcher dispatcher) {
        this.orchestrationCapabilityDispatcher = dispatcher;
    }

    private void publishRuntimeEvent(String eventName, Map<String, Object> payload, Map<String, Object> metadata) {
        owner.publishRuntimeEvent(eventName, payload, metadata);
    }

    private Optional<CompiledConcept> findEntity(String name) {
        return owner.findEntity(name);
    }

    private void insertScheduledEvent(
            UUID scheduleId,
            String scheduleKey,
            String orchestrationName,
            int actionIndex,
            String sourceEvent,
            String sourceEventId,
            String correlationId,
            String tenantId,
            String eventName,
            OffsetDateTime dueAt,
            String payloadJson
    ) throws SQLException {
        owner.insertScheduledEvent(scheduleId, scheduleKey, orchestrationName, actionIndex, sourceEvent,
                sourceEventId, correlationId, tenantId, eventName, dueAt, payloadJson);
    }

    List<String> initializeOrchestrationSubscribers() {
        return registerOrchestrationSubscribers(modelSupplier.get());
    }

    /**
     * REG-241: the constructor-only registration above left the EventBus subscription set frozen
     * as external state forever -- after a {@code /model-reload}, an orchestration rule ADDED to
     * the new model never fired (no subscriber existed for its event) and a rule REMOVED kept
     * firing (its subscriber was never told to stop). Called from a {@code ModelReloadListener}
     * registered in {@code NpdevCapabilityBindingConfig}, which runs inside {@code
     * ModelHolder.swap}'s write lock, so this always runs with readers of the model blocked and
     * never overlaps a concurrent reload.
     */
    void reloadOrchestrationSubscribers(CompiledModel model) {
        registerOrchestrationSubscribers(model);
    }

    private List<String> registerOrchestrationSubscribers(CompiledModel model) {
        synchronized (orchestrationSubscriptions) {
            closeOrchestrationSubscriptionsLocked();
            if (model.getOrchestrationRules().isEmpty()) {
                return List.of();
            }
            List<String> subscribers = new ArrayList<>();
            for (CompiledOrchestration orchestration : model.getOrchestrationRules()) {
                RuntimeOrchestration runtimeOrchestration = toRuntimeOrchestration(orchestration);
                if (runtimeOrchestration == null) {
                    continue;
                }
                try {
                    AutoCloseable subscription = kernelRunner.subscribeEvent(
                            runtimeOrchestration.eventName(),
                            envelope -> handleEventOrchestration(runtimeOrchestration, envelope));
                    orchestrationSubscriptions.add(subscription);
                    subscribers.add(runtimeOrchestration.name());
                } catch (Exception exception) {
                    LOG.log(Level.WARNING,
                            "Failed to register orchestration subscriber: " + runtimeOrchestration.name(),
                            exception);
                }
            }
            return subscribers.isEmpty() ? List.of() : List.copyOf(subscribers);
        }
    }

    private void closeOrchestrationSubscriptionsLocked() {
        for (AutoCloseable subscription : orchestrationSubscriptions) {
            try {
                subscription.close();
            } catch (Exception exception) {
                LOG.log(Level.WARNING, "Failed to close a prior orchestration subscription", exception);
            }
        }
        orchestrationSubscriptions.clear();
    }

    private RuntimeOrchestration toRuntimeOrchestration(CompiledOrchestration orchestration) {
        if (orchestration == null) {
            return null;
        }
        CompiledOrchestrationTrigger trigger = orchestration.getTrigger();
        if (trigger == null) {
            return null;
        }
        if (!"event".equals(normalize(trigger.getType()))) {
            return null;
        }
        String eventName = trigger.getEvent() == null ? "" : trigger.getEvent().trim();
        if (eventName.isBlank()) {
            return null;
        }

        List<CompiledOrchestrationAction> sourceActions = orchestration.getActions().isEmpty()
                ? (orchestration.getAction() == null ? List.of() : List.of(orchestration.getAction()))
                : orchestration.getActions();
        if (sourceActions.isEmpty()) {
            return null;
        }
        List<RuntimeOrchestrationAction> runtimeActions = new ArrayList<>();
        int index = 0;
        for (CompiledOrchestrationAction sourceAction : sourceActions) {
            RuntimeOrchestrationAction runtimeAction = toRuntimeOrchestrationAction(sourceAction, index);
            if (runtimeAction == null) {
                return null;
            }
            runtimeActions.add(runtimeAction);
            index++;
        }
        if (runtimeActions.isEmpty()) {
            return null;
        }
        return new RuntimeOrchestration(
                orchestration.getName(),
                eventName,
                orchestration.getCondition(),
                List.copyOf(runtimeActions)
        );
    }

    private RuntimeOrchestrationAction toRuntimeOrchestrationAction(
            CompiledOrchestrationAction action,
            int index
    ) {
        if (action == null) {
            return null;
        }
        String actionType = normalize(action.getType());
        if ("create".equals(actionType)) {
            EventCreateOrchestration createAction = toRuntimeCreateOrchestration(action);
            if (createAction == null) {
                return null;
            }
            return new RuntimeOrchestrationAction(index, "create", createAction, null, null);
        }
        if ("callcapability".equals(actionType)) {
            EventCapabilityOrchestration capabilityAction = toRuntimeCapabilityOrchestration(action);
            if (capabilityAction == null) {
                return null;
            }
            return new RuntimeOrchestrationAction(index, "callCapability", null, capabilityAction, null);
        }
        if ("scheduleevent".equals(actionType)) {
            EventScheduleOrchestration scheduleAction = toRuntimeScheduleOrchestration(action);
            if (scheduleAction == null) {
                return null;
            }
            return new RuntimeOrchestrationAction(index, "scheduleEvent", null, null, scheduleAction);
        }
        return null;
    }

    private EventCreateOrchestration toRuntimeCreateOrchestration(CompiledOrchestrationAction action) {
        if (action == null || entityManager == null) {
            return null;
        }
        Optional<CompiledConcept> targetEntityOpt = findEntity(action.getConcept());
        if (targetEntityOpt.isEmpty()) {
            return null;
        }
        CompiledConcept targetEntity = targetEntityOpt.get();
        Map<String, CompiledField> fieldsByName = new LinkedHashMap<>();
        List<String> uniqueFields = new ArrayList<>();
        for (CompiledField field : targetEntity.getFields()) {
            if (field == null || field.getName() == null || field.getName().isBlank()) {
                continue;
            }
            fieldsByName.put(normalize(field.getName()), field);
            if (field.isUnique()) {
                uniqueFields.add(field.getName());
            }
        }
        uniqueFields.sort(String.CASE_INSENSITIVE_ORDER);
        String table = tableName(targetEntity);
        if (table == null || table.isBlank()) {
            return null;
        }
        Map<String, String> fieldMap = action.getMap() == null ? Map.of() : action.getMap();
        return new EventCreateOrchestration(
                targetEntity.getName(),
                table,
                Map.copyOf(fieldsByName),
                Map.copyOf(fieldMap),
                List.copyOf(uniqueFields)
        );
    }

    private EventCapabilityOrchestration toRuntimeCapabilityOrchestration(CompiledOrchestrationAction action) {
        if (action == null || capabilityDispatcher == null) {
            return null;
        }
        String capabilityName = action.getCapability() == null ? "" : action.getCapability().trim();
        String operation = action.getOperation() == null ? "" : action.getOperation().trim();
        if (capabilityName.isBlank() || operation.isBlank()) {
            return null;
        }
        String capabilityType = resolveCapabilityType(capabilityName);
        String adapterId = resolveCapabilityAdapterId(capabilityName);
        Map<String, String> fieldMap = action.getMap() == null ? Map.of() : action.getMap();
        return new EventCapabilityOrchestration(
                capabilityName,
                capabilityType,
                adapterId,
                operation,
                Map.copyOf(fieldMap)
        );
    }

    private EventScheduleOrchestration toRuntimeScheduleOrchestration(CompiledOrchestrationAction action) {
        if (action == null || dataSource == null) {
            return null;
        }
        String eventName = action.getEvent() == null ? "" : action.getEvent().trim();
        if (eventName.isBlank()) {
            return null;
        }
        long delaySeconds = action.getDelaySeconds() == null
                ? DEFAULT_SCHEDULE_DELAY_SECONDS
                : Math.max(0L, action.getDelaySeconds());
        Map<String, String> fieldMap = action.getMap() == null ? Map.of() : action.getMap();
        return new EventScheduleOrchestration(
                eventName,
                delaySeconds,
                Map.copyOf(fieldMap)
        );
    }

    private void handleEventOrchestration(RuntimeOrchestration orchestration, EventEnvelope envelope) {
        if (orchestration == null || envelope == null) {
            return;
        }
        if (!orchestration.eventName().equals(envelope.eventName())) {
            return;
        }

        Map<String, Object> eventPayload = envelope.payload() == null ? Map.of() : envelope.payload();
        if (!shouldExecuteOrchestration(orchestration.name(), orchestration.condition(), envelope, eventPayload)) {
            return;
        }
        OrchestrationExecutionClaim claim = claimOrchestrationExecution(orchestration, envelope, eventPayload);
        if (!claim.acquired()) {
            publishOrchestrationEvent("OrchestrationSkippedDuplicate", orchestration, envelope, Map.of(
                    "reason", "duplicate_execution",
                    "idempotencyKey", claim.duplicateKey() == null ? "" : claim.duplicateKey()
            ));
            return;
        }

        publishOrchestrationEvent("OrchestrationStarted", orchestration, envelope, Map.of(
                "actionCount", orchestration.actions().size()
        ));

        for (RuntimeOrchestrationAction action : orchestration.actions()) {
            OrchestrationActionExecutionResult result =
                    executeOrchestrationAction(orchestration, action, envelope, eventPayload);
            if (result.success()) {
                publishOrchestrationActionEvent(
                        "OrchestrationActionSucceeded",
                        orchestration,
                        action,
                        envelope,
                        result.status(),
                        result.reason()
                );
                continue;
            }

            publishOrchestrationActionEvent(
                    "OrchestrationActionFailed",
                    orchestration,
                    action,
                    envelope,
                    result.status(),
                    result.reason()
            );
            publishOrchestrationEvent("OrchestrationStopped", orchestration, envelope, Map.of(
                    "actionIndex", action.index(),
                    "actionType", action.type(),
                    "status", result.status(),
                    "reason", result.reason()
            ));
            releaseOrchestrationExecution(claim);
            return;
        }

        publishOrchestrationEvent("OrchestrationCompleted", orchestration, envelope, Map.of(
                "actionCount", orchestration.actions().size()
        ));
    }

    private OrchestrationActionExecutionResult executeOrchestrationAction(
            RuntimeOrchestration orchestration,
            RuntimeOrchestrationAction action,
            EventEnvelope envelope,
            Map<String, Object> eventPayload
    ) {
        if (action == null) {
            return OrchestrationActionExecutionResult.failed("failed", "action_missing");
        }
        String normalizedType = normalize(action.type());
        if ("create".equals(normalizedType)) {
            return executeCreateOrchestrationAction(action.createAction(), envelope, eventPayload);
        }
        if ("callcapability".equals(normalizedType)) {
            return executeCapabilityOrchestrationAction(
                    orchestration == null ? null : orchestration.name(),
                    action.capabilityAction(),
                    envelope,
                    eventPayload
            );
        }
        if ("scheduleevent".equals(normalizedType)) {
            return executeScheduleOrchestrationAction(
                    orchestration,
                    action,
                    action.scheduleAction(),
                    envelope,
                    eventPayload
            );
        }
        return OrchestrationActionExecutionResult.failed("failed", "unsupported_action");
    }

    OrchestrationActionExecutionResult executeCreateOrchestrationAction(
            EventCreateOrchestration action,
            EventEnvelope envelope,
            Map<String, Object> eventPayload
    ) {
        if (action == null) {
            return OrchestrationActionExecutionResult.failed("failed", "create_action_missing");
        }
        Map<String, Object> createPayload = resolveCreatePayload(action, envelope, eventPayload);
        if (createPayload.isEmpty()) {
            return OrchestrationActionExecutionResult.failed("failed", "mapped_payload_empty");
        }

        try {
            if (existsByUniqueFields(action, createPayload)) {
                return OrchestrationActionExecutionResult.succeeded("skipped", "unique_exists");
            }
            insertMappedRow(action, createPayload);
            return OrchestrationActionExecutionResult.succeeded("ok", "created");
        } catch (Exception exception) {
            if (isUniqueViolation(exception)) {
                return OrchestrationActionExecutionResult.succeeded("skipped", "unique_violation");
            }
            if (isNotNullViolation(exception)) {
                // REG-232: a NOT NULL constraint failure here means the orchestration's own field
                // mapping left a required column unset -- a business-level "required field missing"
                // condition, not an infrastructure fault. Logged at INFO with just the message (no
                // raw JDBC stack trace) so it reads as a clean diagnostic instead of a noisy WARN.
                LOG.log(Level.INFO, "Create orchestration action skipped -- a required field was "
                        + "left null: {0}", exception.getMessage());
                return OrchestrationActionExecutionResult.failed("failed", "required_field_missing");
            }
            LOG.log(Level.WARNING, "Create orchestration action failed", exception);
            return OrchestrationActionExecutionResult.failed("failed", "create_failed");
        }
    }

    private OrchestrationActionExecutionResult executeCapabilityOrchestrationAction(
            String orchestrationName,
            EventCapabilityOrchestration orchestration,
            EventEnvelope envelope,
            Map<String, Object> eventPayload
    ) {
        if (orchestration == null) {
            return OrchestrationActionExecutionResult.failed("failed", "capability_action_missing");
        }
        if (capabilityDispatcher == null || envelope == null) {
            return OrchestrationActionExecutionResult.failed("failed", "capability_dispatcher_missing");
        }
        Map<String, Object> capabilityPayload = resolveMappedValues(orchestration.fieldMap(), eventPayload);
        Map<String, Object> invocationPayload = capabilityPayload.isEmpty()
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(capabilityPayload));

        CapabilityCall call = new CapabilityCall(
                orchestration.capabilityName(),
                orchestration.capabilityType(),
                orchestration.adapterId(),
                orchestration.operation(),
                List.of(invocationPayload),
                envelope.correlationId(),
                null
        );
        // INT-5: tenantId/actorId ride along (when the event carries them) so a capability handler can
        // resolve tenant-scoped settings; Map.of rejects nulls, hence the LinkedHashMap.
        Map<String, Object> callContext = new LinkedHashMap<>();
        callContext.put("orchestration", orchestrationName);
        callContext.put("eventName", envelope.eventName());
        callContext.put("eventId", envelope.eventId());
        String eventTenant = envelope.tenantId();
        boolean eventHasTenant = eventTenant != null && !eventTenant.isBlank()
                && !UNSET_TENANT_SENTINEL.equals(eventTenant);
        String fallbackTenant = orchestrationFallbackTenant;
        if (eventHasTenant) {
            callContext.put("tenantId", eventTenant);
            callContext.put("tenantSource", "event");
        } else if (fallbackTenant != null) {
            callContext.put("tenantId", fallbackTenant);
            callContext.put("tenantSource", "fallback");
        } else if (eventTenant != null) {
            callContext.put("tenantId", eventTenant);
            callContext.put("tenantSource", "event");
        }
        if (envelope.actorId() != null) {
            callContext.put("actorId", envelope.actorId());
        }
        CapabilityDispatcher orchestrationDispatcher = orchestrationCapabilityDispatcher;
        CapabilityResult result = (orchestrationDispatcher != null ? orchestrationDispatcher : capabilityDispatcher)
                .invoke(call, callContext);

        Map<String, Object> evidencePayload = new LinkedHashMap<>();
        evidencePayload.put("orchestration", orchestrationName);
        evidencePayload.put("capability", orchestration.capabilityName());
        evidencePayload.put("operation", orchestration.operation());
        evidencePayload.put("sourceEvent", envelope.eventName());
        evidencePayload.put("sourceEventId", envelope.eventId());
        evidencePayload.put("correlationId", envelope.correlationId());
        if (!invocationPayload.isEmpty()) {
            evidencePayload.putAll(invocationPayload);
        }

        if (result != null && result.ok()) {
            if (result.value() != null) {
                evidencePayload.put("result", result.value());
                String adapterId = extractResultAdapterId(result.value());
                if (adapterId != null) {
                    evidencePayload.put("adapterId", adapterId);
                }
            }
            publishRuntimeEvent(
                    "OrchestrationCapabilityInvoked",
                    evidencePayload,
                    Map.of(
                            "orchestrationAction", "callCapability",
                            "status", "ok"
                    )
            );
            return OrchestrationActionExecutionResult.succeeded("ok", "capability_invoked");
        }

        if (result != null && result.error() != null) {
            evidencePayload.put("errorCode", result.error().code());
            evidencePayload.put("errorMessage", result.error().message());
            if (result.error().details() != null && !result.error().details().isEmpty()) {
                evidencePayload.put("errorDetails", result.error().details());
            }
        }
        publishRuntimeEvent(
                "OrchestrationCapabilityFailed",
                evidencePayload,
                Map.of(
                        "orchestrationAction", "callCapability",
                        "status", "failed"
                )
        );
        return OrchestrationActionExecutionResult.failed("failed", "capability_failed");
    }

    private OrchestrationActionExecutionResult executeScheduleOrchestrationAction(
            RuntimeOrchestration orchestration,
            RuntimeOrchestrationAction runtimeAction,
            EventScheduleOrchestration scheduleAction,
            EventEnvelope envelope,
            Map<String, Object> eventPayload
    ) {
        if (scheduleAction == null) {
            return OrchestrationActionExecutionResult.failed("failed", "schedule_action_missing");
        }
        if (dataSource == null || envelope == null) {
            return OrchestrationActionExecutionResult.failed("failed", "scheduler_unavailable");
        }

        String orchestrationName = orchestration == null ? "" : nullToEmpty(orchestration.name());
        int actionIndex = runtimeAction == null ? 0 : runtimeAction.index();
        String scheduleKey = buildScheduleKey(orchestrationName, actionIndex, envelope, eventPayload);
        if (scheduleKey == null || scheduleKey.isBlank()) {
            return OrchestrationActionExecutionResult.failed("failed", "schedule_key_missing");
        }

        Map<String, Object> mappedPayload = resolveMappedValues(scheduleAction.fieldMap(), eventPayload);
        String payloadJson;
        try {
            payloadJson = OBJECT_MAPPER.writeValueAsString(mappedPayload == null ? Map.of() : mappedPayload);
        } catch (Exception exception) {
            return OrchestrationActionExecutionResult.failed("failed", "schedule_payload_invalid");
        }

        UUID scheduleId = UUID.randomUUID();
        OffsetDateTime dueAt = runtimeClock.nowUtc().plusSeconds(Math.max(0L, scheduleAction.delaySeconds()));
        try {
            insertScheduledEvent(
                    scheduleId,
                    scheduleKey,
                    orchestrationName,
                    actionIndex,
                    envelope.eventName(),
                    envelope.eventId(),
                    envelope.correlationId(),
                    envelope.tenantId(),
                    scheduleAction.eventName(),
                    dueAt,
                    payloadJson
            );

            publishRuntimeEvent(
                    "OrchestrationScheduleCreated",
                    buildScheduleEvidencePayload(
                            "scheduleId", scheduleId,
                            "scheduleKey", scheduleKey,
                            "orchestration", orchestrationName,
                            "actionIndex", actionIndex,
                            "sourceEvent", envelope.eventName(),
                            "sourceEventId", envelope.eventId(),
                            "eventName", scheduleAction.eventName(),
                            "dueAt", dueAt.toString()
                    ),
                    Map.of(
                            "orchestrationAction", "scheduleEvent",
                            "status", "scheduled"
                    )
            );
            return OrchestrationActionExecutionResult.succeeded("ok", "scheduled");
        } catch (Exception exception) {
            if (isUniqueViolation(exception)) {
                return OrchestrationActionExecutionResult.succeeded("skipped", "schedule_exists");
            }
            LOG.log(Level.WARNING, "Schedule orchestration action failed", exception);
            return OrchestrationActionExecutionResult.failed("failed", "schedule_failed");
        }
    }

    private void publishOrchestrationEvent(
            String eventName,
            RuntimeOrchestration orchestration,
            EventEnvelope envelope,
            Map<String, Object> details
    ) {
        if (eventName == null || eventName.isBlank() || orchestration == null || envelope == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orchestration", orchestration.name());
        payload.put("sourceEvent", envelope.eventName());
        payload.put("sourceEventId", envelope.eventId());
        payload.put("correlationId", envelope.correlationId());
        if (details != null && !details.isEmpty()) {
            payload.putAll(details);
        }
        publishRuntimeEvent(
                eventName,
                payload,
                Map.of("orchestrationAction", "workflow")
        );
    }

    private void publishOrchestrationActionEvent(
            String eventName,
            RuntimeOrchestration orchestration,
            RuntimeOrchestrationAction action,
            EventEnvelope envelope,
            String status,
            String reason
    ) {
        if (eventName == null || eventName.isBlank()
                || orchestration == null
                || action == null
                || envelope == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orchestration", orchestration.name());
        payload.put("actionIndex", action.index());
        payload.put("actionType", action.type());
        payload.put("status", status);
        payload.put("reason", reason);
        payload.put("sourceEvent", envelope.eventName());
        payload.put("sourceEventId", envelope.eventId());
        payload.put("correlationId", envelope.correlationId());
        publishRuntimeEvent(
                eventName,
                payload,
                Map.of(
                        "orchestrationAction", normalize(action.type()),
                        "status", status == null ? "" : status
                )
        );
    }

    private OrchestrationExecutionClaim claimOrchestrationExecution(
            RuntimeOrchestration orchestration,
            EventEnvelope envelope,
            Map<String, Object> eventPayload
    ) {
        List<String> keys = buildOrchestrationExecutionKeys(orchestration, envelope, eventPayload);
        if (keys.isEmpty()) {
            return OrchestrationExecutionClaim.acquired(List.of());
        }
        List<String> acquiredKeys = new ArrayList<>();
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            if (orchestrationExecutionRegistry.tryAcquire(key)) {
                acquiredKeys.add(key);
                continue;
            }
            for (String acquired : acquiredKeys) {
                orchestrationExecutionRegistry.release(acquired);
            }
            return OrchestrationExecutionClaim.duplicate(key);
        }
        return OrchestrationExecutionClaim.acquired(List.copyOf(acquiredKeys));
    }

    private void releaseOrchestrationExecution(OrchestrationExecutionClaim claim) {
        if (claim == null || claim.keys().isEmpty()) {
            return;
        }
        for (String key : claim.keys()) {
            orchestrationExecutionRegistry.release(key);
        }
    }

    private List<String> buildOrchestrationExecutionKeys(
            RuntimeOrchestration orchestration,
            EventEnvelope envelope,
            Map<String, Object> eventPayload
    ) {
        if (orchestration == null || envelope == null) {
            return List.of();
        }
        String orchestrationName = normalize(orchestration.name());
        if (orchestrationName.isBlank()) {
            return List.of();
        }

        List<String> keys = new ArrayList<>();
        String sourceEventId = envelope.eventId();
        if (sourceEventId != null && !sourceEventId.isBlank()) {
            keys.add("event:" + orchestrationName + ":" + sourceEventId.trim());
        }

        String correlationId = envelope.correlationId();
        if (correlationId != null && !correlationId.isBlank()) {
            String subjectId = resolveOrchestrationSubjectId(eventPayload);
            if (subjectId == null || subjectId.isBlank()) {
                subjectId = serializePayloadForIdempotency(eventPayload);
            }
            keys.add("effective:" + orchestrationName + ":"
                    + normalize(envelope.eventName()) + ":"
                    + correlationId.trim() + ":"
                    + subjectId.trim());
        }
        return keys.isEmpty() ? List.of() : List.copyOf(keys);
    }

    private Map<String, Object> resolveCreatePayload(
            EventCreateOrchestration orchestration,
            EventEnvelope envelope,
            Map<String, Object> eventPayload
    ) {
        if (orchestration == null || orchestration.fieldMap().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> mappedValues = resolveMappedValues(orchestration.fieldMap(), eventPayload);
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> mapping : mappedValues.entrySet()) {
            CompiledField field = orchestration.fieldsByName().get(normalize(mapping.getKey()));
            if (field == null) {
                continue;
            }
            values.put(field.getName(), coerceMappedValue(field, mapping.getValue()));
        }

        if (!hasMapKey(values, "id")) {
            CompiledField idField = orchestration.fieldsByName().get("id");
            if (idField != null && ("uuid".equals(normalizeType(idField.getDslType()))
                    || "reference".equals(normalizeType(idField.getDslType())))) {
                values.put(idField.getName(), UUID.randomUUID());
            }
        }
        // Without this, an orchestration-created row silently falls back to the table's schema-level
        // tenant_id default ("default") instead of the tenant that actually triggered the event -- the
        // row exists but is invisible through the normal tenant-scoped list/read endpoints. Confirmed
        // live: a create-orchestration row was findable only via a direct SQL query, never via the
        // generated concept's REST API under the acting tenant. "tenantId" has no CompiledField (it's
        // a generator-injected infra column, never DSL-authored), so it's added under its raw key --
        // insertMappedRow's columnName() already falls back to a computed identifier for keys with no
        // matching field, exactly for cases like this one.
        if (!hasMapKey(values, "tenantId") && envelope != null && envelope.tenantId() != null) {
            values.put("tenantId", envelope.tenantId());
        }
        return values.isEmpty() ? Map.of() : Map.copyOf(values);
    }

    private boolean shouldExecuteOrchestration(
            String orchestrationName,
            String condition,
            EventEnvelope envelope,
            Map<String, Object> eventPayload
    ) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        boolean matches = evaluateOrchestrationCondition(condition, eventPayload);
        if (matches) {
            return true;
        }

        Map<String, Object> evidencePayload = new LinkedHashMap<>();
        evidencePayload.put("orchestration", orchestrationName);
        evidencePayload.put("sourceEvent", envelope.eventName());
        evidencePayload.put("sourceEventId", envelope.eventId());
        evidencePayload.put("correlationId", envelope.correlationId());
        evidencePayload.put("reason", "condition_false");

        publishRuntimeEvent(
                "OrchestrationConditionSkipped",
                evidencePayload,
                Map.of(
                        "orchestrationAction", "condition",
                        "status", "skipped"
                )
        );
        return false;
    }

    /**
     * R4.2 (roadmap): the orchestration twin of {@code KernelRunner.evaluateCondition} -- used to be
     * a hand-rolled {@code ==}/{@code !=}-only matcher, same shape and same limitation. Now tries the
     * full {@link ComputedExpression} grammar ({@code &&}, {@code ||}, {@code >}, arithmetic) FIRST,
     * falling through to the legacy matcher below on {@link ComputedExpression.ExpressionException}
     * -- the same first-then-fallback ordering {@code CelInvariantEngine.evaluateExpression} already
     * established for invariant expressions. The bridging scope simply reuses {@link
     * ConditionValueSupport#resolveConditionValue}, so a token that parses under both engines
     * resolves against the SAME {@code $event.}/quoted-literal/bare-payload-field rules either way.
     */
    boolean evaluateOrchestrationCondition(String rawCondition, Map<String, Object> eventPayload) {
        if (rawCondition == null || rawCondition.isBlank()) {
            return true;
        }
        String condition = rawCondition.trim();
        if ("true".equalsIgnoreCase(condition)) {
            return true;
        }
        if ("false".equalsIgnoreCase(condition)) {
            return false;
        }

        try {
            return ComputedExpression.evaluateBoolean(condition, new OrchestrationConditionScope(eventPayload));
        } catch (ComputedExpression.ExpressionException notComputedExpression) {
            // fall through to the legacy matcher below.
        }

        int equalsIndex = condition.indexOf("==");
        if (equalsIndex >= 0) {
            Object left = resolveConditionValue(condition.substring(0, equalsIndex), eventPayload);
            Object right = resolveConditionValue(condition.substring(equalsIndex + 2), eventPayload);
            return valuesEqual(left, right);
        }

        int notEqualsIndex = condition.indexOf("!=");
        if (notEqualsIndex >= 0) {
            Object left = resolveConditionValue(condition.substring(0, notEqualsIndex), eventPayload);
            Object right = resolveConditionValue(condition.substring(notEqualsIndex + 2), eventPayload);
            return !valuesEqual(left, right);
        }

        return asBoolean(resolveConditionValue(condition, eventPayload));
    }

    /** R4.2: bridges a {@link ComputedExpression} variable lookup to {@link
     * ConditionValueSupport#resolveConditionValue} -- {@code containsKey} always answers {@code
     * true} (mirroring {@code CelInvariantEngine.FieldPathScope}) so {@link
     * ComputedExpression.Node#eval} always takes the {@code get} branch rather than trying its own
     * dotted-map walk over this synthetic view. */
    private static final class OrchestrationConditionScope extends AbstractMap<String, Object> {
        private final Map<String, Object> eventPayload;

        OrchestrationConditionScope(Map<String, Object> eventPayload) {
            this.eventPayload = eventPayload;
        }

        @Override
        public Object get(Object key) {
            return key instanceof String token ? resolveConditionValue(token, eventPayload) : null;
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

    private Map<String, Object> resolveMappedValues(
            Map<String, String> mapping,
            Map<String, Object> eventPayload
    ) {
        if (mapping == null || mapping.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String targetKey = entry.getKey();
            if (targetKey == null || targetKey.isBlank()) {
                continue;
            }
            Object rawValue = resolveMappingValue(entry.getValue(), eventPayload);
            if (rawValue == null) {
                continue;
            }
            values.put(targetKey, rawValue);
        }
        return values.isEmpty() ? Map.of() : Map.copyOf(values);
    }

    private Object resolveMappingValue(String source, Map<String, Object> eventPayload) {
        if (source == null) {
            return null;
        }
        String trimmed = source.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if ("$event".equals(trimmed)) {
            return eventPayload;
        }
        if (trimmed.startsWith("$event.")) {
            return readPathValue(eventPayload, trimmed.substring("$event.".length()));
        }
        Object direct = readPayloadValue(eventPayload, trimmed);
        if (direct != null) {
            return direct;
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        if ("null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private String resolveCapabilityType(String capabilityName) {
        if (capabilityName == null || capabilityName.isBlank()) {
            return null;
        }
        String requested = normalize(capabilityName);
        for (var capability : modelSupplier.get().getCapabilities()) {
            if (capability != null && normalize(capability.getName()).equals(requested)) {
                return capability.getType();
            }
        }
        return null;
    }

    private String resolveCapabilityAdapterId(String capabilityName) {
        if (capabilityName == null || capabilityName.isBlank()) {
            return null;
        }
        if (capabilityRegistry != null) {
            String fromRegistry = capabilityRegistry.debugDefaultAdapterId(capabilityName);
            if (fromRegistry != null && !fromRegistry.isBlank()) {
                return fromRegistry;
            }
        }
        String requested = normalize(capabilityName);
        for (var binding : modelSupplier.get().getBindings()) {
            if (binding != null
                    && normalize(binding.getCapability()).equals(requested)
                    && binding.getAdapter() != null
                    && !binding.getAdapter().isBlank()) {
                return binding.getAdapter().trim();
            }
        }
        return null;
    }

    private boolean existsByUniqueFields(EventCreateOrchestration orchestration, Map<String, Object> payload) {
        if (entityManager == null
                || orchestration == null
                || orchestration.uniqueFields().isEmpty()
                || payload == null
                || payload.isEmpty()) {
            return false;
        }
        List<Map.Entry<String, Object>> checks = new ArrayList<>();
        for (String uniqueField : orchestration.uniqueFields()) {
            if (uniqueField == null || uniqueField.isBlank()) {
                continue;
            }
            Object value = readMapValue(payload, uniqueField);
            if (isLifecycleMissing(value)) {
                continue;
            }
            checks.add(Map.entry(uniqueField, value));
        }
        if (checks.isEmpty()) {
            return false;
        }

        StringBuilder sql = new StringBuilder("SELECT 1 FROM ")
                .append(orchestration.targetTable())
                .append(" WHERE ");
        for (int index = 0; index < checks.size(); index++) {
            if (index > 0) {
                sql.append(" AND ");
            }
            sql.append("CAST(")
                    .append(columnName(orchestration.fieldsByName().get(normalize(checks.get(index).getKey())),
                            checks.get(index).getKey()))
                    .append(" AS VARCHAR) = :u")
                    .append(index);
        }
        try {
            Query query = entityManager.createNativeQuery(sql.toString());
            for (int index = 0; index < checks.size(); index++) {
                query.setParameter("u" + index, String.valueOf(checks.get(index).getValue()));
            }
            query.setMaxResults(1);
            List<?> rows = query.getResultList();
            return rows != null && !rows.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void insertMappedRow(EventCreateOrchestration orchestration, Map<String, Object> payload) throws SQLException {
        if (dataSource == null
                || orchestration == null
                || payload == null
                || payload.isEmpty()) {
            return;
        }
        List<Map.Entry<String, Object>> entries = new ArrayList<>(payload.entrySet());
        entries.sort(Comparator.comparing(entry -> normalize(entry.getKey())));
        StringBuilder columns = new StringBuilder();
        StringBuilder values = new StringBuilder();
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                columns.append(", ");
                values.append(", ");
            }
            columns.append(columnName(orchestration.fieldsByName().get(normalize(entries.get(index).getKey())),
                    entries.get(index).getKey()));
            values.append("?");
        }
        String sql = "INSERT INTO " + orchestration.targetTable()
                + " (" + columns + ") VALUES (" + values + ")";
        // A lifecycle event is published synchronously INSIDE the originating service's
        // @Transactional update, so this insert usually runs while the caller's transaction still
        // holds a row lock on the source row. On its own pooled connection the insert's FK check
        // then waited on that lock forever while the caller waited on the insert -- a cross-
        // connection self-deadlock Postgres cannot detect (CI, 2026-09-30: CanonicalDemoBusinessE2EIT
        // hung with pid A "idle in transaction" after the orchestration's own existence check, and
        // pid B's INSERT INTO insurance_claims blocked_by {A}). When a Spring transaction is active,
        // join its connection instead, under a SAVEPOINT so a unique/NOT NULL failure rolls back
        // only this insert -- on Postgres a failed statement would otherwise abort the caller's whole
        // transaction. With no transaction active (a deferred or async event), keep the own short
        // JDBC transaction; the shared EntityManager is not usable there (TransactionRequiredException).
        if (CallerTransaction.isBoundTo(dataSource)) {
            CallerTransaction.insertUnderSavepoint(dataSource, sql, entries);
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                for (int index = 0; index < entries.size(); index++) {
                    insert.setObject(index + 1, entries.get(index).getValue());
                }
                insert.executeUpdate();
            }
            connection.commit();
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /**
     * The caller's Spring-managed JDBC connection, when there is one. spring-jdbc is compileOnly here
     * (runtime-support also runs outside a Spring app), so every Spring type stays behind
     * {@link #isBoundTo(DataSource)}'s class-presence check and is never loaded when it is absent.
     */
    private static final class CallerTransaction {
        private static final boolean SPRING_JDBC_PRESENT = isPresent(
                "org.springframework.jdbc.datasource.DataSourceUtils");

        private static boolean isPresent(String className) {
            try {
                Class.forName(className, false, GeneratedCrudRuntimeSupport.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError absent) {
                return false;
            }
        }

        // Bound, not merely "a transaction is active": if this DataSource is not the one the
        // transaction manager bound a connection for, DataSourceUtils would hand back a fresh
        // unbound connection and the insert would never be committed by anyone.
        static boolean isBoundTo(DataSource dataSource) {
            return SPRING_JDBC_PRESENT && SpringJdbc.hasBoundConnection(dataSource);
        }

        static void insertUnderSavepoint(DataSource dataSource, String sql,
                                         List<Map.Entry<String, Object>> entries) throws SQLException {
            SpringJdbc.insertUnderSavepoint(dataSource, sql, entries);
        }

        private static final class SpringJdbc {
            static boolean hasBoundConnection(DataSource dataSource) {
                return org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive()
                        && org.springframework.transaction.support.TransactionSynchronizationManager
                        .hasResource(dataSource);
            }

            static void insertUnderSavepoint(DataSource dataSource, String sql,
                                             List<Map.Entry<String, Object>> entries) throws SQLException {
                Connection connection = org.springframework.jdbc.datasource.DataSourceUtils
                        .getConnection(dataSource);
                try {
                    java.sql.Savepoint savepoint = connection.setSavepoint();
                    try (PreparedStatement insert = connection.prepareStatement(sql)) {
                        for (int index = 0; index < entries.size(); index++) {
                            insert.setObject(index + 1, entries.get(index).getValue());
                        }
                        insert.executeUpdate();
                    } catch (SQLException | RuntimeException failure) {
                        connection.rollback(savepoint);
                        throw failure;
                    }
                } finally {
                    org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection, dataSource);
                }
            }
        }
    }
}
