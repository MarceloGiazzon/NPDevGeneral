package com.npdev.runtime.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledEvent;
import com.npdev.dsl.v1.compiled.CompiledEventField;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledOrchestration;
import com.npdev.dsl.v1.compiled.CompiledOrchestrationAction;
import com.npdev.dsl.v1.compiled.CompiledOrchestrationTrigger;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.FlowStepDefinition;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.InvariantScopeProvider;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.PersistenceCapability;
import com.npdev.kernel.ports.RuntimeInvariantEngineFactory;
import com.npdev.kernel.security.PermissionDecision;
import com.npdev.kernel.security.PermissionRequirement;
import com.npdev.kernel.security.PermissionSubject;
import com.npdev.runtime.support.crud.bonds.BondRuntimeShape;
import com.npdev.runtime.support.crud.valuecoercion.DslTypeCoercionSupport;
import com.npdev.runtime.support.crud.orchestration.EventCapabilityOrchestration;
import com.npdev.runtime.support.crud.orchestration.EventCreateOrchestration;
import com.npdev.runtime.support.crud.orchestration.EventScheduleOrchestration;
import com.npdev.runtime.support.crud.orchestration.OrchestrationActionExecutionResult;
import com.npdev.runtime.support.crud.orchestration.OrchestrationExecutionClaim;
import com.npdev.runtime.support.crud.orchestration.RuntimeOrchestration;
import com.npdev.runtime.support.crud.orchestration.RuntimeOrchestrationAction;
import com.npdev.runtime.support.crud.orchestration.ScheduledEventRecord;
import com.npdev.runtime.support.crud.scheduling.ScheduledEventSql;
import com.npdev.runtime.support.crud.uniqueness.CompoundUniqueFieldLookup;
import com.npdev.runtime.support.crud.uniqueness.CompoundUniqueValueLookup;
import com.npdev.runtime.support.crud.uniqueness.UniqueFieldLookup;
import com.npdev.runtime.support.crud.uniqueness.UniqueValueLookup;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.npdev.runtime.support.crud.bonds.BondNamingSupport.normalizeTenantForBondCheck;
import static com.npdev.runtime.support.crud.bonds.BondNamingSupport.referenceTargetName;
import static com.npdev.runtime.support.crud.condition.ConditionValueSupport.asBoolean;
import static com.npdev.runtime.support.crud.condition.ConditionValueSupport.readPathValue;
import static com.npdev.runtime.support.crud.condition.ConditionValueSupport.resolveConditionValue;
import static com.npdev.runtime.support.crud.condition.ConditionValueSupport.resolveStateMachineGuardValue;
import static com.npdev.runtime.support.crud.condition.ConditionValueSupport.valuesEqual;
import static com.npdev.runtime.support.crud.executioncontext.PayloadClaimsSupport.asNonBlankString;
import static com.npdev.runtime.support.crud.executioncontext.PayloadClaimsSupport.extractCurrentId;
import static com.npdev.runtime.support.crud.executioncontext.PayloadClaimsSupport.immutablePayload;
import static com.npdev.runtime.support.crud.executioncontext.PayloadClaimsSupport.parseRoles;
import static com.npdev.runtime.support.crud.executioncontext.PayloadClaimsSupport.toPayloadMap;
import static com.npdev.runtime.support.crud.orchestration.OrchestrationSupport.coerceMappedValue;
import static com.npdev.runtime.support.crud.orchestration.OrchestrationSupport.extractResultAdapterId;
import static com.npdev.runtime.support.crud.orchestration.OrchestrationSupport.resolveOrchestrationSubjectId;
import static com.npdev.runtime.support.crud.orchestration.OrchestrationSupport.serializePayloadForIdempotency;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.extractVersion;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.hasMapKey;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.isLifecycleMissing;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.readLifecycleToken;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.readLifecycleValue;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.readObjectValue;
import static com.npdev.runtime.support.crud.schemaexpr.SchemaExpressionSupport.cloneSchemaDefaultValue;
import static com.npdev.runtime.support.crud.schemaexpr.SchemaExpressionSupport.evaluateSchemaExpression;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.buildScheduleEvidencePayload;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.buildScheduleKey;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.nullToEmpty;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.sanitizeScheduleLimit;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.sanitizeScheduleOffset;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.toScheduledEventRecord;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.columnName;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.existsByIdSql;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.fetchCurrentStatusSql;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.tableName;
import static com.npdev.runtime.support.crud.uniqueness.InvariantMessageSupport.collectUniqueInvariantRefs;
import static com.npdev.runtime.support.crud.uniqueness.InvariantMessageSupport.extractFieldPath;
import static com.npdev.runtime.support.crud.uniqueness.InvariantMessageSupport.extractPathFromMessage;
import static com.npdev.runtime.support.crud.uniqueness.InvariantMessageSupport.inferInvariantFromMessage;
import static com.npdev.runtime.support.crud.valuecoercion.DslTypeCoercionSupport.normalizeByDslType;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.normalizeType;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.readPayloadValue;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.referenceValuesEqual;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toBoolean;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toInteger;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toLong;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toOffsetDateTime;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toTimestamp;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toUuid;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.writeObjectValue;

/**
 * Runtime support for generated CRUD services.
 *
 * Generated services stay thin and delegate invariant evaluation plus event publication
 * to this runtime-owned component.
 */
public final class GeneratedCrudRuntimeSupport {
    private static final Logger LOG = Logger.getLogger(GeneratedCrudRuntimeSupport.class.getName());
    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";
    public static final String SCHEDULE_TABLE = "npdev_scheduled_event";
    private static final String SCHEDULE_STATUS_PENDING = "PENDING";
    private static final String SCHEDULE_STATUS_PROCESSING = "PROCESSING";
    private static final String SCHEDULE_STATUS_PROCESSED = "PROCESSED";
    private static final String SCHEDULE_STATUS_FAILED = "FAILED";
    private static final int DEFAULT_SCHEDULE_DELAY_SECONDS = 60;

