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
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.FlowStepDefinition;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.UserScopedExpressions;
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
import com.npdev.runtime.support.crud.orchestration.EventCreateOrchestration;
import com.npdev.runtime.support.crud.orchestration.OrchestrationActionExecutionResult;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
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

import static com.npdev.runtime.support.CrudAuditDiffSupport.describeFieldChangesJson;
import static com.npdev.runtime.support.CrudAuditDiffSupport.describeFieldDiff;
import static com.npdev.runtime.support.crud.bonds.BondNamingSupport.normalizeTenantForBondCheck;
import static com.npdev.runtime.support.crud.bonds.BondNamingSupport.referenceTargetName;
import static com.npdev.runtime.support.crud.executioncontext.PayloadClaimsSupport.asNonBlankString;
import static com.npdev.runtime.support.crud.executioncontext.PayloadClaimsSupport.extractCurrentId;
import static com.npdev.runtime.support.crud.executioncontext.PayloadClaimsSupport.immutablePayload;
import static com.npdev.runtime.support.crud.executioncontext.PayloadClaimsSupport.parseRoles;
import static com.npdev.runtime.support.crud.executioncontext.PayloadClaimsSupport.toPayloadMap;
import com.npdev.runtime.support.crud.reflection.ExtraFieldAccess;
import com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.extractVersion;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.isLifecycleMissing;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.readLifecycleToken;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.readLifecycleValue;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.readObjectValue;
import static com.npdev.runtime.support.crud.schemaexpr.SchemaExpressionSupport.cloneSchemaDefaultValue;
import static com.npdev.runtime.support.crud.schemaexpr.SchemaExpressionSupport.evaluateSchemaExpression;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.buildScheduleEvidencePayload;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.nullToEmpty;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.sanitizeScheduleLimit;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.sanitizeScheduleOffset;
import static com.npdev.runtime.support.crud.scheduling.ScheduledEventSupport.toScheduledEventRecord;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.columnName;
import static com.npdev.runtime.support.crud.sqlnaming.SqlNamingSupport.tableName;
import static com.npdev.runtime.support.crud.uniqueness.InvariantMessageSupport.collectUniqueInvariantRefs;
import static com.npdev.runtime.support.crud.valuecoercion.DslTypeCoercionSupport.normalizeByDslType;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.normalizeType;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.readPayloadValue;
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

    // D1 (Phase 1 of the server-driven-UI structural rewrite, REG-208 convention): holds a
    // Supplier<CompiledModel> and reads modelSupplier.get() fresh on every use, rather than
    // caching a CompiledModel snapshot at construction -- this class used to be the one straggler
    // that violated ModelHolder's own documented convention (its class Javadoc, in
    // NPDevRuntimeHost/runtimehost-core/.../config/ModelHolder.java -- not importable HERE, since
    // this is a :kernel adapter and RuntimeHost depends on adapters, never the reverse; a plain
    // JDK Supplier is the dependency-direction-safe substitute, with the RuntimeHost wiring
    // passing modelHolder::get), which is exactly why a model-reload's new field was invisible to
    // the generated CRUD REST surface (B28.yml).
    private final Supplier<CompiledModel> modelSupplier;
    private final KernelRunner kernelRunner;
    private final EntityManager entityManager;
    private final DataSource dataSource;
    private final RuntimeClock runtimeClock;
    private final RuntimeInvariantEngineFactory runtimeInvariantEngineFactory;
    private final AuditLogStore auditLogStore;
    private final PermissionEvaluator permissionEvaluator;
    private final IdempotencyStore idempotencyStore;
    private final CrudLifecycleSupport lifecycleSupport;
    private final CrudOrchestrationSupport orchestrationSupport;
    private final CrudSchemaValidationSupport schemaValidationSupport;
    private final CrudReferenceLookupSupport referenceLookupSupport;
    // REG-241: handles from every currently-registered orchestration subscription, so a reload can
    // close them before re-registering against the new model. Access is synchronized on itself --
    // the constructor's initial registration and a later ModelReloadListener-driven reload are the
    // only two callers, and only one reload can be in flight at a time (ModelHolder.swap serializes
    // reloads under its own write lock), but nothing stops a reload racing this class's OWN
    // constructor-adjacent state in a test double, so the lock is taken explicitly rather than relied
    // on implicitly.
    private ConceptGateway conceptGateway;

    // Fallback existence check for cross-concept reference validation when there is no
    // EntityManager (e.g. npdev.storage.mode=in-memory). Set via withConceptGateway after
    // construction rather than threaded through the constructor overloads below, since this
    // is an optional capability rather than a required dependency.
    // The gateway's own sequence-aware defaultExpression evaluator (nextNumber('seq') allocation),
    // so a row created through generated CRUD gets the same default a gateway write would.
    private com.npdev.kernel.concepts.ConceptGatewaySemanticPolicy fieldDefaults;

    public GeneratedCrudRuntimeSupport withFieldDefaults(com.npdev.kernel.concepts.ConceptGatewaySemanticPolicy fieldDefaults) {
        this.fieldDefaults = fieldDefaults;
        return this;
    }

    // RUN-2: an orchestration rule that calls a capability used to pass tenantId only when the
    // triggering event carried a real one, and to call the bare dispatcher. A handler resolving
    // tenant-scoped settings (connector secrets, AI spend caps, file store) then got no tenant, or the
    // reserved "default" sentinel an anonymous publish stamps. Optional collaborators, set after
    // construction (like withFieldDefaults) so every existing call site keeps compiling.

    /** The app's home tenant (npdev.tenant.default-id), used when an event carries no real tenant. */
    public GeneratedCrudRuntimeSupport withOrchestrationFallbackTenant(String tenantId) {
        orchestrationSupport.setOrchestrationFallbackTenant(tenantId);
        return this;
    }

    /** The dispatcher orchestration capability actions invoke -- e.g. the policy-enforcing wrapper (RUN-1). */
    public GeneratedCrudRuntimeSupport withOrchestrationCapabilityDispatcher(CapabilityDispatcher dispatcher) {
        orchestrationSupport.setOrchestrationCapabilityDispatcher(dispatcher);
        return this;
    }

    public GeneratedCrudRuntimeSupport withConceptGateway(ConceptGateway conceptGateway) {
        this.conceptGateway = conceptGateway;
        referenceLookupSupport.setConceptGateway(conceptGateway);
        return this;
    }

    public GeneratedCrudRuntimeSupport(Supplier<CompiledModel> modelSupplier, KernelRunner kernelRunner) {
        this(modelSupplier, kernelRunner, null, null, null, null);
    }

    public GeneratedCrudRuntimeSupport(
            Supplier<CompiledModel> modelSupplier,
            KernelRunner kernelRunner,
            EntityManager entityManager
    ) {
        this(modelSupplier, kernelRunner, entityManager, null, null, null);
    }

    public GeneratedCrudRuntimeSupport(
            Supplier<CompiledModel> modelSupplier,
            KernelRunner kernelRunner,
            EntityManager entityManager,
            CapabilityDispatcher capabilityDispatcher,
            CapabilityRegistry capabilityRegistry
    ) {
        this(modelSupplier, kernelRunner, entityManager, capabilityDispatcher, capabilityRegistry, null);
    }

    public GeneratedCrudRuntimeSupport(
            Supplier<CompiledModel> modelSupplier,
            KernelRunner kernelRunner,
            EntityManager entityManager,
            CapabilityDispatcher capabilityDispatcher,
            CapabilityRegistry capabilityRegistry,
            DataSource dataSource
    ) {
        this(
                modelSupplier,
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
            Supplier<CompiledModel> modelSupplier,
            KernelRunner kernelRunner,
            EntityManager entityManager,
            CapabilityDispatcher capabilityDispatcher,
            CapabilityRegistry capabilityRegistry,
            DataSource dataSource,
            RuntimeClock runtimeClock,
            OrchestrationExecutionRegistry orchestrationExecutionRegistry
    ) {
        this(
                modelSupplier,
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
            Supplier<CompiledModel> modelSupplier,
            KernelRunner kernelRunner,
            EntityManager entityManager,
            CapabilityDispatcher capabilityDispatcher,
            CapabilityRegistry capabilityRegistry,
            DataSource dataSource,
            RuntimeClock runtimeClock,
            OrchestrationExecutionRegistry orchestrationExecutionRegistry,
            RuntimeInvariantEngineFactory runtimeInvariantEngineFactory
    ) {
        this(modelSupplier, kernelRunner, entityManager, capabilityDispatcher, capabilityRegistry,
                dataSource, runtimeClock, orchestrationExecutionRegistry, runtimeInvariantEngineFactory,
                null, null, null);
    }

    public GeneratedCrudRuntimeSupport(
            Supplier<CompiledModel> modelSupplier,
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
        if (modelSupplier == null) {
            throw new IllegalArgumentException("modelSupplier is required");
        }
        if (kernelRunner == null) {
            throw new IllegalArgumentException("kernelRunner is required");
        }
        this.modelSupplier = modelSupplier;
        this.kernelRunner = kernelRunner;
        this.entityManager = entityManager;
        this.dataSource = dataSource;
        this.runtimeClock = runtimeClock == null ? new SystemRuntimeClock() : runtimeClock;
        this.runtimeInvariantEngineFactory = runtimeInvariantEngineFactory == null
                ? missingRuntimeInvariantEngineFactory()
                : runtimeInvariantEngineFactory;
        this.auditLogStore = auditLogStore == null ? AuditLogStore.noop() : auditLogStore;
        this.permissionEvaluator = permissionEvaluator == null ? PermissionEvaluator.allowAll() : permissionEvaluator;
        this.idempotencyStore = idempotencyStore == null ? IdempotencyStore.noop() : idempotencyStore;
        this.lifecycleSupport = new CrudLifecycleSupport(this, this.entityManager);
        this.schemaValidationSupport = new CrudSchemaValidationSupport(this);
        this.referenceLookupSupport = new CrudReferenceLookupSupport(this, this.entityManager);
        this.orchestrationSupport = new CrudOrchestrationSupport(
                this, this.modelSupplier, this.kernelRunner, this.entityManager, capabilityDispatcher,
                capabilityRegistry, this.dataSource, this.runtimeClock,
                orchestrationExecutionRegistry == null
                        ? new InMemoryOrchestrationExecutionRegistry()
                        : orchestrationExecutionRegistry);
        orchestrationSupport.initializeOrchestrationSubscribers();
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
        // The ONE allocation of a nextNumber() default for this create: the gateway write receives
        // it in this payload (and so allocates nothing itself), and the JPA entity gets it via
        // applyCreateFields(name, source, payload, target) -- one number per row, no gaps.
        return new LinkedHashMap<>(materializeEntityValues(entity, dto, null, false, true));
    }

    public Map<String, Object> buildUpdateInvariantPayload(
            String entityName,
            UUID id,
            Object existing,
            Object dto
    ) {
        CompiledConcept entity = requireEntity(entityName);
        Map<String, Object> payload = new LinkedHashMap<>(materializeEntityValues(entity, dto, existing, true, false));
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
     * <p>
     * Engine-aware, on the same predicate {@link #existsByAnchor} uses: with no EntityManager
     * (npdev.storage.mode=in-memory) business rows live only in the ConceptGateway/ConceptStore, and
     * {@code dataSource} -- still non-null there, it is the kernel's own JDBC store -- holds no
     * business tables at all, so a {@code SELECT ... FROM <target table>} against it failed with
     * "Table not found" and turned every in-memory create/update carrying a reference into a 500.
     * The in-memory store is tenant-keyed, so a gateway read under the caller's context keeps the
     * same existence-plus-tenant guarantee.
     */
    public void enforceBondTargetTenant(String entityName, Map<String, Object> payload, ExecutionContext context) {
        boolean inMemoryBusinessData = entityManager == null && conceptGateway != null;
        if (payload == null || (!inMemoryBusinessData && dataSource == null)) {
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
            boolean exists = inMemoryBusinessData
                    ? referenceLookupSupport.existsByAnchorViaConceptGateway(targetEntity, anchor, rawValue, context)
                    : bondTargetExistsForTenant(
                            SqlIdentifierSupport.tableName(targetEntity),
                            SqlIdentifierSupport.columnName(anchor),
                            rawValue,
                            callerTenant);
            if (!exists) {
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

    /** Create with the payload {@link #buildCreateInvariantPayload} built: also carries over the
     *  sequence (nextNumber) values allocated there, so the entity and the gateway agree. */
    public void applyCreateFields(String entityName, Object source, Map<String, Object> createPayload, Object target) {
        applyEntityFields(entityName, source, target, false);
        if (target == null || createPayload == null) {
            return;
        }
        for (CompiledField field : requireEntity(entityName).getFields()) {
            CompiledSchema schema = field == null ? null : field.getSchema();
            if (schema == null || schema.getDefaultExpression() == null
                    || !NEXT_NUMBER_DEFAULT.matcher(schema.getDefaultExpression()).matches()) {
                continue;
            }
            Object allocated = createPayload.get(field.getName());
            if (allocated != null && readObjectValue(target, field.getName()) == null) {
                writeObjectValue(target, field.getName(), allocated);
            }
        }
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
                + "trigger_correlation_id, tenant_id, event_name, due_at, status, attempt_count, created_at, "
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
                publishScheduledDueEvent(record, payload, Map.of(
                        "orchestrationAction", "scheduleEvent",
                        "scheduleId", record.id().toString(),
                        "sourceEventName", nullToEmpty(record.sourceEventName())
                ));
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
        for (String message : schemaValidationSupport.validateFieldTypesAndReferences(entity, normalizedPayload)) {
            violations.add(schemaValidationSupport.toTypedViolation(entityName, message));
        }
        lifecycleSupport.validateLifecycleTransitions(entityName, entity, normalizedPayload, violations);

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
                        return referenceLookupSupport.resourceHasConflict(
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
                        return referenceLookupSupport.scopeExists(conceptName, fieldPath, expectedValue);
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
                violations.add(schemaValidationSupport.toInvariantViolation(entityName, violation, uniqueInvariantRefs));
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

        CompiledStateTransition transition = lifecycleSupport.findLifecycleTransition(lifecycle, previous, nextStatus);
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
        Optional<CompiledEvent> eventOpt = modelSupplier.get().getEvents().stream()
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

    private void publishScheduledDueEvent(ScheduledEventRecord record, Map<String, Object> payload,
                                          Map<String, Object> metadata) {
        // RUN-10: publish the due event with the SAME correlation id AND tenant it was scheduled under,
        // so a correlated AWAIT_EVENT (matchCorrelation: true) is satisfied instead of staying parked.
        Map<String, Object> eventPayload = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        if (metadata != null && !metadata.isEmpty()) {
            eventPayload.put("_meta", Map.copyOf(new LinkedHashMap<>(metadata)));
        }
        String tenantId = (record.tenantId() == null || record.tenantId().isBlank())
                ? "default"
                : record.tenantId();
        kernelRunner.publishExternalEvent(
                record.eventName(),
                Map.copyOf(eventPayload),
                record.correlationId(),
                null,
                ExecutionContext.of(tenantId, null)
        );
    }

    void publishRuntimeEvent(String eventName, Map<String, Object> payload, Map<String, Object> metadata) {
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

    /** CARVE-3: delegates to {@link CrudOrchestrationSupport} (the REG-241 reload hook is part of the public surface). */
    public void reloadOrchestrationSubscribers(CompiledModel model) {
        orchestrationSupport.reloadOrchestrationSubscribers(model);
    }

    // Kept on this class (private, same signature) because tests reach them reflectively.
    private OrchestrationActionExecutionResult executeCreateOrchestrationAction(
            EventCreateOrchestration action,
            EventEnvelope envelope,
            Map<String, Object> eventPayload
    ) {
        return orchestrationSupport.executeCreateOrchestrationAction(action, envelope, eventPayload);
    }

    private boolean evaluateOrchestrationCondition(String rawCondition, Map<String, Object> eventPayload) {
        return orchestrationSupport.evaluateOrchestrationCondition(rawCondition, eventPayload);
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
    static boolean isUniqueViolation(Exception exception) {
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

    /**
     * REG-232: a NOT NULL constraint failure is a business-level "required field missing"
     * condition, not an infrastructure fault -- callers should classify it distinctly rather than
     * letting the raw JDBC exception surface as a generic failure. 23502 is Postgres/H2's dedicated
     * not-null code; MySQL and SQL Server report the generic integrity class 23000, so the vendor
     * error number is the discriminator there -- the same per-engine asymmetry
     * {@link #isForeignKeyViolation} already documents (STOR-11). Checked AFTER
     * {@link #isUniqueViolation} by every caller, so a duplicate is never misreported as a missing
     * field (23505 and 23502 never overlap in practice, but the ordering mirrors the FK check's own
     * belt-and-suspenders convention).
     */
    static boolean isNotNullViolation(Throwable exception) {
        if (exception == null) {
            return false;
        }
        if (exception instanceof SQLException sqlException) {
            if ("23502".equals(sqlException.getSQLState())
                    || sqlException.getErrorCode() == 1048  // MySQL ER_BAD_NULL_ERROR
                    || sqlException.getErrorCode() == 515) { // SQL Server "Cannot insert NULL"
                return true;
            }
        }
        String message = exception.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("not null") || normalized.contains("cannot be null")
                    // H2's actual wording is reversed ("NULL not allowed for column ..."), which
                    // "not null" alone does not match -- caught this via a failing regression test
                    // that used H2's real message text, not a paraphrase.
                    || normalized.contains("null not allowed")) {
                return true;
            }
        }
        return isNotNullViolation(exception.getCause());
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
                    envelope.tenantId(),
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

    void insertScheduledEvent(
            UUID id,
            String scheduleKey,
            String orchestrationName,
            int actionIndex,
            String sourceEventName,
            String sourceEventId,
            String correlationId,
            String tenantId,
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
            statement.setString(8, tenantId == null || tenantId.isBlank() ? "default" : tenantId);
            statement.setString(9, eventName);
            statement.setTimestamp(10, toTimestamp(dueAt));
            statement.setString(11, payloadJson);
            statement.setString(12, SCHEDULE_STATUS_PENDING);
            statement.setTimestamp(13, now);
            statement.setTimestamp(14, now);
            statement.executeUpdate();
        }
    }

    ExecutionContext resolveCurrentExecutionContext() {
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
            // REG-227: RuntimeApiKeyAuthFilter's own claims carry "actor_id", but JwtBearerAuthFilter
            // stashes the RAW JWT payload -- whose actor claim is the standard "sub", never
            // "actor_id" (confirmed by decoding a real minted token: {sub, tenant_id, roles, tv, ...},
            // no actor_id key at all). Without this fallback, actorId was UNCONDITIONALLY null for
            // every JWT-mode request, so ExecutionContext.of(tenantId, null) normalized to actorId
            // "anonymous" regardless of who was really authenticated, and the token-revocation check
            // below (gated on actorId != null) could never even run -- not merely "downgrades on
            // revocation" as originally diagnosed, but "never identifies a JWT actor at all", of which
            // the missing revocation enforcement is one visible symptom.
            String actorId = asNonBlankString(claims.get("actor_id"));
            if (actorId == null) {
                actorId = asNonBlankString(claims.get("sub"));
            }
            if (tenantId == null && actorId == null) {
                return ExecutionContext.anonymous();
            }

            // LNCH-4: a token minted with a 'tv' claim is only valid while it still matches the
            // identity pack's live token_version for this (tenant, actor) -- same revocation contract
            // IdentityAwareContextResolver enforces on the RuntimeHost admin/business-UI path. Only
            // checked when actorId itself resolved; otherwise IdentityRoleLookup.tokenVersion's
            // null-actor guard would report version 0 and falsely "revoke" every non-zero-versioned
            // token whose actor didn't resolve here, rather than correctly no-op'ing.
            // REG-227: a revoked token must be REJECTED, not downgraded to ExecutionContext.anonymous()
            // -- anonymous() carries DEFAULT_ROLE "USER" (not empty roles), which is legitimately
            // sufficient for ordinary CRUD reads on any concept with no explicit permission rule, so
            // the previous "fall back to anonymous" here silently let a revoked JWT keep reading data
            // through this path indefinitely, diverging from IdentityAwareContextResolver's own
            // throw-401 behavior on the exact same check despite this method's own doc comment
            // insisting the two paths "can never diverge".
            if (actorId != null && isTokenRevoked(claims.get("tv"), tenantId, actorId)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token_revoked");
            }
            // SESS-1: and a token for one device whose session row was revoked -- the same shared decision
            // IdentityAwareContextResolver applies, so the two paths cannot diverge.
            if (actorId != null && claims.get("sid") != null) {
                Optional<IdentityPackTableNames> sessionTables = identityTables();
                if (sessionTables.isPresent()
                        && IdentityRoleLookup.isSessionRevoked(claims.get("sid"), dataSource, sessionTables.get(), tenantId)) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session_revoked");
                }
            }

            // Identity-backed roles (when the identity pack is populated for this tenant+actor) are
            // authoritative over the principal's claim-roles -- same supplement-with-fallback contract
            // the RuntimeHost IdentityAwareContextResolver applies, kept consistent across both
            // context-resolution paths via the shared IdentityRoleLookup.
            Optional<IdentityPackTableNames> identityTables = identityTables();
            Set<String> identityRoles = identityTables.isEmpty()
                    ? Set.of()
                    : IdentityRoleLookup.rolesFor(dataSource, identityTables.get(), tenantId, actorId);
            Set<String> roles = identityRoles.isEmpty() ? parseRoles(claims.get("roles")) : identityRoles;
            ExecutionContext context = ExecutionContext.of(tenantId, actorId);
            return roles.isEmpty() ? context : context.withRoles(roles);
        } catch (ResponseStatusException statusException) {
            // REG-227: a deliberate rejection (token_revoked above) must propagate, not be swallowed
            // into the same anonymous fallback it exists to prevent.
            throw statusException;
        } catch (Exception ignored) {
            return ExecutionContext.anonymous();
        }
    }

    private boolean isTokenRevoked(Object rawTokenVersion, String tenantId, String actorId) {
        // REG-23: delegate to the single shared decision point (IdentityRoleLookup.isTokenRevoked) so
        // both claim->context paths agree, including the config-driven rejection of legacy tv-less tokens.
        Optional<IdentityPackTableNames> identityTables = identityTables();
        if (identityTables.isEmpty()) {
            return false;
        }
        return IdentityRoleLookup.isTokenRevoked(rawTokenVersion, dataSource, identityTables.get(), tenantId, actorId);
    }

    // REG-240: was resolved ONCE at construction into a final field, so a model reload that
    // composes (or renames a table within) the identity pack after boot was never observed here --
    // an app that booted without identity::User kept an empty role set forever, and a tokenVersion
    // bump stopped logging anyone out. Reads modelSupplier.get() fresh instead, the same per-call
    // cost the ten other IdentityPackTableNames.tryResolve callers in RuntimeHost already pay.
    private Optional<IdentityPackTableNames> identityTables() {
        return IdentityPackTableNames.tryResolve(modelSupplier.get());
    }

    /**
     * ENG-4: the live counterpart of the generated service's baked {@code ENUM_VALUES_BY_FIELD}.
     * Reads {@code modelSupplier.get()} fresh, so an enum option added by a live model reload is
     * accepted by the generated CRUD service without a rebuild. Empty when the concept or field is
     * unknown to the live model or declares no enum values (the caller falls back to its baked set).
     */
    public Optional<Set<String>> liveEnumValues(String conceptName, String fieldName) {
        if (conceptName == null || fieldName == null) {
            return Optional.empty();
        }
        Optional<CompiledConcept> concept = modelSupplier.get().findConcept(conceptName);
        if (concept.isEmpty()) {
            return Optional.empty();
        }
        for (CompiledField field : concept.get().getFields()) {
            if (fieldName.equals(field.getName())) {
                List<String> values = field.getEnumValues();
                return values.isEmpty() ? Optional.empty() : Optional.of(new LinkedHashSet<>(values));
            }
        }
        return Optional.empty();
    }

    /**
     * LIVE-1: the live counterpart of the generated service's baked {@code ALL_FIELD_NAMES}. The names
     * of every field the LIVE model declares for the concept (empty when the concept is unknown), so a
     * field added by a model reload is accepted by the generated CRUD service's request whitelist
     * without a rebuild -- the generated service accepts a name when EITHER its baked set or this one
     * contains it.
     */
    public Set<String> liveFieldNames(String conceptName) {
        if (conceptName == null) {
            return Set.of();
        }
        Optional<CompiledConcept> concept = modelSupplier.get().findConcept(conceptName);
        if (concept.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (CompiledField field : concept.get().getFields()) {
            if (field != null && field.getName() != null) {
                names.add(field.getName());
            }
        }
        return names;
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
            boolean patchMode,
            boolean allocateSequences
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

        applySchemaValueBehaviors(entity, values, allocateSequences);
        return values;
    }

    /** The row's own tenantId when the payload carries one, else the caller's (sequence scope: tenant). */
    private String tenantForDefaults(Map<String, Object> values) {
        Object rowTenant = readMapValue(values, "tenantId");
        if (rowTenant != null && !String.valueOf(rowTenant).isBlank()) {
            return String.valueOf(rowTenant);
        }
        return resolveCurrentExecutionContext().tenantId();
    }

    /** nextNumber('seq') allocates a counter value -- only ever on the persisting pass, never while
     *  building a validation payload, or every create burns extra numbers (PTM-0001 then PTM-0004). */
    private static final java.util.regex.Pattern NEXT_NUMBER_DEFAULT =
            java.util.regex.Pattern.compile("^\\s*nextNumber\\(.*\\)\\s*$");

    /** The lifecycle's initial state when {@code fieldName} is its status field: the state flagged
     *  {@code initial}, else the first declared one (the kernel gateway's own rule). */
    private static String lifecycleInitialState(CompiledConcept entity, String fieldName) {
        com.npdev.dsl.v1.compiled.CompiledLifecycle lifecycle = entity.getLifecycle();
        if (lifecycle == null || !fieldName.equals(lifecycle.getStatusField())) {
            return null;
        }
        String first = null;
        for (com.npdev.dsl.v1.compiled.CompiledStateMachineState state : lifecycle.getStates()) {
            if (state == null || state.getValue() == null || state.getValue().isBlank()) {
                continue;
            }
            if (state.isInitial()) {
                return state.getValue();
            }
            if (first == null) {
                first = state.getValue();
            }
        }
        return first;
    }

    private void applySchemaValueBehaviors(CompiledConcept entity, Map<String, Object> values, boolean allocateSequences) {
        if (entity == null || values == null) {
            return;
        }
        // Pigmentampas P9: a create that omits the lifecycle status field starts in the
        // lifecycle's initial state, as the kernel gateway already does -- without this the REST
        // invariant check rejected it first with required(status). Updates never get here with the
        // field null: materializeEntityValues merged the existing record's value first.
        for (CompiledField field : entity.getFields()) {
            if (field == null || field.getName() == null || readMapValue(values, field.getName()) != null) {
                continue;
            }
            String initialState = lifecycleInitialState(entity, field.getName());
            if (initialState != null) {
                values.put(field.getName(), normalizeFieldValue(entity, field, initialState));
            }
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
                    boolean sequenceDefault = NEXT_NUMBER_DEFAULT.matcher(schema.getDefaultExpression()).matches();
                    if (sequenceDefault && !allocateSequences) {
                        continue;
                    }
                    boolean userDefault = UserScopedExpressions.referencesUser(schema.getDefaultExpression());
                    Object evaluated = userDefault
                            ? com.npdev.kernel.concepts.ValueExpressionEvaluator.evaluate(
                                    schema.getDefaultExpression(), values, resolveCurrentExecutionContext())
                            : fieldDefaults == null
                                    ? null
                                    : fieldDefaults.evaluateFieldDefault(schema.getDefaultExpression(), values, tenantForDefaults(values));
                    if (evaluated == null && !userDefault) {
                        evaluated = evaluateSchemaExpression(schema.getDefaultExpression(), values);
                    }
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

    Optional<CompiledConcept> findEntity(String name) {
        CompiledModel currentModel = modelSupplier.get();
        Optional<CompiledConcept> exact = currentModel.findConcept(name);
        if (exact.isPresent()) {
            return exact;
        }
        String normalized = normalize(name);
        for (CompiledConcept entity : currentModel.getConcepts()) {
            if (normalize(entity.getName()).equals(normalized)) {
                return Optional.of(entity);
            }
        }
        return Optional.empty();
    }

    CompiledConcept requireEntity(String entityName) {
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

    Optional<CompiledField> resolveReferenceAnchor(CompiledField referenceField, CompiledConcept targetEntity) {
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

    Optional<CompiledField> idField(CompiledConcept entity) {
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
        Map<String, Object> materialized = materializeEntityValues(entity, source, patchMode ? target : null, patchMode, false);
        for (CompiledField field : entity.getFields()) {
            if (field == null || field.isId()) {
                continue;
            }
            if (!materialized.containsKey(field.getName())) {
                continue;
            }
            Object materializedValue = materialized.get(field.getName());
            // LIVE-1: a field added by a live model reload has no property on an entity generated
            // before it existed -- write it to the entity's extras bag, coerced by its DSL type (a
            // typed property would be coerced by its setter's parameter type; the bag has none).
            if (ExtraFieldAccess.supports(target.getClass())
                    && !ValueCoercionSupport.declaresProperty(target.getClass(), field.getName())) {
                ExtraFieldAccess.put(target, field.getName(),
                        DslTypeCoercionSupport.normalizeByDslType(field.getDslType(), materializedValue));
                continue;
            }
            writeObjectValue(target, field.getName(), materializedValue);
        }
    }


    boolean existsByAnchor(CompiledConcept entity, CompiledField anchor, Object value) {
        return referenceLookupSupport.existsByAnchor(entity, anchor, value);
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
            appendCrudAudit(conceptName, operation, null, "DENY", safeCtx, null, null);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "forbidden: " + conceptName + "." + operation);
        }
    }

    public void auditCrudMutation(String conceptName, String operation, String resourceId,
                                  String outcome, ExecutionContext ctx) {
        appendCrudAudit(conceptName, operation, resourceId, outcome, ctx, null, null);
    }

    /**
     * R5.1: same as the 5-arg {@link #auditCrudMutation} above, but carries the record's own
     * field-level before/after snapshot (service-base.mustache's {@code mapFromEntity} output) so
     * the audit trail records WHAT changed, not just that a mutation happened -- "who changed what,
     * when" is the actual ERP requirement, and a bare CRUD_UPDATE row with no field data answers only
     * "when"/"who". Either snapshot may be {@code null}: CREATE has no before; a delete (soft or
     * physical -- the store makes that call, not this method) has no after; RESTORE's before is
     * deliberately omitted rather than guessed, since the row was excluded from every read while
     * deleted.
     */
    public void auditCrudMutation(String conceptName, String operation, String resourceId,
                                  String outcome, ExecutionContext ctx,
                                  Map<String, Object> before, Map<String, Object> after) {
        appendCrudAudit(conceptName, operation, resourceId, outcome, ctx, before, after);
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
                                  String outcome, ExecutionContext ctx,
                                  Map<String, Object> before, Map<String, Object> after) {
        try {
            ExecutionContext safeCtx = ctx == null ? ExecutionContext.anonymous() : ctx;
            String safeResourceType = (conceptName == null || conceptName.isBlank())
                    ? "UNKNOWN" : conceptName.toUpperCase(Locale.ROOT);
            String safeOperation = (operation == null || operation.isBlank()) ? "UNKNOWN" : operation;
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("operation", safeOperation);
            try {
                Set<String> redacted = redactedAuditFields(conceptName);
                String fieldDiff = describeFieldDiff(before, after, redacted);
                if (!fieldDiff.isEmpty()) {
                    meta.put("fieldDiff", fieldDiff);
                }
                // UI-4: the same changes as structured JSON, so a reader never has to parse the ambiguous
                // "a: x -> y; b: p -> q" string (a value may itself contain "; " or " -> ").
                String fieldChanges = describeFieldChangesJson(before, after, redacted);
                if (!fieldChanges.isEmpty()) {
                    meta.put("fieldChanges", fieldChanges);
                }
            } catch (RuntimeException redactionFailure) {
                // Fail closed on VALUES: if the redaction set cannot be computed, no field value is written
                // at all (an empty "nothing to redact" set would leak sensitive / password fields). The audit
                // row itself is still appended, without the diff.
                LOG.log(Level.WARNING, "Audit field diff omitted for " + safeResourceType
                        + ": redaction set unavailable", redactionFailure);
                meta.remove("fieldDiff");
                meta.remove("fieldChanges");
            }
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
                    meta
            ));
        } catch (Exception ignored) {
            // Audit must never break primary execution.
        }
    }



    /**
     * UI-4: names of the concept's fields whose values must never reach the audit trail in clear:
     * {@code sensitive: true} fields and password-widget fields. Empty for an unknown concept or
     * an empty model.
     */
    private Set<String> redactedAuditFields(String conceptName) {
        if (conceptName == null || conceptName.isBlank()) {
            return Set.of();
        }
        Optional<CompiledConcept> concept = findEntity(conceptName);
        if (concept.isEmpty()) {
            return Set.of();
        }
        Set<String> redacted = new HashSet<>();
        for (CompiledField field : concept.get().getFields()) {
            boolean password = field.getUi() != null && "password".equals(field.getUi().getWidget());
            if (field.isSensitive() || password) {
                redacted.add(field.getName());
            }
        }
        return redacted;
    }

}