    public record InvariantViolationDetail(
            String code,
            String concept,
            String invariant,
            String path,
            String message,
            boolean unique
    ) {
        public Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", code);
            out.put("concept", concept);
            out.put("invariant", invariant);
            out.put("path", path);
            out.put("message", message);
            return out;
        }
    }

    public static final class InvariantViolationException extends RuntimeException {
        private final List<InvariantViolationDetail> violations;
        private final int statusCode;

        public InvariantViolationException(List<InvariantViolationDetail> violations) {
            super(joinMessages(violations));
            this.violations = violations == null ? List.of() : List.copyOf(violations);
            this.statusCode = hasUniqueViolation(this.violations) ? 409 : 422;
        }

        public List<InvariantViolationDetail> violations() {
            return violations;
        }

        public int statusCode() {
            return statusCode;
        }

        public Map<String, Object> toResponseBody() {
            InvariantViolationDetail first = violations.isEmpty()
                    ? new InvariantViolationDetail(
                    "invariant_failed",
                    null,
                    null,
                    null,
                    getMessage(),
                    false
            )
                    : violations.get(0);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", first.code());
            body.put("concept", first.concept());
            body.put("invariant", first.invariant());
            body.put("path", first.path());
            body.put("message", first.message());
            body.put("violations", violations.stream().map(InvariantViolationDetail::asMap).toList());
            return body;
        }

        private static String joinMessages(List<InvariantViolationDetail> violations) {
            if (violations == null || violations.isEmpty()) {
                return "Validation failed";
            }
            return violations.stream()
                    .map(InvariantViolationDetail::message)
                    .filter(message -> message != null && !message.isBlank())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Validation failed");
        }

        private static boolean hasUniqueViolation(List<InvariantViolationDetail> violations) {
            if (violations == null || violations.isEmpty()) {
                return false;
            }
            return violations.stream().anyMatch(InvariantViolationDetail::unique);
        }
    }

    private final CompiledModel compiledModel;
    private final KernelRunner kernelRunner;
    private final EntityManager entityManager;
    private final CapabilityDispatcher capabilityDispatcher;
    private final CapabilityRegistry capabilityRegistry;
    private final DataSource dataSource;
    private final RuntimeClock runtimeClock;
    private final OrchestrationExecutionRegistry orchestrationExecutionRegistry;
    private final RuntimeInvariantEngineFactory runtimeInvariantEngineFactory;
    private final AuditLogStore auditLogStore;
    private final PermissionEvaluator permissionEvaluator;
    private final IdempotencyStore idempotencyStore;
    // REG-177: resolved ONCE at construction (not per-request) from the already-available
    // compiledModel -- empty when this app doesn't compose the identity pack at all
    // (internal.tables=false is a normal, supported configuration here, not an error).
    private final Optional<IdentityPackTableNames> identityTables;
    private ConceptGateway conceptGateway;

    // Fallback existence check for cross-concept reference validation when there is no
    // EntityManager (e.g. npdev.storage.mode=in-memory). Set via withConceptGateway after
    // construction rather than threaded through the constructor overloads below, since this
    // is an optional capability rather than a required dependency.
    public GeneratedCrudRuntimeSupport withConceptGateway(ConceptGateway conceptGateway) {
        this.conceptGateway = conceptGateway;
        return this;
    }

    public GeneratedCrudRuntimeSupport(CompiledModel compiledModel, KernelRunner kernelRunner) {
        this(compiledModel, kernelRunner, null, null, null, null);
    }

    public GeneratedCrudRuntimeSupport(
            CompiledModel compiledModel,
            KernelRunner kernelRunner,
            EntityManager entityManager
    ) {
        this(compiledModel, kernelRunner, entityManager, null, null, null);
    }

    public GeneratedCrudRuntimeSupport(
            CompiledModel compiledModel,
            KernelRunner kernelRunner,
            EntityManager entityManager,
            CapabilityDispatcher capabilityDispatcher,
            CapabilityRegistry capabilityRegistry
    ) {
        this(compiledModel, kernelRunner, entityManager, capabilityDispatcher, capabilityRegistry, null);
    }

    public GeneratedCrudRuntimeSupport(
            CompiledModel compiledModel,
            KernelRunner kernelRunner,
            EntityManager entityManager,
            CapabilityDispatcher capabilityDispatcher,
            CapabilityRegistry capabilityRegistry,
            DataSource dataSource
    ) {
        this(
                compiledModel,
                kernelRunner,
                entityManager,
                capabilityDispatcher,
                capabilityRegistry,
                dataSource,
                new SystemRuntimeClock(),
                new InMemoryOrchestrationExecutionRegistry(),
                missingRuntimeInvariantEngineFactory()
        );
    }

    public GeneratedCrudRuntimeSupport(
            CompiledModel compiledModel,
            KernelRunner kernelRunner,
            EntityManager entityManager,
            CapabilityDispatcher capabilityDispatcher,
            CapabilityRegistry capabilityRegistry,
            DataSource dataSource,
            RuntimeClock runtimeClock,
            OrchestrationExecutionRegistry orchestrationExecutionRegistry
    ) {
        this(
                compiledModel,
                kernelRunner,
                entityManager,
                capabilityDispatcher,
                capabilityRegistry,
                dataSource,
                runtimeClock,
                orchestrationExecutionRegistry,
                missingRuntimeInvariantEngineFactory()
        );
    }

    public GeneratedCrudRuntimeSupport(
            CompiledModel compiledModel,
            KernelRunner kernelRunner,
            EntityManager entityManager,
            CapabilityDispatcher capabilityDispatcher,
            CapabilityRegistry capabilityRegistry,
            DataSource dataSource,
            RuntimeClock runtimeClock,
            OrchestrationExecutionRegistry orchestrationExecutionRegistry,
            RuntimeInvariantEngineFactory runtimeInvariantEngineFactory
    ) {
        this(compiledModel, kernelRunner, entityManager, capabilityDispatcher, capabilityRegistry,
                dataSource, runtimeClock, orchestrationExecutionRegistry, runtimeInvariantEngineFactory,
                null, null, null);
    }

    public GeneratedCrudRuntimeSupport(
            CompiledModel compiledModel,
            KernelRunner kernelRunner,
            EntityManager entityManager,
            CapabilityDispatcher capabilityDispatcher,
            CapabilityRegistry capabilityRegistry,
            DataSource dataSource,
            RuntimeClock runtimeClock,
            OrchestrationExecutionRegistry orchestrationExecutionRegistry,
            RuntimeInvariantEngineFactory runtimeInvariantEngineFactory,
            AuditLogStore auditLogStore,
            PermissionEvaluator permissionEvaluator,
            IdempotencyStore idempotencyStore
    ) {
        if (compiledModel == null) {
            throw new IllegalArgumentException("compiledModel is required");
        }
        if (kernelRunner == null) {
            throw new IllegalArgumentException("kernelRunner is required");
        }
        this.compiledModel = compiledModel;
        this.kernelRunner = kernelRunner;
        this.entityManager = entityManager;
        this.capabilityDispatcher = capabilityDispatcher;
        this.capabilityRegistry = capabilityRegistry;
        this.dataSource = dataSource;
        this.runtimeClock = runtimeClock == null ? new SystemRuntimeClock() : runtimeClock;
        this.orchestrationExecutionRegistry = orchestrationExecutionRegistry == null
                ? new InMemoryOrchestrationExecutionRegistry()
                : orchestrationExecutionRegistry;
        this.runtimeInvariantEngineFactory = runtimeInvariantEngineFactory == null
                ? missingRuntimeInvariantEngineFactory()
                : runtimeInvariantEngineFactory;
        this.auditLogStore = auditLogStore == null ? AuditLogStore.noop() : auditLogStore;
        this.permissionEvaluator = permissionEvaluator == null ? PermissionEvaluator.allowAll() : permissionEvaluator;
        this.idempotencyStore = idempotencyStore == null ? IdempotencyStore.noop() : idempotencyStore;
        this.identityTables = IdentityPackTableNames.tryResolve(compiledModel);
        initializeOrchestrationSubscribers();
        // R2.4: the kernel's scheduleEvent flow step needs the durable table this class owns, and
        // :kernel cannot depend on this adapter. Registered here rather than in a Spring @Bean for
        // the same reason initializeOrchestrationSubscribers() is: this constructor is already the
        // one place that holds both the KernelRunner and the DataSource, and every generated app
        // builds this object exactly once.
        kernelRunner.withDeferredEventScheduler(this::scheduleDeferredFlowEvent);
    }

    private static RuntimeInvariantEngineFactory missingRuntimeInvariantEngineFactory() {
        return new RuntimeInvariantEngineFactory() {
            @Override
            public InvariantEngine create(UniqueValueLookup uniqueValueLookup, ConflictLookup conflictLookup) {
                throw new IllegalStateException("RuntimeInvariantEngineFactory is required");
            }
        };
    }

    public static <T, ID> PersistenceCapability<T, ID> persistenceCapability(
            Function<ID, Optional<T>> findById,
            Supplier<List<T>> findAll,
            Function<T, T> save,
            Predicate<ID> existsById,
            Consumer<ID> deleteById,
            UniqueFieldLookup<ID> uniqueFieldLookup
    ) {
        return persistenceCapability(findById, findAll, save, existsById, deleteById, uniqueFieldLookup, null);
    }

    /** LIFT-UNIQUE-P3: overload adding a compound-unique field lookup. */
    public static <T, ID> PersistenceCapability<T, ID> persistenceCapability(
            Function<ID, Optional<T>> findById,
            Supplier<List<T>> findAll,
            Function<T, T> save,
            Predicate<ID> existsById,
            Consumer<ID> deleteById,
            UniqueFieldLookup<ID> uniqueFieldLookup,
            CompoundUniqueFieldLookup<ID> compoundUniqueFieldLookup
    ) {
        return new PersistenceCapability<>() {
            @Override
            public Optional<T> findById(ID id) {
                return findById == null ? Optional.empty() : findById.apply(id);
            }

            @Override
            public List<T> findAll() {
                if (findAll == null) {
                    return List.of();
                }
                List<T> results = findAll.get();
                return results == null ? List.of() : results;
            }

            @Override
            public T save(T entity) {
                if (save == null) {
                    throw new IllegalStateException("Persistence save function is required");
                }
                return save.apply(entity);
            }

            @Override
            public boolean existsById(ID id) {
                return existsById != null && existsById.test(id);
            }

            @Override
            public void deleteById(ID id) {
                if (deleteById != null) {
                    deleteById.accept(id);
                }
            }

            @Override
            public boolean existsUnique(String fieldName, Object value, ID excludeId) {
                return uniqueFieldLookup != null && uniqueFieldLookup.exists(fieldName, value, excludeId);
            }

            @Override
            public boolean existsUniqueCompound(List<String> fieldNames, List<Object> values, ID excludeId) {
                return compoundUniqueFieldLookup != null
                        && compoundUniqueFieldLookup.exists(fieldNames, values, excludeId);
            }
        };
    }

    public Map<String, Object> buildCreateInvariantPayload(String entityName, Object dto) {
        CompiledConcept entity = requireEntity(entityName);
        return new LinkedHashMap<>(materializeEntityValues(entity, dto, null, false));
    }

    public Map<String, Object> buildUpdateInvariantPayload(
            String entityName,
            UUID id,
            Object existing,
            Object dto
    ) {
        CompiledConcept entity = requireEntity(entityName);
        Map<String, Object> payload = new LinkedHashMap<>(materializeEntityValues(entity, dto, existing, true));
        payload.put("__id", id);
        payload.put("id", id);
        return payload;
    }

    /**
     * Closes a real cross-tenant data-integrity gap: the FK constraint on a scalar bond column only
     * checks that the referenced ROW EXISTS, never that it belongs to the CALLER's own tenant. Without
     * this, tenant A can create a row whose bond field points at tenant B's private business data --
     * confirmed live (a StaffMember create with a cross-tenant tenantRef succeeded with 200) before
     * this check existed. For every non-M2M reference field present in the payload, requires that the
     * target row exists AND its tenant_id matches the caller's tenant; otherwise throws the same
     * InvariantViolationException shape every other CRUD validation failure uses, deliberately worded
     * like a not-found rather than a forbidden, so it never confirms a row exists in another tenant.
     */
    public void enforceBondTargetTenant(String entityName, Map<String, Object> payload, ExecutionContext context) {
        if (payload == null || dataSource == null) {
            return;
        }
        CompiledConcept entity = requireEntity(entityName);
        String callerTenant = normalizeTenantForBondCheck(context == null ? null : context.tenantId());
        for (CompiledField field : entity.getFields()) {
            if (field == null || field.isId()) {
                continue;
            }
            CompiledReferenceSemantics semantics = field.getReferenceSemantics();
            if (semantics != null && semantics.isMultiple()) {
                continue; // many-to-many lives in a junction table, not a column on this payload
            }
            String targetName = referenceTargetName(field);
            if (targetName == null || targetName.isBlank()) {
                continue; // not a reference field at all
            }
            Object rawValue = readMapValue(payload, field.getName());
            if (rawValue == null) {
                continue; // optional reference left unset
            }
            CompiledConcept targetEntity = findEntity(targetName).orElse(null);
            if (targetEntity == null) {
                continue; // unresolvable target name is a model problem, not this caller's to diagnose
            }
            CompiledField anchor = resolveReferenceAnchor(field, targetEntity).orElse(null);
            if (anchor == null) {
                continue;
            }
            String table = SqlIdentifierSupport.tableName(targetEntity);
            String column = SqlIdentifierSupport.columnName(anchor);
            if (!bondTargetExistsForTenant(table, column, rawValue, callerTenant)) {
                throw new InvariantViolationException(List.of(new InvariantViolationDetail(
                        "bond_target_not_found",
                        entityName,
                        "BondTenantScope",
                        field.getName(),
                        "Referenced " + targetName + " was not found",
                        false
                )));
            }
        }
    }

    private boolean bondTargetExistsForTenant(String table, String column, Object value, String tenantId) {
        String sql = "SELECT 1 FROM " + table + " WHERE " + column + " = ? AND tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            statement.setString(2, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed validating bond target tenant for table " + table, exception);
        }
    }

    public UUID ensureGeneratedId(Map<String, Object> payload) {
        if (payload == null) {
            return UUID.randomUUID();
        }
        UUID existing = toUuid(readMapValue(payload, "id"));
        if (existing != null) {
            payload.put("id", existing);
            return existing;
        }
        UUID generatedId = UUID.randomUUID();
        payload.put("id", generatedId);
        return generatedId;
    }

    public UUID ensureGeneratedId(String entityName, Map<String, Object> payload) {
        if (payload == null) {
            return UUID.randomUUID();
        }
        String idField = idFieldName(entityName);
        UUID existing = toUuid(readMapValue(payload, idField));
        if (existing == null) {
            existing = toUuid(readMapValue(payload, "id"));
        }
        if (existing != null) {
            payload.put(idField, existing);
            payload.put("id", existing);
            return existing;
        }
        UUID generatedId = UUID.randomUUID();
        payload.put(idField, generatedId);
        payload.put("id", generatedId);
        return generatedId;
    }

    public void assignGeneratedId(Object entity, UUID generatedId) {
        if (entity == null || generatedId == null) {
            return;
        }
        Object currentId = readObjectValue(entity, "id");
        if (currentId != null) {
            return;
        }
        writeObjectValue(entity, "id", generatedId);
    }

    public void assignGeneratedId(String entityName, Object entity, UUID generatedId) {
        if (entity == null || generatedId == null) {
            return;
        }
        String idField = idFieldName(entityName);
        Object currentId = readObjectValue(entity, idField);
        if (currentId != null) {
            return;
        }
        writeObjectValue(entity, idField, generatedId);
    }

    public void applyCreateFields(String entityName, Object source, Object target) {
        applyEntityFields(entityName, source, target, false);
    }

    public void applyUpdateFields(String entityName, Object source, Object target) {
        applyEntityFields(entityName, source, target, true);
    }

    // No version in the request means no check, so callers unaware of versioning are unaffected.
    public void checkOptimisticVersion(String conceptName, Object existing, Object source) {
        Long expected = extractVersion(source);
        if (expected == null) {
            return;
        }
        Long actual = extractVersion(existing);
        if (actual == null || !actual.equals(expected)) {
            throw new InvariantViolationException(List.of(new InvariantViolationDetail(
                    "version_conflict",
                    conceptName,
                    "optimisticConcurrency",
                    "version",
                    "Record was modified by another request. expected=" + expected + " actual=" + actual,
                    true
            )));
        }
    }

    public List<Object> listBondMembers(String sourceConceptName, Object sourceId, String fieldName) {
        BondRuntimeShape shape = requireBondRuntimeShape(sourceConceptName, fieldName);
        if (dataSource == null) {
            return List.of();
        }
        String sql = "SELECT " + shape.targetColumn() + " FROM " + shape.junctionTable()
                + " WHERE CAST(" + shape.sourceColumn() + " AS VARCHAR) = ? ORDER BY " + shape.targetColumn();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, String.valueOf(normalizeByDslType(shape.sourceIdField().getDslType(), sourceId)));
            try (ResultSet rows = statement.executeQuery()) {
                List<Object> out = new ArrayList<>();
                while (rows.next()) {
                    out.add(rows.getObject(1));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed listing bond members for "
                    + sourceConceptName + "." + fieldName, exception);
        }
    }

    public void addBondMember(String sourceConceptName, Object sourceId, String fieldName, Object targetAnchorValue) {
        BondRuntimeShape shape = requireBondRuntimeShape(sourceConceptName, fieldName);
        requireDataSourceForBond(shape);
        String sql = "INSERT INTO " + shape.junctionTable()
                + " (" + shape.sourceColumn() + ", " + shape.targetColumn() + ") VALUES (?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, normalizeByDslType(shape.sourceIdField().getDslType(), sourceId));
            statement.setObject(2, normalizeByDslType(shape.targetAnchorField().getDslType(), targetAnchorValue));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw mapDataIntegrityViolation(sourceConceptName, fieldName, exception)
                    .orElseThrow(() -> new IllegalStateException("Failed adding bond member for "
                            + sourceConceptName + "." + fieldName, exception));
        }
    }

    public void removeBondMember(String sourceConceptName, Object sourceId, String fieldName, Object targetAnchorValue) {
        BondRuntimeShape shape = requireBondRuntimeShape(sourceConceptName, fieldName);
        requireDataSourceForBond(shape);
        String sql = "DELETE FROM " + shape.junctionTable()
                + " WHERE CAST(" + shape.sourceColumn() + " AS VARCHAR) = ?"
                + " AND CAST(" + shape.targetColumn() + " AS VARCHAR) = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, String.valueOf(normalizeByDslType(shape.sourceIdField().getDslType(), sourceId)));
            statement.setString(2, String.valueOf(normalizeByDslType(shape.targetAnchorField().getDslType(), targetAnchorValue)));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw mapDataIntegrityViolation(sourceConceptName, fieldName, exception)
                    .orElseThrow(() -> new IllegalStateException("Failed removing bond member for "
                            + sourceConceptName + "." + fieldName, exception));
        }
    }

    public void replaceBondMembers(String sourceConceptName, Object sourceId, String fieldName, Collection<?> targetAnchorValues) {
        BondRuntimeShape shape = requireBondRuntimeShape(sourceConceptName, fieldName);
        requireDataSourceForBond(shape);
        String deleteSql = "DELETE FROM " + shape.junctionTable()
                + " WHERE CAST(" + shape.sourceColumn() + " AS VARCHAR) = ?";
        String insertSql = "INSERT INTO " + shape.junctionTable()
                + " (" + shape.sourceColumn() + ", " + shape.targetColumn() + ") VALUES (?, ?)";
        Object normalizedSourceId = normalizeByDslType(shape.sourceIdField().getDslType(), sourceId);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(deleteSql)) {
                delete.setString(1, String.valueOf(normalizedSourceId));
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                for (Object targetAnchorValue : targetAnchorValues == null ? List.of() : targetAnchorValues) {
                    insert.setObject(1, normalizedSourceId);
                    insert.setObject(2, normalizeByDslType(shape.targetAnchorField().getDslType(), targetAnchorValue));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            connection.setAutoCommit(previousAutoCommit);
        } catch (SQLException exception) {
            throw mapDataIntegrityViolation(sourceConceptName, fieldName, exception)
                    .orElseThrow(() -> new IllegalStateException("Failed replacing bond members for "
                            + sourceConceptName + "." + fieldName, exception));
        }
    }

    public List<Map<String, Object>> listScheduledEvents(Integer limit, Integer offset) {
        if (dataSource == null) {
            return List.of();
        }
        int safeLimit = sanitizeScheduleLimit(limit);
        int safeOffset = sanitizeScheduleOffset(offset);
        SqlDialect dialect = SqlDialects.active();
        String sql = dialect.paginated("SELECT id, schedule_key, orchestration_name, action_index, source_event_name, source_event_id, "
                + "trigger_correlation_id, event_name, due_at, status, attempt_count, created_at, "
                + "updated_at, processed_at, payload "
                + "FROM " + SCHEDULE_TABLE + " "
                + "ORDER BY created_at DESC ").stripTrailing();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int pageIndex = 1;
            for (int pageValue : dialect.limitOffset().values(safeLimit, safeOffset)) {
                statement.setInt(pageIndex++, pageValue);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<Map<String, Object>> schedules = new ArrayList<>();
                while (rows.next()) {
                    ScheduledEventRecord record = toScheduledEventRecord(rows);
                    if (record == null) {
                        continue;
                    }
                    schedules.add(record.toMap());
                }
                return schedules.isEmpty() ? List.of() : List.copyOf(schedules);
            }
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "Failed to list scheduled events", exception);
            return List.of();
        }
    }

    public Map<String, Object> processDueScheduledEvents(Boolean forceDue, Integer limit) {
        Map<String, Object> summary = new LinkedHashMap<>();
        boolean force = forceDue != null && forceDue;
        int safeLimit = sanitizeScheduleLimit(limit);
        summary.put("forceDue", force);
        summary.put("limit", safeLimit);
        summary.put("scanned", 0);
        summary.put("processed", 0);
        summary.put("skipped", 0);
        summary.put("failed", 0);

        if (dataSource == null) {
            summary.put("status", "scheduler_unavailable");
            return Map.copyOf(summary);
        }

        List<ScheduledEventRecord> dueRecords = selectDueScheduledEvents(force, safeLimit);
        if (dueRecords.isEmpty()) {
            summary.put("status", "no_due_records");
            return Map.copyOf(summary);
        }

        int scanned = 0;
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        for (ScheduledEventRecord record : dueRecords) {
            if (record == null || record.id() == null) {
                continue;
            }
            scanned++;
            if (!claimScheduledEvent(record.id())) {
                skipped++;
                continue;
            }

            try {
                Map<String, Object> payload = new LinkedHashMap<>(record.payload());
                payload.putIfAbsent("scheduleId", record.id().toString());
                payload.putIfAbsent("sourceEventName", record.sourceEventName());
                payload.putIfAbsent("sourceEventId", record.sourceEventId());
                publishRuntimeEvent(
                        record.eventName(),
                        payload,
                        Map.of(
                                "orchestrationAction", "scheduleEvent",
                                "scheduleId", record.id().toString(),
                                "sourceEventName", nullToEmpty(record.sourceEventName())
                        )
                );
                markScheduledEventProcessed(record.id());
                processed++;
                publishRuntimeEvent(
                        "OrchestrationScheduleProcessed",
                        buildScheduleEvidencePayload(
                                "scheduleId", record.id(),
                                "eventName", record.eventName(),
                                "sourceEventName", record.sourceEventName(),
                                "sourceEventId", record.sourceEventId(),
                                "orchestration", record.orchestrationName(),
                                "payloadKeys", new ArrayList<>(record.payload().keySet())
                        ),
                        Map.of(
                                "orchestrationAction", "scheduleEvent",
                                "status", "processed"
                        )
                    );
            } catch (Exception exception) {
                failed++;
                markScheduledEventFailed(record.id());
                publishRuntimeEvent(
                        "OrchestrationScheduleFailed",
                        buildScheduleEvidencePayload(
                                "scheduleId", record.id(),
                                "eventName", record.eventName(),
                                "sourceEventName", record.sourceEventName(),
                                "sourceEventId", record.sourceEventId(),
                                "orchestration", record.orchestrationName(),
                                "error", exception.getMessage() == null ? "schedule_emit_failed" : exception.getMessage()
                        ),
                        Map.of(
                                "orchestrationAction", "scheduleEvent",
                                "status", "failed"
                        )
                );
                LOG.log(Level.WARNING, "Failed to process scheduled event " + record.id(), exception);
            }
        }

        summary.put("status", "ok");
        summary.put("scanned", scanned);
        summary.put("processed", processed);
        summary.put("skipped", skipped);
        summary.put("failed", failed);
        return Map.copyOf(summary);
    }

    public List<String> validateEntity(
            String entityName,
            Map<String, Object> payload,
            UniqueValueLookup uniqueValueLookup
    ) {
        return validateEntity(entityName, payload, uniqueValueLookup, null);
    }

    /** LIFT-UNIQUE-P3: overload adding a compound-unique lookup. */
    public List<String> validateEntity(
            String entityName,
            Map<String, Object> payload,
            UniqueValueLookup uniqueValueLookup,
            CompoundUniqueValueLookup compoundUniqueValueLookup
    ) {
        return validateEntityDetailed(entityName, payload, uniqueValueLookup, compoundUniqueValueLookup).stream()
                .map(InvariantViolationDetail::message)
                .toList();
    }

    public List<InvariantViolationDetail> validateEntityDetailed(
            String entityName,
            Map<String, Object> payload,
            UniqueValueLookup uniqueValueLookup
    ) {
        return validateEntityDetailed(entityName, payload, uniqueValueLookup, null);
    }

    /** LIFT-UNIQUE-P3: overload adding a compound-unique lookup. */
    public List<InvariantViolationDetail> validateEntityDetailed(
            String entityName,
            Map<String, Object> payload,
            UniqueValueLookup uniqueValueLookup,
            CompoundUniqueValueLookup compoundUniqueValueLookup
    ) {
        Optional<CompiledConcept> entityOpt = findEntity(entityName);
        if (entityOpt.isEmpty()) {
            return List.of(new InvariantViolationDetail(
                    "invariant_failed",
                    entityName,
                    "unknown_entity",
                    null,
                    "Unknown entity for runtime validation: " + entityName,
                    false
            ));
        }

        CompiledConcept entity = entityOpt.get();
        Map<String, Object> safePayload = immutablePayload(payload);
        Map<String, Object> normalizedPayload = normalizePayloadForValidation(entity, safePayload);
        List<InvariantViolationDetail> violations = new ArrayList<>();
        for (String message : validateFieldTypesAndReferences(entity, normalizedPayload)) {
            violations.add(toTypedViolation(entityName, message));
        }
        validateLifecycleTransitions(entityName, entity, normalizedPayload, violations);

        InvariantEngine engine = runtimeInvariantEngineFactory.create(
                (requestedEntity, fieldName, value, rawPayload) -> uniqueValueLookup != null
                        && uniqueValueLookup.exists(
                        requestedEntity,
                        fieldName,
                        value,
                        extractCurrentId(rawPayload),
                        toPayloadMap(rawPayload, normalizedPayload)
                ),
                new RuntimeInvariantEngineFactory.ConflictLookup() {
                    @Override
                    public boolean conflicts(
                            String resourceField,
                            Object resourceId,
                            String startsAtField,
                            Object startsAt,
                            String durationField,
                            Object durationMinutes,
                            Object excludeId,
                            Object payload
                    ) {
                        return resourceHasConflict(
                                entityName,
                                resourceField,
                                resourceId,
                                startsAtField,
                                startsAt,
                                durationField,
                                durationMinutes,
                                excludeId,
                                payload
                        );
                    }
                },
                new InvariantScopeProvider() {
                    @Override
                    public boolean exists(
                            String conceptName,
                            String fieldPath,
                            Object expectedValue,
                            Map<String, Object> state,
                            Object payload
                    ) {
                        return scopeExists(conceptName, fieldPath, expectedValue);
                    }
                },
                (requestedEntity, fieldNames, values, rawPayload) -> compoundUniqueValueLookup != null
                        && compoundUniqueValueLookup.exists(
                        requestedEntity,
                        fieldNames,
                        values,
                        extractCurrentId(rawPayload),
                        toPayloadMap(rawPayload, normalizedPayload)
                )
        );
        List<String> refs = entity.getInvariants().stream()
                .filter(invariant -> invariant != null && invariant.getRef() != null && !invariant.getRef().isBlank())
                .map(invariant -> invariant.getRef().trim())
                .toList();

        if (!refs.isEmpty()) {
            Set<String> uniqueInvariantRefs = collectUniqueInvariantRefs(entity);
            InvariantEngine.InvariantEvaluationResult result = engine.evaluate(new InvariantEngine.InvariantEvaluationRequest(
                    entityName,
                    normalizedPayload,
                    refs,
                    new InvariantEngine.EvaluationMetadata(
                            "generated-crud",
                            "validate-entity",
                            0,
                            FlowStepDefinition.InvariantCheckpoint.PRE,
                            null
                    ),
                    Map.of()
            ));
            for (InvariantEngine.Violation violation : result.violations()) {
                violations.add(toInvariantViolation(entityName, violation, uniqueInvariantRefs));
            }
        }

        return violations;
    }

    public void publishMutationEvent(
            String entityName,
            String action,
            UUID id,
            Object snapshot,
            Set<String> allowedTopics
    ) {
        String topic = entityName + "." + action;
        if (allowedTopics != null && !allowedTopics.contains(topic)) {
            throw new IllegalStateException("Undeclared mutation event topic: " + topic);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entity", entityName);
        payload.put("action", action);
        payload.put("id", id);
        if (snapshot != null) {
            payload.put("snapshot", snapshot);
        }

        publishRuntimeEvent(topic, payload, Map.of(
                "entity", entityName,
                "action", action
        ));
    }

    /**
     * Publishes an author-declared custom event (model.json's {@code events} section, a concept-
     * nested event with a {@code mode} field) directly from generated CRUD's mutation step --
     * closes the gap where reaching a custom event required declaring an entire Flow for that
     * concept+mode just to use its {@code emitEvent} step. Unlike {@link #publishMutationEvent}
     * (which always derives the topic as {@code entityName + "." + action}), the topic here is the
     * event's own declared name verbatim, since a custom event is not one of the 3 built-in
     * create/update/delete shapes.
     */
    public void publishDeclaredEvent(String eventName, String entityName, UUID id, Object snapshot) {
        if (eventName == null || eventName.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entity", entityName);
        payload.put("id", id);
        if (snapshot != null) {
            payload.put("snapshot", snapshot);
        }
        publishRuntimeEvent(eventName.trim(), payload, Map.of("entity", entityName));
    }

    public String captureLifecycleStatus(String entityName, Object snapshot) {
        if (snapshot == null) {
            return null;
        }
        Optional<CompiledConcept> entityOpt = findEntity(entityName);
        if (entityOpt.isEmpty()) {
            return null;
        }
        CompiledLifecycle lifecycle = entityOpt.get().getLifecycle();
        if (lifecycle == null) {
            return null;
        }
        String statusField = lifecycle.getStatusField() == null || lifecycle.getStatusField().isBlank()
                ? "status"
                : lifecycle.getStatusField().trim();
        return readLifecycleToken(readObjectValue(snapshot, statusField));
    }

    public void publishLifecycleTransitionEvent(
            String entityName,
            String previousStatus,
            Object updatedSnapshot
    ) {
        publishLifecycleTransitionEvent(entityName, previousStatus, updatedSnapshot, Map.of());
    }

    public void publishLifecycleTransitionEvent(
            String entityName,
            String previousStatus,
            Object updatedSnapshot,
            Map<String, Object> fallbackPayload
    ) {
        String previous = readLifecycleToken(previousStatus);
        if (updatedSnapshot == null || previous == null) {
            return;
        }

        Optional<CompiledConcept> entityOpt = findEntity(entityName);
        if (entityOpt.isEmpty()) {
            return;
        }
        CompiledConcept entity = entityOpt.get();
        CompiledLifecycle lifecycle = entity.getLifecycle();
        if (lifecycle == null || lifecycle.getTransitions().isEmpty()) {
            return;
        }

        String statusField = lifecycle.getStatusField() == null || lifecycle.getStatusField().isBlank()
                ? "status"
                : lifecycle.getStatusField().trim();
        String nextStatus = readLifecycleToken(readLifecycleValue(updatedSnapshot, fallbackPayload, statusField));
        if (nextStatus == null || normalize(nextStatus).equals(normalize(previous))) {
            return;
        }

        CompiledStateTransition transition = findLifecycleTransition(lifecycle, previous, nextStatus);
        if (transition == null || transition.getEvent() == null || transition.getEvent().isBlank()) {
            return;
        }

        String conceptName = entity.getName() == null || entity.getName().isBlank()
                ? entityName
                : entity.getName();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("concept", conceptName);
        payload.put("status", nextStatus);
        payload.put("previousStatus", previous);

        Object id = readLifecycleValue(updatedSnapshot, fallbackPayload, "id");
        if (id != null) {
            payload.put("id", id);
            payload.put("entityId", id);
            payload.put(toLowerCamel(conceptName) + "Id", id);
        }

        appendDeclaredEventPayloadFields(transition.getEvent().trim(), updatedSnapshot, fallbackPayload, payload);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("entity", conceptName);
        metadata.put("transition", previous + "->" + nextStatus);
        publishRuntimeEvent(transition.getEvent().trim(), payload, metadata);
    }

    private void appendDeclaredEventPayloadFields(
            String eventName,
            Object updatedSnapshot,
            Map<String, Object> fallbackPayload,
            Map<String, Object> payload
    ) {
        if (eventName == null || eventName.isBlank() || payload == null) {
            return;
        }
        Optional<CompiledEvent> eventOpt = compiledModel.getEvents().stream()
                .filter(event -> event != null && eventName.equals(event.getName()))
                .findFirst();
        if (eventOpt.isEmpty()) {
            return;
        }
        for (CompiledEventField field : eventOpt.get().getPayloadFields()) {
            if (field == null || field.getName() == null || field.getName().isBlank()) {
                continue;
            }
            String fieldName = field.getName().trim();
            if (payload.containsKey(fieldName)) {
                continue;
            }
            Object value = readLifecycleValue(updatedSnapshot, fallbackPayload, fieldName);
            if (value != null) {
                payload.put(fieldName, value);
            }
        }
    }

    private void publishRuntimeEvent(String eventName, Map<String, Object> payload, Map<String, Object> metadata) {
        if (eventName == null || eventName.isBlank()) {
            return;
        }
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        if (payload != null && !payload.isEmpty()) {
            eventPayload.putAll(payload);
        }
        if (metadata != null && !metadata.isEmpty()) {
            eventPayload.put("_meta", Map.copyOf(new LinkedHashMap<>(metadata)));
        }
        kernelRunner.publishExternalEvent(
                eventName,
                Map.copyOf(eventPayload),
                null,
                null,
                resolveCurrentExecutionContext()
        );
    }

    private List<String> initializeOrchestrationSubscribers() {
        if (compiledModel.getOrchestrationRules().isEmpty()) {
            return List.of();
        }
        List<String> subscribers = new ArrayList<>();
        for (CompiledOrchestration orchestration : compiledModel.getOrchestrationRules()) {
            RuntimeOrchestration runtimeOrchestration = toRuntimeOrchestration(orchestration);
            if (runtimeOrchestration == null) {
                continue;
            }
            try {
                kernelRunner.subscribeEvent(runtimeOrchestration.eventName(), envelope ->
                        handleEventOrchestration(runtimeOrchestration, envelope));
                subscribers.add(runtimeOrchestration.name());
            } catch (Exception exception) {
                LOG.log(Level.WARNING,
                        "Failed to register orchestration subscriber: " + runtimeOrchestration.name(),
                        exception);
            }
        }
        return subscribers.isEmpty() ? List.of() : List.copyOf(subscribers);
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

    private OrchestrationActionExecutionResult executeCreateOrchestrationAction(
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
        CapabilityResult result = capabilityDispatcher.invoke(call, Map.of(
                "orchestration", orchestrationName,
                "eventName", envelope.eventName(),
                "eventId", envelope.eventId()
        ));

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
    private boolean evaluateOrchestrationCondition(String rawCondition, Map<String, Object> eventPayload) {
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
        for (var capability : compiledModel.getCapabilities()) {
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
        for (var binding : compiledModel.getBindings()) {
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
        // An event-triggered orchestration runs outside the originating request's JPA transaction,
        // so it persists through its own short JDBC transaction. Using the shared EntityManager here
        // is not allowed (it surfaced as TransactionRequiredException); the DataSource path mirrors
        // the bond membership writes in this class.
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
     * Is this failure a unique violation? <b>Ask the dialect first; the message is the last resort.</b>
     *
     * <p>This method used to decide entirely by substring-matching the driver's message:
     *
     * <pre>
     *   contains("duplicate key") || contains("unique constraint")
     *       || contains("already exists") || contains("violates unique")
     * </pre>
     *
     * <p>Those four phrases are Postgres's wording. Measured, same request and same model on each
     * engine (STOR-11's parity half):
     *
     * <pre>
     *   Postgres     duplicate key value violates unique constraint "ux_accounts_email"   -> match -> 409
     *   SQL Server   Cannot insert duplicate key row ... with unique index                -> match -> 409
     *   MySQL        Duplicate entry 'x' for key 'accounts.ux_accounts_email'             -> NO match -> 500
     * </pre>
     *
     * <p>So the engine that returned 500 was not behaving differently -- it was PHRASING differently,
     * and two engines matched by luck. A user picking MySQL got a different status code and a
     * different response body for the identical mistake, which is the parity rule broken at the
     * layer that decides what the user sees.
     *
     * <p>{@link SqlDialect#isUniqueViolation} answers it per engine from SQLSTATE plus the vendor
     * error number, which is the only reliable discriminator (MySQL and SQL Server both report the
     * generic class 23000 for every integrity failure). The message test is kept BELOW it, not
     * removed: some callers wrap a non-SQL failure -- the in-memory store raises its own -- and
     * those have no SQLSTATE to inspect.
     */
    private static boolean isUniqueViolation(Exception exception) {
        if (exception == null) {
            return false;
        }
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException
                    && SqlDialects.active().isUniqueViolation(sqlException)) {
                return true;
            }
        }
        String message = exception.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("duplicate key")
                    || normalized.contains("unique constraint")
                    || normalized.contains("already exists")
                    || normalized.contains("violates unique")
                    // MySQL's own phrasing, so the fallback is not Postgres-only even when there is
                    // no SQLException to ask the dialect about.
                    || normalized.contains("duplicate entry")) {
                return true;
            }
        }
        Throwable cause = exception.getCause();
        if (cause instanceof Exception causeException) {
            return isUniqueViolation(causeException);
        }
        return false;
    }

    public static Optional<InvariantViolationException> mapDataIntegrityViolation(
            String conceptName,
            String fieldName,
            Exception exception
    ) {
        if (exception == null) {
            return Optional.empty();
        }
        String safeConcept = conceptName == null || conceptName.isBlank() ? null : conceptName;
        String safeField = fieldName == null || fieldName.isBlank() ? null : fieldName;
        if (exception instanceof com.npdev.kernel.concepts.ReferentialIntegrityException referentialIntegrityException) {
            return Optional.of(new InvariantViolationException(List.of(new InvariantViolationDetail(
                    "reference_integrity_failed",
                    safeConcept != null ? safeConcept : referentialIntegrityException.getConceptName(),
                    "in_memory_reference_constraint",
                    safeField != null ? safeField : referentialIntegrityException.getFieldName(),
                    referentialIntegrityException.getMessage(),
                    false
            ))));
        }
        if (isUniqueViolation(exception)) {
            return Optional.of(new InvariantViolationException(List.of(new InvariantViolationDetail(
                    "unique_integrity_failed",
                    safeConcept,
                    "database_unique_constraint",
                    safeField,
                    "A unique value already exists for " + (safeConcept == null ? "this concept" : safeConcept),
                    true
            ))));
        }
        if (isForeignKeyViolation(exception)) {
            return Optional.of(new InvariantViolationException(List.of(new InvariantViolationDetail(
                    "reference_integrity_failed",
                    safeConcept,
                    "database_reference_constraint",
                    safeField,
                    "A referenced record does not exist or is restricted by a bond",
                    false
            ))));
        }
        return Optional.empty();
    }

    /** HARDEN-GC-P1/P2: a bare (storeId, key) pointer into a {@link com.npdev.kernel.ports.FileStoreContract}. */
    public record FileHandleRef(String storeId, String key) {
    }

    /**
     * HARDEN-GC-P1/P2: reads every {@code FileHandle} referenced by a file-typed field's stored
     * JSON value -- a single object for {@code multiple:false}, an array for {@code multiple:true}.
     * Malformed/partial entries (missing storeId or key) are skipped rather than failing the
     * caller, since this runs on the delete/update hot path.
     */
    public static List<FileHandleRef> extractFileHandleRefs(JsonNode fieldValue) {
        List<FileHandleRef> refs = new ArrayList<>();
        if (fieldValue == null || fieldValue.isNull() || fieldValue.isMissingNode()) {
            return refs;
        }
        if (fieldValue.isArray()) {
            for (JsonNode item : fieldValue) {
                FileHandleRef ref = toFileHandleRef(item);
                if (ref != null) {
                    refs.add(ref);
                }
            }
        } else {
            FileHandleRef ref = toFileHandleRef(fieldValue);
            if (ref != null) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private static FileHandleRef toFileHandleRef(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        String storeId = node.path("storeId").asText(null);
        String key = node.path("key").asText(null);
        if (storeId == null || storeId.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        return new FileHandleRef(storeId, key);
    }

    /**
     * HARDEN-GC-P1: deletes every referenced file's bytes through {@code fileStore}, tolerating
     * (log + continue) an individual store failure so a store outage never aborts the record
     * mutation that triggered the cascade -- {@code fileStore} being unavailable is itself
     * tolerated the same way, since a delete-cascade is best-effort GC, not a correctness gate.
     */
    public static void deleteFileHandles(
            com.npdev.kernel.ports.FileStoreContract fileStore,
            List<FileHandleRef> refs,
            String conceptName
    ) {
        if (fileStore == null || refs == null || refs.isEmpty()) {
            return;
        }
        for (FileHandleRef ref : refs) {
            try {
                fileStore.delete(new com.npdev.kernel.ports.FileHandle(
                        ref.storeId(), ref.key(), "application/octet-stream", 0, "file"));
            } catch (RuntimeException exception) {
                LOG.log(Level.WARNING, "Failed to delete file bytes for " + conceptName + " key=" + ref.key()
                        + " (tolerated: a future orphan sweep can reclaim stragglers)", exception);
            }
        }
    }

    /**
     * HARDEN-GC-P2: after a successful update, deletes whichever of {@code oldValue}'s file
     * handles are no longer present in {@code newValue} -- covers both a {@code multiple:false}
     * replacement and a {@code multiple:true} field losing some of its entries. Never called
     * before the save succeeds, so a failed save can never orphan the still-referenced old file.
     */
    public static void cascadeReplacedFileField(
            com.npdev.kernel.ports.FileStoreContract fileStore,
            String conceptName,
            JsonNode oldValue,
            JsonNode newValue
    ) {
        List<FileHandleRef> oldRefs = extractFileHandleRefs(oldValue);
        if (oldRefs.isEmpty() || fileStore == null) {
            return;
        }
        Set<String> newKeys = new HashSet<>();
        for (FileHandleRef ref : extractFileHandleRefs(newValue)) {
            newKeys.add(ref.key());
        }
        List<FileHandleRef> orphaned = new ArrayList<>();
        for (FileHandleRef ref : oldRefs) {
            if (!newKeys.contains(ref.key())) {
                orphaned.add(ref);
            }
        }
        deleteFileHandles(fileStore, orphaned, conceptName);
    }

    private static boolean isForeignKeyViolation(Throwable exception) {
        if (exception == null) {
            return false;
        }
        if (exception instanceof SQLException sqlException) {
            String sqlState = sqlException.getSQLState();
            // 23503 is Postgres/H2's dedicated foreign-key code. MySQL (1452/1451) and SQL Server
            // (547) report the generic class 23000, so the state test alone answers for two engines
            // only -- the same asymmetry that made a unique violation a 500 on MySQL (STOR-11). The
            // message test below still carries those two, and `isUniqueViolation` is asked FIRST by
            // the caller, so a duplicate is never misreported as a reference failure.
            if ("23503".equals(sqlState) || sqlException.getErrorCode() == 1452
                    || sqlException.getErrorCode() == 1451 || sqlException.getErrorCode() == 547) {
                return true;
            }
        }
        String message = exception.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("foreign key")
                    || normalized.contains("referential integrity")
                    || normalized.contains("violates referential")
                    || normalized.contains("constraint violation")) {
                return true;
            }
        }
        return isForeignKeyViolation(exception.getCause());
    }

    private List<ScheduledEventRecord> selectDueScheduledEvents(boolean forceDue, int limit) {
        if (dataSource == null) {
            return List.of();
        }
        String sql = ScheduledEventSql.selectDue(forceDue);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameterIndex = 1;
            statement.setString(parameterIndex++, SCHEDULE_STATUS_PENDING);
            if (!forceDue) {
                statement.setTimestamp(parameterIndex++, toTimestamp(runtimeClock.nowUtc()));
            }
            statement.setInt(parameterIndex, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<ScheduledEventRecord> records = new ArrayList<>();
                while (rows.next()) {
                    ScheduledEventRecord record = toScheduledEventRecord(rows);
                    if (record != null) {
                        records.add(record);
                    }
                }
                return records.isEmpty() ? List.of() : List.copyOf(records);
            }
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "Failed to query due scheduled events", exception);
            return List.of();
        }
    }

    private boolean claimScheduledEvent(UUID scheduleId) {
        if (dataSource == null || scheduleId == null) {
            return false;
        }
        String sql = ScheduledEventSql.claim();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            Timestamp now = toTimestamp(runtimeClock.nowUtc());
            statement.setString(1, SCHEDULE_STATUS_PROCESSING);
            statement.setTimestamp(2, now);
            statement.setString(3, scheduleId.toString());
            statement.setString(4, SCHEDULE_STATUS_PENDING);
            return statement.executeUpdate() > 0;
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "Failed to claim scheduled event " + scheduleId, exception);
            return false;
        }
    }

    private void markScheduledEventProcessed(UUID scheduleId) {
        if (dataSource == null || scheduleId == null) {
            return;
        }
        String sql = ScheduledEventSql.markProcessed();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            Timestamp now = toTimestamp(runtimeClock.nowUtc());
            statement.setString(1, SCHEDULE_STATUS_PROCESSED);
            statement.setTimestamp(2, now);
            statement.setTimestamp(3, now);
            statement.setString(4, scheduleId.toString());
            statement.executeUpdate();
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "Failed to mark scheduled event processed " + scheduleId, exception);
        }
    }

    private void markScheduledEventFailed(UUID scheduleId) {
        if (dataSource == null || scheduleId == null) {
            return;
        }
        String sql = ScheduledEventSql.markFailed();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            Timestamp now = toTimestamp(runtimeClock.nowUtc());
            statement.setString(1, SCHEDULE_STATUS_FAILED);
            statement.setTimestamp(2, now);
            statement.setString(3, scheduleId.toString());
            statement.executeUpdate();
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "Failed to mark scheduled event failed " + scheduleId, exception);
        }
    }

    /**
     * R2.4: {@link com.npdev.kernel.ports.DeferredEventScheduler} bound to the SAME table and the
     * SAME insert the orchestration-level {@code scheduleEvent} action has always used, so the R2.3
     * drain consumes a flow step's row without knowing it came from one. No second row format, no
     * second SQL statement -- {@link #insertScheduledEvent} is called unchanged.
     *
     * <p><b>Column mapping.</b> The table was designed around the orchestration action, whose rows
     * are caused by an inbound event; a flow step's cause is an execution, so:
     * {@code orchestration_name} carries the flow name, {@code action_index} the step index,
     * {@code source_event_name} the flow name prefixed {@code flow:} (the column is NOT NULL and
     * there is no source event -- the prefix is what stops the drain's echo of it into
     * {@code payload.sourceEventName} reading like an event that exists), and
     * {@code source_event_id} the flow execution id, which is what actually caused this row.
     *
     * <p><b>Why the key is a name-UUID and not the composite itself.</b> {@code schedule_key} is
     * {@code VARCHAR(191)} with a unique index. The composite that makes a schedule unique --
     * flow, step index, execution id, correlation id, event -- runs to ~160 characters with two
     * uuid-shaped ids in it and blows the bound outright on a long flow or event name, which is a
     * data-too-long insert failure on MySQL and SQL Server rather than anything visible in a test.
     * Hashing it to a type-3 UUID is a fixed 41 characters, and costs nothing in legibility because
     * every component of the composite is also a column of its own on the same row.
     *
     * <p>A duplicate key means this exact (execution, step, correlation, event) was already
     * scheduled -- a re-executed step, not a second reminder -- so it reports success without
     * inserting, mirroring {@code executeScheduleOrchestrationAction}'s {@code schedule_exists}.
     */
    public boolean scheduleDeferredFlowEvent(EventEnvelope envelope, long dueAtEpochMs) {
        if (envelope == null) {
            return false;
        }
        if (dataSource == null) {
            LOG.log(Level.WARNING, "Cannot defer scheduled event " + envelope.eventName()
                    + ": no DataSource is configured, so npdev_scheduled_event cannot be written");
            return false;
        }
        String payloadJson;
        try {
            payloadJson = OBJECT_MAPPER.writeValueAsString(envelope.payload());
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "Failed to serialize deferred event payload " + envelope.eventName(), exception);
            return false;
        }
        String scheduleKey = buildFlowScheduleKey(envelope);
        OffsetDateTime dueAt = Instant.ofEpochMilli(dueAtEpochMs).atOffset(ZoneOffset.UTC);
        try {
            insertScheduledEvent(
                    UUID.randomUUID(),
                    scheduleKey,
                    envelope.flowName(),
                    envelope.stepIndex(),
                    "flow:" + envelope.flowName(),
                    envelope.causationId(),
                    envelope.correlationId(),
                    envelope.eventName(),
                    dueAt,
                    payloadJson
            );
            return true;
        } catch (Exception exception) {
            if (isUniqueViolation(exception)) {
                return true;
            }
            LOG.log(Level.WARNING, "Failed to schedule deferred event " + envelope.eventName(), exception);
            return false;
        }
    }

    private static String buildFlowScheduleKey(EventEnvelope envelope) {
        String composite = nullToEmpty(envelope.flowName())
                + "|" + envelope.stepIndex()
                + "|" + nullToEmpty(envelope.causationId())
                + "|" + nullToEmpty(envelope.correlationId())
                + "|" + nullToEmpty(envelope.eventName());
        return "flow:" + UUID.nameUUIDFromBytes(composite.getBytes(StandardCharsets.UTF_8));
    }

    private void insertScheduledEvent(
            UUID id,
            String scheduleKey,
            String orchestrationName,
            int actionIndex,
            String sourceEventName,
            String sourceEventId,
            String correlationId,
            String eventName,
            OffsetDateTime dueAt,
            String payloadJson
    ) throws SQLException {
        if (dataSource == null) {
            throw new SQLException("scheduler datasource unavailable");
        }
        String sql = ScheduledEventSql.insert();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            Timestamp now = toTimestamp(runtimeClock.nowUtc());
            statement.setString(1, id.toString());
            statement.setString(2, scheduleKey);
            statement.setString(3, orchestrationName);
            statement.setInt(4, actionIndex);
            statement.setString(5, sourceEventName);
            statement.setString(6, sourceEventId);
            statement.setString(7, correlationId);
            statement.setString(8, eventName);
            statement.setTimestamp(9, toTimestamp(dueAt));
            statement.setString(10, payloadJson);
            statement.setString(11, SCHEDULE_STATUS_PENDING);
            statement.setTimestamp(12, now);
            statement.setTimestamp(13, now);
            statement.executeUpdate();
        }
    }

    private ExecutionContext resolveCurrentExecutionContext() {
        try {
            RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
            if (!(requestAttributes instanceof ServletRequestAttributes servletRequestAttributes)) {
                return ExecutionContext.anonymous();
            }
            HttpServletRequest request = servletRequestAttributes.getRequest();
            if (request == null) {
                return ExecutionContext.anonymous();
            }
            Object rawClaims = request.getAttribute(CLAIMS_ATTRIBUTE);
            if (!(rawClaims instanceof Map<?, ?> rawMap)) {
                return ExecutionContext.anonymous();
            }

            Map<String, Object> claims = mapWithStringKeys(rawMap);
            String tenantId = asNonBlankString(claims.get("tenant_id"));
            String actorId = asNonBlankString(claims.get("actor_id"));
            if (tenantId == null && actorId == null) {
                return ExecutionContext.anonymous();
            }

            // LNCH-4: a token minted with a 'tv' claim is only valid while it still matches the
            // identity pack's live token_version for this (tenant, actor) -- same revocation contract
            // IdentityAwareContextResolver enforces on the RuntimeHost admin/business-UI path. Only
            // checked when actorId itself resolved (this path's own claim key, unlike the JWT resolver,
            // has no 'sub' fallback -- see below); otherwise IdentityRoleLookup.tokenVersion's
            // null-actor guard would report version 0 and falsely "revoke" every non-zero-versioned
            // token whose actor didn't resolve here, rather than correctly no-op'ing.
            if (actorId != null && isTokenRevoked(claims.get("tv"), tenantId, actorId)) {
                return ExecutionContext.anonymous();
            }

            // Identity-backed roles (when the identity pack is populated for this tenant+actor) are
            // authoritative over the principal's claim-roles -- same supplement-with-fallback contract
            // the RuntimeHost IdentityAwareContextResolver applies, kept consistent across both
            // context-resolution paths via the shared IdentityRoleLookup.
            Set<String> identityRoles = identityTables.isEmpty()
                    ? Set.of()
                    : IdentityRoleLookup.rolesFor(dataSource, identityTables.get(), tenantId, actorId);
            Set<String> roles = identityRoles.isEmpty() ? parseRoles(claims.get("roles")) : identityRoles;
            ExecutionContext context = ExecutionContext.of(tenantId, actorId);
            return roles.isEmpty() ? context : context.withRoles(roles);
        } catch (Exception ignored) {
            return ExecutionContext.anonymous();
        }
    }

    private boolean isTokenRevoked(Object rawTokenVersion, String tenantId, String actorId) {
        // REG-23: delegate to the single shared decision point (IdentityRoleLookup.isTokenRevoked) so
        // both claim->context paths agree, including the config-driven rejection of legacy tv-less tokens.
        if (identityTables.isEmpty()) {
            return false;
        }
        return IdentityRoleLookup.isTokenRevoked(rawTokenVersion, dataSource, identityTables.get(), tenantId, actorId);
    }

    private Map<String, Object> normalizePayloadForValidation(
            CompiledConcept entity,
            Map<String, Object> payload
    ) {
        if (entity == null || payload == null || payload.isEmpty()) {
            return payload == null ? Map.of() : payload;
        }

        Map<String, Object> normalized = new LinkedHashMap<>(payload);
        Object explicitId = readMapValue(normalized, "id");
        if (isLifecycleMissing(explicitId)) {
            Object idFromShadow = readMapValue(normalized, "__id");
            if (!isLifecycleMissing(idFromShadow)) {
                normalized.put("id", idFromShadow);
            }
        }
        for (CompiledField field : entity.getFields()) {
            if (field == null || field.getName() == null || field.getName().isBlank()) {
                continue;
            }

            Object rawValue = readPayloadValue(normalized, field.getName());
            if (rawValue == null) {
                continue;
            }
            Object normalizedValue = DslTypeCoercionSupport.normalizeFieldValue(field, rawValue);
            normalized.put(field.getName(), normalizedValue);
        }
        return immutablePayload(normalized);
    }

    private Object normalizeFieldValue(CompiledConcept owner, CompiledField field, Object rawValue) {
        String dslType = normalizeType(field == null ? null : field.getDslType());
        if ("reference".equals(dslType)) {
            CompiledField anchor = resolveReferenceAnchor(field).orElse(null);
            if (anchor != null) {
                return normalizeByDslType(anchor.getDslType(), rawValue);
            }
        }
        return DslTypeCoercionSupport.normalizeFieldValue(field, rawValue);
    }

    private Map<String, Object> materializeEntityValues(
            CompiledConcept entity,
            Object explicitSource,
            Object existingSource,
            boolean patchMode
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (entity == null) {
            return values;
        }

        for (CompiledField field : entity.getFields()) {
            if (field == null || field.getName() == null || field.getName().isBlank()) {
                continue;
            }
            Object existingValue = readObjectValue(existingSource, field.getName());
            if (existingValue != null) {
                values.put(field.getName(), normalizeFieldValue(entity, field, existingValue));
            }
        }

        for (CompiledField field : entity.getFields()) {
            if (field == null || field.getName() == null || field.getName().isBlank()) {
                continue;
            }
            Object explicitValue = readObjectValue(explicitSource, field.getName());
            if (explicitValue != null) {
                values.put(field.getName(), normalizeFieldValue(entity, field, explicitValue));
            } else if (!patchMode && readObjectValue(existingSource, field.getName()) == null && field.isId()) {
                Object idValue = readObjectValue(explicitSource, field.getName());
                if (idValue != null) {
                    values.put(field.getName(), normalizeFieldValue(entity, field, idValue));
                }
            }
        }

        applySchemaValueBehaviors(entity, values);
        return values;
    }

    private void applySchemaValueBehaviors(CompiledConcept entity, Map<String, Object> values) {
        if (entity == null || values == null) {
            return;
        }
        int maxPasses = Math.max(1, entity.getFields().size() * 2);
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean changed = false;
            for (CompiledField field : entity.getFields()) {
                if (field == null || field.getSchema() == null || field.getName() == null || field.getName().isBlank()) {
                    continue;
                }
                CompiledSchema schema = field.getSchema();
                String fieldName = field.getName();
                Object currentValue = readMapValue(values, fieldName);

                if (currentValue == null && schema.getDefaultValue() != null) {
                    Object normalizedDefault = normalizeFieldValue(entity, field, cloneSchemaDefaultValue(schema.getDefaultValue()));
                    values.put(fieldName, normalizedDefault);
                    currentValue = normalizedDefault;
                    changed = true;
                }

                if (currentValue == null && schema.getDefaultExpression() != null && !schema.getDefaultExpression().isBlank()) {
                    Object evaluated = evaluateSchemaExpression(schema.getDefaultExpression(), values);
                    if (evaluated != null) {
                        Object normalizedDefault = normalizeFieldValue(entity, field, evaluated);
                        values.put(fieldName, normalizedDefault);
                        currentValue = normalizedDefault;
                        changed = true;
                    }
                }

                if (schema.getDerivedExpression() != null && !schema.getDerivedExpression().isBlank()) {
                    Object evaluated = evaluateSchemaExpression(schema.getDerivedExpression(), values);
                    Object normalizedDerived = normalizeFieldValue(entity, field, evaluated);
                    if (!Objects.equals(currentValue, normalizedDerived)) {
                        if (normalizedDerived == null) {
                            values.remove(fieldName);
                        } else {
                            values.put(fieldName, normalizedDerived);
                        }
                        changed = true;
                    }
                }
            }
            if (!changed) {
                return;
            }
        }
    }

    private List<String> validateFieldTypesAndReferences(CompiledConcept entity, Map<String, Object> payload) {
        if (entity == null || entity.getName() == null || entity.getName().isBlank()) {
            return List.of("Entity metadata is required for runtime field validation");
        }
        List<String> violations = new ArrayList<>();
        for (CompiledField field : entity.getFields()) {
            if (field == null || field.getName() == null || field.getName().isBlank()) {
                continue;
            }
            if (field.isId()) {
                continue;
            }

            Object value = readPayloadValue(payload, field.getName());
            if (value == null) {
                continue;
            }

            String dslType = normalizeType(field.getDslType());
            switch (dslType) {
                case "enum" -> validateEnumField(entity.getName(), field, value, violations);
                case "date" -> validateDateField(entity.getName(), field, value, violations);
                case "datetime" -> validateDateTimeField(entity.getName(), field, value, violations);
                case "reference" -> validateReferenceField(entity.getName(), field, value, violations);
                case "object", "array" -> validateNestedSchemaField(entity.getName(), field, value, violations);
                default -> {
                    // Validation for required/unique/expression invariants is handled by invariant engine.
                }
            }
        }
        return violations;
    }

    private void validateEnumField(
            String entityName,
            CompiledField field,
            Object value,
            List<String> violations
    ) {
        String normalized = value.toString().trim();
        if (normalized.isEmpty()) {
            return;
        }
        Collection<String> allowed = field.getEnumValues();
        boolean match = allowed.stream().anyMatch(candidate -> candidate != null && candidate.equals(normalized));
        if (!match) {
            violations.add("Entity " + entityName + ": enum constraint violated for field '" + field.getName()
                    + "' (allowed: " + allowed + ")");
        }
    }

    private void validateDateField(
            String entityName,
            CompiledField field,
            Object value,
            List<String> violations
    ) {
        try {
            if (value instanceof LocalDate) {
                return;
            }
            if (value instanceof String raw) {
                LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
                return;
            }
            violations.add("Entity " + entityName + ": date field '" + field.getName()
                    + "' must use ISO format YYYY-MM-DD");
        } catch (DateTimeParseException exception) {
            violations.add("Entity " + entityName + ": date field '" + field.getName()
                    + "' must use ISO format YYYY-MM-DD");
        }
    }

    private void validateDateTimeField(
            String entityName,
            CompiledField field,
            Object value,
            List<String> violations
    ) {
        if (value instanceof OffsetDateTime || value instanceof LocalDateTime) {
            return;
        }
        if (!(value instanceof String raw)) {
            violations.add("Entity " + entityName + ": datetime field '" + field.getName()
                    + "' must use ISO datetime format");
            return;
        }

        String candidate = raw.trim();
        if (candidate.isEmpty()) {
            return;
        }
        try {
            DateTimeFormatter.ISO_DATE_TIME.parseBest(
                    candidate,
                    OffsetDateTime::from,
                    LocalDateTime::from
            );
        } catch (DateTimeParseException exception) {
            violations.add("Entity " + entityName + ": datetime field '" + field.getName()
                    + "' must use ISO datetime format");
        }
    }

    private void validateReferenceField(
            String entityName,
            CompiledField field,
            Object value,
            List<String> violations
    ) {
        String targetEntityName = field.getReferenceTarget();
        if (targetEntityName == null || targetEntityName.isBlank()) {
            violations.add("Entity " + entityName + ": reference field '" + field.getName()
                    + "' has no target entity");
            return;
        }

        Optional<CompiledConcept> targetEntityOpt = findEntity(targetEntityName);
        if (targetEntityOpt.isEmpty()) {
            violations.add("Entity " + entityName + ": reference target '" + targetEntityName
                    + "' not found in compiled model");
            return;
        }

        CompiledConcept targetEntity = targetEntityOpt.get();
        CompiledField anchor = resolveReferenceAnchor(field, targetEntity).orElse(null);
        if (anchor == null) {
            violations.add("Entity " + entityName + ": reference field '" + field.getName()
                    + "' has no resolvable target anchor");
            return;
        }

        Object anchorValue = normalizeByDslType(anchor.getDslType(), value);
        if (anchorValue == null) {
            violations.add("Entity " + entityName + ": reference field '" + field.getName()
                    + "' must match anchor " + targetEntityName + "." + anchor.getName());
            return;
        }

        if (!existsByAnchor(targetEntity, anchor, anchorValue)) {
            violations.add("Entity " + entityName + ": reference '" + field.getName()
                    + "' points to non-existent " + targetEntityName + "." + anchor.getName()
                    + " " + anchorValue);
        }
    }

    private void validateNestedSchemaField(
            String entityName,
            CompiledField field,
            Object value,
            List<String> violations
    ) {
        CompiledSchema schema = field.getSchema();
        if (schema == null) {
            violations.add("Entity " + entityName + ": field '" + field.getName()
                    + "' is missing schema metadata for nested validation");
            return;
        }
        validateSchemaValue(entityName, field.getName(), schema, value, violations);
    }

    private void validateSchemaValue(
            String entityName,
            String path,
            CompiledSchema schema,
            Object value,
            List<String> violations
    ) {
        if (schema == null) {
            return;
        }
        String type = normalizeType(schema.getType());
        if (type.isBlank()) {
            return;
        }
        if (value == null) {
            return;
        }

        switch (type) {
            case "object" -> validateObjectSchemaValue(entityName, path, schema, value, violations);
            case "array" -> validateArraySchemaValue(entityName, path, schema, value, violations);
            case "enum" -> validateEnumSchemaValue(entityName, path, schema, value, violations);
            case "date" -> validateDateSchemaValue(entityName, path, value, violations);
            case "datetime" -> validateDateTimeSchemaValue(entityName, path, value, violations);
            case "reference", "uuid" -> {
                if (toUuid(value) == null) {
                    violations.add("Entity " + entityName + ": field '" + path + "' must be a UUID");
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) {
                    violations.add("Entity " + entityName + ": field '" + path + "' must be boolean");
                }
            }
            default -> {
                // Scalar type rules already covered by existing field validators when applicable.
            }
        }
    }

    private void validateObjectSchemaValue(
            String entityName,
            String path,
            CompiledSchema schema,
            Object value,
            List<String> violations
    ) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            violations.add("Entity " + entityName + ": field '" + path + "' must be an object");
            return;
        }
        Map<String, Object> map = mapWithStringKeys(rawMap);

        for (String requiredField : schema.getRequired()) {
            if (requiredField == null || requiredField.isBlank()) {
                continue;
            }
            boolean present = hasMapKey(map, requiredField);
            if (!present) {
                CompiledSchema requiredSchema = schema.getProperties().get(requiredField);
                if (requiredSchema != null
                        && (requiredSchema.getDefaultValue() != null
                        || (requiredSchema.getDefaultExpression() != null && !requiredSchema.getDefaultExpression().isBlank())
                        || (requiredSchema.getDerivedExpression() != null && !requiredSchema.getDerivedExpression().isBlank()))) {
                    present = true;
                }
            }
            if (!present) {
                violations.add("Entity " + entityName + ": required nested field '" + path + "."
                        + requiredField + "' is missing");
            }
        }

        for (Map.Entry<String, CompiledSchema> property : schema.getProperties().entrySet()) {
            String propertyName = property.getKey();
            Object propertyValue = readMapValue(map, propertyName);
            if (propertyValue == null && property.getValue() != null) {
                if (property.getValue().getDefaultValue() != null) {
                    propertyValue = property.getValue().getDefaultValue();
                } else if (property.getValue().getDefaultExpression() != null
                        && !property.getValue().getDefaultExpression().isBlank()) {
                    propertyValue = evaluateSchemaExpression(property.getValue().getDefaultExpression(), map);
                } else if (property.getValue().getDerivedExpression() != null
                        && !property.getValue().getDerivedExpression().isBlank()) {
                    propertyValue = evaluateSchemaExpression(property.getValue().getDerivedExpression(), map);
                }
            }
            if (propertyValue == null) {
                continue;
            }
            validateSchemaValue(
                    entityName,
                    path + "." + propertyName,
                    property.getValue(),
                    propertyValue,
                    violations
            );
        }
    }

    private void validateArraySchemaValue(
            String entityName,
            String path,
            CompiledSchema schema,
            Object value,
            List<String> violations
    ) {
        if (!(value instanceof Collection<?> values)) {
            violations.add("Entity " + entityName + ": field '" + path + "' must be an array");
            return;
        }
        if (schema.getItems() == null) {
            return;
        }
        int index = 0;
        for (Object item : values) {
            validateSchemaValue(entityName, path + "[" + index + "]", schema.getItems(), item, violations);
            index++;
        }
    }

    private void validateEnumSchemaValue(
            String entityName,
            String path,
            CompiledSchema schema,
            Object value,
            List<String> violations
    ) {
        if (schema.getEnumValues() == null || schema.getEnumValues().isEmpty()) {
            return;
        }
        String normalized = value.toString().trim();
        if (normalized.isEmpty()) {
            return;
        }
        boolean match = schema.getEnumValues().stream()
                .anyMatch(candidate -> candidate != null && candidate.equals(normalized));
        if (!match) {
            violations.add("Entity " + entityName + ": enum constraint violated for field '" + path
                    + "' (allowed: " + schema.getEnumValues() + ")");
        }
    }

    private void validateDateSchemaValue(
            String entityName,
            String path,
            Object value,
            List<String> violations
    ) {
        try {
            if (value instanceof LocalDate) {
                return;
            }
            if (value instanceof String raw) {
                LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
                return;
            }
            violations.add("Entity " + entityName + ": date field '" + path
                    + "' must use ISO format YYYY-MM-DD");
        } catch (DateTimeParseException exception) {
            violations.add("Entity " + entityName + ": date field '" + path
                    + "' must use ISO format YYYY-MM-DD");
        }
    }

    private void validateDateTimeSchemaValue(
            String entityName,
            String path,
            Object value,
            List<String> violations
    ) {
        if (value instanceof OffsetDateTime || value instanceof LocalDateTime) {
            return;
        }
        if (!(value instanceof String raw)) {
            violations.add("Entity " + entityName + ": datetime field '" + path
                    + "' must use ISO datetime format");
            return;
        }

        String candidate = raw.trim();
        if (candidate.isEmpty()) {
            return;
        }
        try {
            DateTimeFormatter.ISO_DATE_TIME.parseBest(
                    candidate,
                    OffsetDateTime::from,
                    LocalDateTime::from
            );
        } catch (DateTimeParseException exception) {
            violations.add("Entity " + entityName + ": datetime field '" + path
                    + "' must use ISO datetime format");
        }
    }

    private InvariantViolationDetail toTypedViolation(String concept, String message) {
        String path = extractPathFromMessage(message);
        String invariant = inferInvariantFromMessage(path, message);
        return new InvariantViolationDetail(
                "invariant_failed",
                concept,
                invariant,
                path,
                message,
                false
        );
    }

    private InvariantViolationDetail toInvariantViolation(
            String concept,
            InvariantEngine.Violation violation,
            Set<String> uniqueInvariantRefs
    ) {
        String invariant = violation == null || violation.invariantRef() == null || violation.invariantRef().isBlank()
                ? "unknown_invariant"
                : violation.invariantRef();
        String message = violation == null || violation.message() == null || violation.message().isBlank()
                ? "Invariant validation failed"
                : violation.message();
        String path = extractFieldPath(violation);
        boolean unique = uniqueInvariantRefs.contains(normalize(invariant))
                || message.toLowerCase(Locale.ROOT).contains("unique constraint violated");

        return new InvariantViolationDetail(
                "invariant_failed",
                concept,
                invariant,
                path,
                message,
                unique
        );
    }

    public static Map<String, Object> mapWithStringKeys(Map<?, ?> rawMap) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }

    public static Object readMapValue(Map<String, Object> map, String key) {
        if (map == null || map.isEmpty() || key == null) {
            return null;
        }
        if (map.containsKey(key)) {
            return map.get(key);
        }
        String normalized = normalize(key);
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (normalize(entry.getKey()).equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Optional<CompiledConcept> findEntity(String name) {
        Optional<CompiledConcept> exact = compiledModel.findConcept(name);
        if (exact.isPresent()) {
            return exact;
        }
        String normalized = normalize(name);
        for (CompiledConcept entity : compiledModel.getConcepts()) {
            if (normalize(entity.getName()).equals(normalized)) {
                return Optional.of(entity);
            }
        }
        return Optional.empty();
    }

    private CompiledConcept requireEntity(String entityName) {
        return findEntity(entityName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown entity for runtime support: " + entityName));
    }

    private String idFieldName(String entityName) {
        CompiledConcept entity = requireEntity(entityName);
        String found = null;
        for (CompiledField field : entity.getFields()) {
            if (field == null || !field.isId()) {
                continue;
            }
            if (found != null) {
                throw new IllegalArgumentException("Entity " + entityName + " must have exactly one id field");
            }
            found = field.getName();
        }
        if (found == null || found.isBlank()) {
            throw new IllegalArgumentException("Entity " + entityName + " must have exactly one id field");
        }
        return found;
    }

    private Optional<CompiledField> resolveReferenceAnchor(CompiledField referenceField) {
        if (referenceField == null) {
            return Optional.empty();
        }
        String targetEntityName = referenceTargetName(referenceField);
        if (targetEntityName == null || targetEntityName.isBlank()) {
            return Optional.empty();
        }
        Optional<CompiledConcept> target = findEntity(targetEntityName);
        return target.flatMap(entity -> resolveReferenceAnchor(referenceField, entity));
    }

    private Optional<CompiledField> resolveReferenceAnchor(CompiledField referenceField, CompiledConcept targetEntity) {
        if (referenceField == null || targetEntity == null) {
            return Optional.empty();
        }
        CompiledReferenceSemantics semantics = referenceField.getReferenceSemantics();
        String via = semantics == null ? null : semantics.getVia();
        if (via == null || via.isBlank()) {
            return idField(targetEntity);
        }
        for (CompiledField candidate : targetEntity.getFields()) {
            if (candidate != null && via.equalsIgnoreCase(candidate.getName())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Optional<CompiledField> idField(CompiledConcept entity) {
        if (entity == null) {
            return Optional.empty();
        }
        CompiledField found = null;
        for (CompiledField field : entity.getFields()) {
            if (field == null || !field.isId()) {
                continue;
            }
            if (found != null) {
                return Optional.empty();
            }
            found = field;
        }
        return Optional.ofNullable(found);
    }

    private BondRuntimeShape requireBondRuntimeShape(String sourceConceptName, String fieldName) {
        CompiledConcept sourceEntity = requireEntity(sourceConceptName);
        CompiledField sourceField = null;
        for (CompiledField field : sourceEntity.getFields()) {
            if (field != null && fieldName != null && fieldName.equalsIgnoreCase(field.getName())) {
                sourceField = field;
                break;
            }
        }
        if (sourceField == null) {
            throw new IllegalArgumentException("Unknown bond field " + sourceConceptName + "." + fieldName);
        }
        CompiledReferenceSemantics semantics = sourceField.getReferenceSemantics();
        if (semantics == null || !semantics.isMultiple()) {
            throw new IllegalArgumentException("Bond field is not a multiple reference: "
                    + sourceConceptName + "." + fieldName);
        }
        CompiledConcept targetEntity = findEntity(referenceTargetName(sourceField))
                .orElseThrow(() -> new IllegalArgumentException("Unknown bond target for "
                        + sourceConceptName + "." + fieldName));
        CompiledField sourceId = idField(sourceEntity)
                .orElseThrow(() -> new IllegalArgumentException("Source concept has no id field: " + sourceConceptName));
        CompiledField targetAnchor = resolveReferenceAnchor(sourceField, targetEntity)
                .orElseThrow(() -> new IllegalArgumentException("Bond target anchor is not resolvable for "
                        + sourceConceptName + "." + fieldName));

        // Junction table + column naming is shared with the generator through Contract naming
        // support so runtime membership operations query the same DDL that Flyway emits.
        String junctionTable = SqlIdentifierSupport.junctionTableName(sourceEntity, sourceField);
        return new BondRuntimeShape(
                sourceEntity,
                sourceField,
                targetEntity,
                sourceId,
                targetAnchor,
                junctionTable,
                SqlIdentifierSupport.sourceJunctionColumn(sourceId),
                SqlIdentifierSupport.targetJunctionColumn(targetAnchor)
        );
    }

    private void requireDataSourceForBond(BondRuntimeShape shape) {
        if (dataSource == null) {
            throw new IllegalStateException("JDBC DataSource is required for bond set operations: "
                    + shape.sourceEntity().getName() + "." + shape.sourceField().getName());
        }
    }

    private void applyEntityFields(String entityName, Object source, Object target, boolean patchMode) {
        if (target == null) {
            return;
        }
        CompiledConcept entity = requireEntity(entityName);
        Map<String, Object> materialized = materializeEntityValues(entity, source, patchMode ? target : null, patchMode);
        for (CompiledField field : entity.getFields()) {
            if (field == null || field.isId()) {
                continue;
            }
            if (!materialized.containsKey(field.getName())) {
                continue;
            }
            writeObjectValue(target, field.getName(), materialized.get(field.getName()));
        }
    }

    private boolean existsById(CompiledConcept entity, UUID id) {
        if (entityManager == null || entity == null || id == null) {
            return false;
        }
        String table = tableName(entity);
        if (table.isBlank()) {
            return false;
        }
        Optional<CompiledField> idField = idField(entity);
        if (idField.isEmpty()) {
            return false;
        }

        try {
            Query query = entityManager.createNativeQuery(existsByIdSql(entity, idField.get()));
            query.setParameter("id", id.toString());
            query.setMaxResults(1);
            List<?> rows = query.getResultList();
            return rows != null && !rows.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean existsByAnchor(CompiledConcept entity, CompiledField anchor, Object value) {
        if (entity == null || anchor == null || value == null) {
            return false;
        }
        if (entityManager == null) {
            return existsByAnchorViaConceptGateway(entity, anchor, value);
        }
        String table = tableName(entity);
        String column = columnName(anchor);
        if (table.isBlank() || column.isBlank()) {
            return false;
        }

        try {
            Query query = entityManager.createNativeQuery(
                    "SELECT 1 FROM " + table + " WHERE CAST(" + column + " AS VARCHAR) = :value"
            );
            query.setParameter("value", String.valueOf(value));
            query.setMaxResults(1);
            List<?> rows = query.getResultList();
            return rows != null && !rows.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    // Reference-existence fallback for npdev.storage.mode=in-memory, where there is no
    // EntityManager and the referenced concept lives only in the ConceptGateway/ConceptStore.
    private boolean existsByAnchorViaConceptGateway(CompiledConcept entity, CompiledField anchor, Object value) {
        if (conceptGateway == null) {
            return false;
        }
        try {
            ExecutionContext context = resolveCurrentCrudContext();
            String anchorName = anchor.getName();
            if ("id".equalsIgnoreCase(anchorName)) {
                return conceptGateway.read(
                        new ConceptReadRequest(entity.getName(), String.valueOf(value), context.tenantId()),
                        context
                ).isPresent();
            }
            return conceptGateway.list(new ConceptListRequest(entity.getName(), context.tenantId()), context)
                    .stream()
                    .anyMatch(record -> referenceValuesEqual(record.data().get(anchorName), value));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean resourceHasConflict(
            String entityName,
            String resourceFieldPath,
            Object resourceIdValue,
            String startsAtFieldPath,
            Object scheduledAtValue,
            String durationMinutesFieldPath,
            Object durationMinutesValue,
            Object excludeIdValue,
            Object payload
    ) {
        if (entityName == null
                || entityName.isBlank()
                || resourceFieldPath == null
                || resourceFieldPath.isBlank()
                || startsAtFieldPath == null
                || startsAtFieldPath.isBlank()
                || durationMinutesFieldPath == null
                || durationMinutesFieldPath.isBlank()) {
            return false;
        }
        if (entityManager == null) {
            return resourceHasConflictViaConceptGateway(
                    entityName,
                    resourceFieldPath,
                    resourceIdValue,
                    startsAtFieldPath,
                    scheduledAtValue,
                    durationMinutesFieldPath,
                    durationMinutesValue,
                    excludeIdValue,
                    payload
            );
        }

        Optional<CompiledConcept> entityOpt = findEntity(entityName);
        if (entityOpt.isEmpty()) {
            return false;
        }
        CompiledConcept entity = entityOpt.get();
        String table = tableName(entity);
        if (table.isBlank()) {
            return false;
        }

        String resourceColumn = columnName(entity, resourceFieldPath);
        String startsAtColumn = columnName(entity, startsAtFieldPath);
        String durationColumn = columnName(entity, durationMinutesFieldPath);
        Optional<CompiledField> idField = idField(entity);
        if (resourceColumn.isBlank() || startsAtColumn.isBlank() || durationColumn.isBlank() || idField.isEmpty()) {
            return false;
        }
        String idColumn = columnName(idField.get());

        Object effectiveExcludeId = resolveConflictExcludeId(excludeIdValue, payload);
        UUID resourceId = toUuid(resourceIdValue);
        OffsetDateTime start = toOffsetDateTime(scheduledAtValue);
        Integer durationMinutes = toInteger(durationMinutesValue);
        UUID excludeId = toUuid(effectiveExcludeId);
        if (resourceId == null || start == null || durationMinutes == null || durationMinutes <= 0) {
            return false;
        }

        try {
            Query query = entityManager.createNativeQuery(
                    "SELECT " + idColumn + ", " + startsAtColumn + ", " + durationColumn + " "
                            + "FROM " + table + " "
                            + "WHERE CAST(" + resourceColumn + " AS VARCHAR) = :resourceId "
                            + "  AND (:excludeId = '' OR CAST(" + idColumn + " AS VARCHAR) <> :excludeId)"
            );
            query.setParameter("resourceId", resourceId.toString());
            query.setParameter("excludeId", excludeId == null ? "" : excludeId.toString());
            List<?> rows = query.getResultList();
            OffsetDateTime newEnd = start.plusMinutes(durationMinutes);
            if (rows == null || rows.isEmpty()) {
                return false;
            }
            for (Object row : rows) {
                if (!(row instanceof Object[] values) || values.length < 3) {
                    continue;
                }
                OffsetDateTime existingStart = toOffsetDateTime(values[1]);
                Integer existingDuration = toInteger(values[2]);
                if (existingStart == null || existingDuration == null || existingDuration <= 0) {
                    continue;
                }
                OffsetDateTime existingEnd = existingStart.plusMinutes(existingDuration);
                if (start.isBefore(existingEnd) && existingStart.isBefore(newEnd)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    // Conflict-detection fallback for npdev.storage.mode=in-memory, where there is no
    // EntityManager and the resource's existing bookings live only in the ConceptGateway/ConceptStore.
    private boolean resourceHasConflictViaConceptGateway(
            String entityName,
            String resourceFieldPath,
            Object resourceIdValue,
            String startsAtFieldPath,
            Object scheduledAtValue,
            String durationMinutesFieldPath,
            Object durationMinutesValue,
            Object excludeIdValue,
            Object payload
    ) {
        if (conceptGateway == null) {
            return false;
        }
        Object effectiveExcludeId = resolveConflictExcludeId(excludeIdValue, payload);
        UUID resourceId = toUuid(resourceIdValue);
        OffsetDateTime start = toOffsetDateTime(scheduledAtValue);
        Integer durationMinutes = toInteger(durationMinutesValue);
        UUID excludeId = toUuid(effectiveExcludeId);
        if (resourceId == null || start == null || durationMinutes == null || durationMinutes <= 0) {
            return false;
        }
        OffsetDateTime newEnd = start.plusMinutes(durationMinutes);

        try {
            ExecutionContext context = resolveCurrentCrudContext();
            return conceptGateway.list(new ConceptListRequest(entityName, context.tenantId()), context)
                    .stream()
                    .anyMatch(record -> {
                        Map<String, Object> data = record.data();
                        if (!referenceValuesEqual(data.get(resourceFieldPath), resourceId.toString())) {
                            return false;
                        }
                        UUID rowId = toUuid(data.get("id"));
                        if (excludeId != null && excludeId.equals(rowId)) {
                            return false;
                        }
                        OffsetDateTime existingStart = toOffsetDateTime(data.get(startsAtFieldPath));
                        Integer existingDuration = toInteger(data.get(durationMinutesFieldPath));
                        if (existingStart == null || existingDuration == null || existingDuration <= 0) {
                            return false;
                        }
                        OffsetDateTime existingEnd = existingStart.plusMinutes(existingDuration);
                        return start.isBefore(existingEnd) && existingStart.isBefore(newEnd);
                    });
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean scopeExists(String conceptName, String fieldPath, Object expectedValue) {
        if (entityManager == null
                || conceptName == null
                || conceptName.isBlank()
                || fieldPath == null
                || fieldPath.isBlank()
                || expectedValue == null) {
            return false;
        }

        String trimmedFieldPath = fieldPath.trim();
        if (trimmedFieldPath.contains(".") || trimmedFieldPath.contains("[") || trimmedFieldPath.contains("]")) {
            return false;
        }

        Optional<CompiledConcept> entityOpt = findEntity(conceptName);
        if (entityOpt.isEmpty()) {
            return false;
        }

        CompiledConcept entity = entityOpt.get();
        String table = tableName(entity);
        if (table.isBlank()) {
            return false;
        }

        String column = columnName(entity, trimmedFieldPath);
        if (column.isBlank()) {
            return false;
        }

        String expectedText = String.valueOf(expectedValue).trim();
        if (expectedText.isEmpty()) {
            return false;
        }

        try {
            Query query = entityManager.createNativeQuery(
                    "SELECT 1 FROM " + table + " WHERE CAST(" + column + " AS VARCHAR) = :expectedValue"
            );
            query.setParameter("expectedValue", expectedText);
            query.setMaxResults(1);
            List<?> rows = query.getResultList();
            return rows != null && !rows.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private Object resolveConflictExcludeId(Object excludeIdValue, Object payload) {
        UUID explicit = toUuid(excludeIdValue);
        if (explicit != null) {
            return explicit;
        }
        if (!(payload instanceof Map<?, ?> rawMap)) {
            return excludeIdValue;
        }
        Map<String, Object> map = mapWithStringKeys(rawMap);
        Object fromId = readMapValue(map, "id");
        UUID id = toUuid(fromId);
        if (id != null) {
            return id;
        }
        Object fromShadowId = readMapValue(map, "__id");
        UUID shadowId = toUuid(fromShadowId);
        if (shadowId != null) {
            return shadowId;
        }
        return excludeIdValue;
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String toLowerCamel(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() == 1) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed.substring(0, 1).toLowerCase(Locale.ROOT) + trimmed.substring(1);
    }

    private void validateLifecycleTransitions(
            String entityName,
            CompiledConcept entity,
            Map<String, Object> payload,
            List<InvariantViolationDetail> violations
    ) {
        if (entity == null || payload == null || payload.isEmpty() || violations == null) {
            return;
        }
        CompiledLifecycle lifecycle = entity.getLifecycle();
        if (lifecycle == null || lifecycle.getTransitions().isEmpty()) {
            return;
        }

        UUID currentId = extractCurrentId(payload);
        if (currentId == null) {
            return;
        }

        String statusField = lifecycle.getStatusField() == null || lifecycle.getStatusField().isBlank()
                ? "status"
                : lifecycle.getStatusField().trim();
        String nextStatus = readLifecycleToken(readPayloadValue(payload, statusField));
        if (nextStatus == null) {
            return;
        }

        String previousStatus = fetchCurrentStatus(entity, currentId, statusField);
        if (previousStatus == null || normalize(previousStatus).equals(normalize(nextStatus))) {
            return;
        }

        CompiledStateTransition matchingTransition = findLifecycleTransition(lifecycle, previousStatus, nextStatus);
        if (matchingTransition == null) {
            violations.add(new InvariantViolationDetail(
                    "invariant_failed",
                    entityName,
                    "status_transition_not_allowed",
                    statusField,
                    "Status transition from '" + previousStatus + "' to '" + nextStatus + "' is not allowed",
                    false
            ));
            return;
        }

        for (String requiredField : matchingTransition.getRequiredPayload()) {
            if (requiredField == null || requiredField.isBlank()) {
                continue;
            }
            Object value = readPayloadValue(payload, requiredField);
            if (isLifecycleMissing(value)) {
                violations.add(new InvariantViolationDetail(
                        "invariant_failed",
                        entityName,
                        "status_transition_requires_field",
                        requiredField,
                        "Status transition to '" + matchingTransition.getTo()
                                + "' requires field '" + requiredField + "'",
                        false
                ));
            }
        }

        if (matchingTransition.getGuard() != null
                && !matchingTransition.getGuard().isBlank()
                && !evaluateStateMachineGuard(matchingTransition.getGuard(), payload, previousStatus, nextStatus)) {
            violations.add(new InvariantViolationDetail(
                    "invariant_failed",
                    entityName,
                    "status_transition_guard_failed",
                    statusField,
                    "Status transition from '" + previousStatus + "' to '" + nextStatus
                            + "' failed guard '" + matchingTransition.getGuard() + "'",
                    false
            ));
        }
    }

    private boolean evaluateStateMachineGuard(
            String rawCondition,
            Map<String, Object> payload,
            String previousStatus,
            String nextStatus
    ) {
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

        int equalsIndex = condition.indexOf("==");
        if (equalsIndex >= 0) {
            Object left = resolveStateMachineGuardValue(condition.substring(0, equalsIndex), payload, previousStatus, nextStatus);
            Object right = resolveStateMachineGuardValue(condition.substring(equalsIndex + 2), payload, previousStatus, nextStatus);
            return valuesEqual(left, right);
        }

        int notEqualsIndex = condition.indexOf("!=");
        if (notEqualsIndex >= 0) {
            Object left = resolveStateMachineGuardValue(condition.substring(0, notEqualsIndex), payload, previousStatus, nextStatus);
            Object right = resolveStateMachineGuardValue(condition.substring(notEqualsIndex + 2), payload, previousStatus, nextStatus);
            return !valuesEqual(left, right);
        }

        return asBoolean(resolveStateMachineGuardValue(condition, payload, previousStatus, nextStatus));
    }

    private CompiledStateTransition findLifecycleTransition(
            CompiledLifecycle lifecycle,
            String previousStatus,
            String nextStatus
    ) {
        if (lifecycle == null || lifecycle.getTransitions() == null) {
            return null;
        }
        String previous = normalize(previousStatus);
        String next = normalize(nextStatus);
        for (CompiledStateTransition transition : lifecycle.getTransitions()) {
            if (transition == null) {
                continue;
            }
            if (normalize(transition.getFrom()).equals(previous)
                    && normalize(transition.getTo()).equals(next)) {
                return transition;
            }
        }
        return null;
    }

    private String fetchCurrentStatus(CompiledConcept entity, UUID id, String statusField) {
        if (entityManager == null || entity == null || id == null) {
            return null;
        }
        String table = tableName(entity);
        if (table == null || table.isBlank()) {
            return null;
        }
        String statusColumn = columnName(entity, statusField);
        Optional<CompiledField> idField = idField(entity);
        if (statusColumn.isBlank() || idField.isEmpty()) {
            return null;
        }
        try {
            Query query = entityManager.createNativeQuery(fetchCurrentStatusSql(entity, idField.get(), statusColumn));
            query.setParameter("id", id.toString());
            query.setMaxResults(1);
            List<?> rows = query.getResultList();
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return readLifecycleToken(rows.get(0));
        } catch (Exception ignored) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // CRUD kernel-port integration: context, permission, audit, idempotency
    // -------------------------------------------------------------------------

    public ExecutionContext resolveCurrentCrudContext() {
        return resolveCurrentExecutionContext();
    }

    public String extractCrudIdempotencyKey() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
                return null;
            }
            String key = servletAttributes.getRequest().getHeader("X-Idempotency-Key");
            return (key == null || key.isBlank()) ? null : key.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    public void checkCrudPermission(String conceptName, String operation, ExecutionContext ctx) {
        ExecutionContext safeCtx = ctx == null ? ExecutionContext.anonymous() : ctx;
        PermissionSubject subject = new PermissionSubject(
                safeCtx.actorId(), safeCtx.tenantId(),
                new ArrayList<>(safeCtx.roles()), List.of()
        );
        PermissionRequirement requirement = new PermissionRequirement(
                operation.toLowerCase(Locale.ROOT) + ":" + conceptName.toLowerCase(Locale.ROOT),
                conceptName, conceptName
        );
        PermissionDecision decision = permissionEvaluator.evaluate(subject, requirement);
        if (decision != null && !decision.allowed()) {
            appendCrudAudit(conceptName, operation, null, "DENY", safeCtx);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "forbidden: " + conceptName + "." + operation);
        }
    }

    public void auditCrudMutation(String conceptName, String operation, String resourceId,
                                  String outcome, ExecutionContext ctx) {
        appendCrudAudit(conceptName, operation, resourceId, outcome, ctx);
    }

    public Optional<String> checkCrudIdempotency(String tenantId, String conceptName,
                                                  String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || conceptName == null) {
            return Optional.empty();
        }
        String safeTenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        try {
            Optional<IdempotencyRecord> record = idempotencyStore.find(
                    safeTenant, "crud." + conceptName, "create", idempotencyKey
            );
            if (record.isPresent() && record.get().success()) {
                String stored = record.get().resultJsonRedacted();
                return Optional.of(stored == null ? "" : stored);
            }
            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public void recordCrudIdempotencySuccess(String tenantId, String conceptName,
                                              String resourceId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || conceptName == null) {
            return;
        }
        String safeTenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        try {
            idempotencyStore.saveSuccess(
                    safeTenant, "crud." + conceptName, "create", idempotencyKey,
                    resourceId == null ? "" : resourceId,
                    System.currentTimeMillis()
            );
        } catch (Exception ignored) {
            // Idempotency recording must never break primary execution.
        }
    }

    private void appendCrudAudit(String conceptName, String operation, String resourceId,
                                  String outcome, ExecutionContext ctx) {
        try {
            ExecutionContext safeCtx = ctx == null ? ExecutionContext.anonymous() : ctx;
            String safeResourceType = (conceptName == null || conceptName.isBlank())
                    ? "UNKNOWN" : conceptName.toUpperCase(Locale.ROOT);
            String safeOperation = (operation == null || operation.isBlank()) ? "UNKNOWN" : operation;
            auditLogStore.append(AuditRecord.create(
                    safeCtx.tenantId(),
                    safeCtx.actorId(),
                    safeCtx.roles(),
                    "CRUD_" + safeOperation.toUpperCase(Locale.ROOT),
                    safeResourceType,
                    resourceId == null ? "<none>" : resourceId,
                    outcome == null ? "UNKNOWN" : outcome,
                    "crud_" + safeOperation.toLowerCase(Locale.ROOT),
                    Map.of("conceptName", safeResourceType),
                    Map.of("operation", safeOperation)
            ));
        } catch (Exception ignored) {
            // Audit must never break primary execution.
        }
    }
}
