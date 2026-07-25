package com.npdev.kernel.concepts;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import com.npdev.kernel.security.PermissionDecision;
import com.npdev.kernel.security.PermissionRequirement;
import com.npdev.kernel.security.PermissionSubject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DefaultConceptGateway implements ConceptGateway {
    private static final String RESOURCE_TYPE = "CONCEPT";

    private final ConceptStore store;
    private final PermissionEvaluator permissionEvaluator;
    private final TenantIsolationPolicy tenantIsolationPolicy;
    private final AuditLogStore auditLogStore;
    private final ConceptGatewaySemanticPolicy semanticPolicy;
    private final ConceptGatewayTraceSink traceSink;

    public DefaultConceptGateway(ConceptStore store) {
        this(store, PermissionEvaluator.allowAll(), TenantIsolationPolicy.STRICT_EQUALS, AuditLogStore.noop());
    }

    public DefaultConceptGateway(
            ConceptStore store,
            PermissionEvaluator permissionEvaluator,
            TenantIsolationPolicy tenantIsolationPolicy,
            AuditLogStore auditLogStore
    ) {
        this(
                store,
                permissionEvaluator,
                tenantIsolationPolicy,
                auditLogStore,
                ConceptGatewaySemanticPolicy.noop(),
                ConceptGatewayTraceSink.noop()
        );
    }

    public DefaultConceptGateway(
            ConceptStore store,
            PermissionEvaluator permissionEvaluator,
            TenantIsolationPolicy tenantIsolationPolicy,
            AuditLogStore auditLogStore,
            ConceptGatewaySemanticPolicy semanticPolicy,
            ConceptGatewayTraceSink traceSink
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.permissionEvaluator = Objects.requireNonNull(permissionEvaluator, "permissionEvaluator");
        this.tenantIsolationPolicy = Objects.requireNonNull(tenantIsolationPolicy, "tenantIsolationPolicy");
        this.auditLogStore = Objects.requireNonNull(auditLogStore, "auditLogStore");
        this.semanticPolicy = Objects.requireNonNull(semanticPolicy, "semanticPolicy");
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink");
    }

    @Override
    public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
        ExecutionContext effectiveContext = normalizeContext(context);
        String tenantId = enforceTenant(request.tenantId(), effectiveContext, "CONCEPT_READ", request.conceptName(), request.id());
        enforcePermission(effectiveContext, "concept.read", request.conceptName(), "CONCEPT_READ", request.id());

        ConceptGatewayRequestContext requestContext = requestContext(
                ConceptGatewayOperation.READ,
                request.conceptName(),
                request.id(),
                tenantId,
                Map.of(),
                effectiveContext,
                Optional.empty()
        );
        ConceptSemanticDecision decision = evaluateRuleProfiles(
                requestContext,
                ruleProfilesForRead(effectiveContext)
        );

        // LNCH-13: row-level read scoping checked BEFORE field-visibility filtering (a hidden
        // field the access rule references, e.g. ownerId, must still be visible to the rule
        // itself) -- a denied row is treated identically to "not found" (see isRowReadable's
        // javadoc: never confirms a row exists outside the caller's scope).
        Optional<ConceptRecord> record = store.findById(tenantId, request.conceptName(), request.id())
                .filter(item -> semanticPolicy.isRowReadable(item, requestContext))
                .map(item -> semanticPolicy.filterVisibleFields(item, requestContext));
        audit(effectiveContext, "CONCEPT_READ", request.conceptName(), request.id(),
                record.isPresent() ? "SUCCESS" : "NOT_FOUND", record.isPresent() ? "allowed" : "not_found", tenantId);
        trace(requestContext, record.isPresent() ? "SUCCESS" : "NOT_FOUND",
                record.isPresent() ? "allowed" : "not_found", decision);
        return record;
    }

    @Override
    public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
        ExecutionContext effectiveContext = normalizeContext(context);
        String tenantId = enforceTenant(request.tenantId(), effectiveContext, "CONCEPT_LIST", request.conceptName(), "*");
        enforcePermission(effectiveContext, "concept.list", request.conceptName(), "CONCEPT_LIST", "*");

        ConceptGatewayRequestContext requestContext = requestContext(
                ConceptGatewayOperation.LIST,
                request.conceptName(),
                "*",
                tenantId,
                Map.of(),
                effectiveContext,
                Optional.empty()
        );
        ConceptSemanticDecision decision = evaluateRuleProfiles(
                requestContext,
                ruleProfilesForRead(effectiveContext)
        );

        List<ConceptRecord> records = store.findAll(tenantId, request.conceptName()).stream()
                .filter(item -> semanticPolicy.isRowReadable(item, requestContext))
                .map(item -> semanticPolicy.filterVisibleFields(item, requestContext))
                .filter(item -> matchesExact(item, request.filterField(), request.filterValue()))
                .toList();
        audit(effectiveContext, "CONCEPT_LIST", request.conceptName(), "*", "SUCCESS", "allowed", tenantId);
        trace(requestContext, "SUCCESS", "allowed", decision);
        return records;
    }

    @Override
    public ConceptPage query(ConceptQueryRequest request, ExecutionContext context) {
        ExecutionContext effectiveContext = normalizeContext(context);
        String tenantId = enforceTenant(request.tenantId(), effectiveContext, "CONCEPT_LIST", request.conceptName(), "*");
        enforcePermission(effectiveContext, "concept.list", request.conceptName(), "CONCEPT_LIST", "*");

        ConceptGatewayRequestContext requestContext = requestContext(
                ConceptGatewayOperation.LIST,
                request.conceptName(),
                "*",
                tenantId,
                Map.of(),
                effectiveContext,
                Optional.empty()
        );
        ConceptSemanticDecision decision = evaluateRuleProfiles(
                requestContext,
                ruleProfilesForRead(effectiveContext)
        );

        // Push the filter/sort/page window down to the store (SQL LIMIT/OFFSET on JDBC), then apply
        // per-record field visibility to the returned page only -- never materialize the whole table.
        ConceptPage page = store.query(tenantId, request.conceptName(), request.query());
        List<ConceptRecord> visible = new ArrayList<>(page.items().size());
        for (ConceptRecord item : page.items()) {
            // LNCH-13: row-level scoping applied to the already-paginated page, not pushed into
            // the SQL WHERE clause -- a deliberate v1 boundary (see docs/EXPRESSIONS.md /
            // LAUNCH_READINESS_GAPS.md).
            if (semanticPolicy.isRowReadable(item, requestContext)) {
                visible.add(semanticPolicy.filterVisibleFields(item, requestContext));
            }
        }
        long total = page.total();
        boolean hasMore = page.hasMore();
        // REG-42 (LNCH13-F3, REG-16-resid Round 2 follow-up): page.total()/hasMore() above were
        // computed by the store BEFORE row-scope filtering, so trusting them would leak the count
        // of rows outside the caller's access.read scope (an information-disclosure side channel,
        // not just a pagination-accuracy nuisance -- see docs/ROW_LEVEL_AUTHORIZATION.md). Only pay
        // the extra query when the concept actually declares access.read; every other concept's
        // query() is unaffected. Re-runs the SAME filters/sorts unpaged (bounded by
        // ConceptQuery.MAX_LIMIT, the platform's existing single-query ceiling) to count exactly the
        // rows this caller may see.
        if (semanticPolicy.hasRowReadScope(request.conceptName())) {
            ConceptQuery unpaged = new ConceptQuery(
                    request.query().filters(), request.query().sorts(), 0, ConceptQuery.MAX_LIMIT);
            ConceptPage unpagedResult = store.query(tenantId, request.conceptName(), unpaged);
            long readableTotal = unpagedResult.items().stream()
                    .filter(item -> semanticPolicy.isRowReadable(item, requestContext))
                    .count();
            total = readableTotal;
            hasMore = (long) request.query().offset() + visible.size() < readableTotal;
        }
        audit(effectiveContext, "CONCEPT_LIST", request.conceptName(), "*", "SUCCESS", "allowed", tenantId);
        trace(requestContext, "SUCCESS", "allowed", decision);
        return new ConceptPage(visible, total, hasMore);
    }

    private static boolean matchesExact(ConceptRecord record, String field, String value) {
        if (field == null) {
            return true;
        }
        Object actual = record.data().get(field);
        return Objects.equals(actual == null ? null : String.valueOf(actual), value);
    }

    @Override
    public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
        ExecutionContext effectiveContext = normalizeContext(context);
        String tenantId = enforceTenant(request.tenantId(), effectiveContext, "CONCEPT_WRITE", request.conceptName(), request.id());

        ConceptGatewayRequestContext requestContext = requestContext(
                ConceptGatewayOperation.SAVE,
                request.conceptName(),
                request.id(),
                tenantId,
                request.data(),
                effectiveContext,
                Optional.empty()
        );
        Optional<ConceptRecord> previous = store.findById(tenantId, request.conceptName(), request.id());
        requestContext = requestContext.withPreviousRecord(previous);

        // REG-41 (LNCH13-F2, REG-16-resid Round 2): authorization must run BEFORE any semantic
        // validation that touches the previous record's data (normalizeAndValidate /
        // applyDefaultsAndDerivedValues / validateLifecycleTransition / BEFORE_COMMIT rule
        // profiles, bundled in runWriteSemantics below) -- otherwise a caller with no write
        // permission or no row-scope access can learn the row's current state (e.g. its
        // lifecycle-status value, via a CONCEPT_LIFECYCLE_TRANSITION_INVALID error's "from" detail)
        // from a validation failure thrown before authorization ever ran. The previous-record
        // FETCH above stays where it is -- enforceRowWritable needs it for update/delete -- it is
        // the semantic-validation USE of that data that must wait until authorization passes.
        enforcePermission(effectiveContext, "concept.write", request.conceptName(), "CONCEPT_WRITE", request.id());
        enforceRowWritable(requestContext, "CONCEPT_WRITE");

        ConceptSemanticDecision decision = runWriteSemantics(
                requestContext,
                ruleProfilesForWriteBeforeCommit(effectiveContext)
        );

        // LNCH-16: expectedRowVersion is a compare-and-swap request; force explicitly opts out of
        // it even when a version was supplied (a flow declaring last-write-wins intent). Anything
        // else -- no expectedRowVersion, the common case for every caller that predates this
        // feature -- is an unconditional write, unchanged from today.
        Long rowVersion = request.force() ? null : request.expectedRowVersion();
        ConceptRecord record = new ConceptRecord(request.conceptName(), request.id(), tenantId, decision.data(), rowVersion);
        ConceptRecord saved;
        try {
            saved = store.save(record);
        } catch (ConceptStoreOptimisticLockException exception) {
            audit(effectiveContext, "CONCEPT_WRITE", request.conceptName(), request.id(), "CONFLICT", "optimistic_lock", tenantId);
            throw new ConceptGatewayOptimisticLockException(
                    exception.conceptName(), exception.id(), exception.tenantId(), exception.currentRecord());
        }
        ConceptSemanticDecision afterCommit = evaluateRuleProfiles(
                requestContext.withData(saved.data()).withPreviousRecord(Optional.of(saved)),
                ruleProfilesForAfterCommit(effectiveContext)
        );
        decision = decision.merge(afterCommit);
        audit(effectiveContext, "CONCEPT_WRITE", request.conceptName(), request.id(), "SUCCESS", "allowed", tenantId);
        trace(requestContext.withData(saved.data()), "SUCCESS", "allowed", decision);
        return saved;
    }

    @Override
    public void delete(ConceptReadRequest request, ExecutionContext context) {
        ExecutionContext effectiveContext = normalizeContext(context);
        String tenantId = enforceTenant(request.tenantId(), effectiveContext, "CONCEPT_DELETE", request.conceptName(), request.id());
        Optional<ConceptRecord> previous = store.findById(tenantId, request.conceptName(), request.id());
        ConceptGatewayRequestContext requestContext = requestContext(
                ConceptGatewayOperation.DELETE,
                request.conceptName(),
                request.id(),
                tenantId,
                previous.map(ConceptRecord::data).orElseGet(Map::of),
                effectiveContext,
                previous
        );
        ConceptSemanticDecision decision = evaluateRuleProfiles(
                requestContext,
                ruleProfilesForWriteBeforeCommit(effectiveContext)
        );

        enforcePermission(effectiveContext, "concept.delete", request.conceptName(), "CONCEPT_DELETE", request.id());
        enforceRowWritable(requestContext, "CONCEPT_DELETE");
        store.deleteById(tenantId, request.conceptName(), request.id());
        audit(effectiveContext, "CONCEPT_DELETE", request.conceptName(), request.id(), "SUCCESS", "allowed", tenantId);
        trace(requestContext, "SUCCESS", "allowed", decision);
    }

    @Override
    public List<ConceptGatewayTraceRecord> explain() {
        return traceSink.records();
    }

    private ConceptSemanticDecision runWriteSemantics(
            ConceptGatewayRequestContext request,
            List<ConceptRuleProfile> ruleProfiles
    ) {
        ConceptSemanticDecision decision = ConceptSemanticDecision.allow(request.data());

        decision = decision.merge(enforceSemanticDecision(request, semanticPolicy.normalizeAndValidate(request)));
        request = request.withData(decision.data());

        decision = decision.merge(enforceSemanticDecision(request, semanticPolicy.applyDefaultsAndDerivedValues(request)));
        request = request.withData(decision.data());

        decision = decision.merge(enforceSemanticDecision(request, semanticPolicy.validateLifecycleTransition(request)));
        request = request.withData(decision.data());

        decision = decision.merge(evaluateRuleProfiles(request, ruleProfiles));
        return decision;
    }

    private ConceptSemanticDecision evaluateRuleProfiles(
            ConceptGatewayRequestContext request,
            List<ConceptRuleProfile> profiles
    ) {
        ConceptSemanticDecision decision = semanticPolicy.evaluateRuleProfiles(request, profiles);
        ConceptSemanticDecision safeDecision = decision == null
                ? ConceptSemanticDecision.allow(request.data())
                : decision;
        return enforceSemanticDecision(request, safeDecision.withRuleProfiles(profiles));
    }

    private ConceptSemanticDecision enforceSemanticDecision(
            ConceptGatewayRequestContext request,
            ConceptSemanticDecision decision
    ) {
        ConceptSemanticDecision safeDecision = decision == null
                ? ConceptSemanticDecision.allow(request.data())
                : decision;
        if (safeDecision.allowed()) {
            return safeDecision;
        }
        trace(request, "DENIED", safeDecision.code(), safeDecision);
        audit(
                request.executionContext(),
                actionName(request.operation()),
                request.conceptName(),
                request.id() == null ? "*" : request.id(),
                "DENIED",
                safeDecision.code(),
                request.tenantId()
        );
        throw new ConceptGatewaySemanticException(safeDecision.code(), safeDecision.message());
    }

    private String enforceTenant(
            String requestedTenantId,
            ExecutionContext context,
            String action,
            String conceptName,
            String id
    ) {
        String effectiveTenant = normalizeTenant(requestedTenantId, context.tenantId());
        if (!tenantIsolationPolicy.sameTenant(context.tenantId(), effectiveTenant)) {
            audit(context, action, conceptName, id, "DENIED", "tenant_scope_denied", effectiveTenant);
            trace(
                    requestContext(
                            actionOperation(action),
                            conceptName,
                            id,
                            effectiveTenant,
                            Map.of(),
                            context,
                            Optional.empty()
                    ),
                    "DENIED",
                    "tenant_scope_denied",
                    ConceptSemanticDecision.deny(
                            "tenant_scope_denied",
                            "Concept Gateway denied cross-tenant access for concept " + conceptName
                    )
            );
            throw new ConceptGatewayAccessDeniedException(
                    "TENANT_SCOPE_DENIED",
                    "Concept Gateway denied cross-tenant access for concept " + conceptName
            );
        }
        return effectiveTenant;
    }

    private void enforcePermission(
            ExecutionContext context,
            String permission,
            String conceptName,
            String action,
            String id
    ) {
        PermissionDecision decision = permissionEvaluator.evaluate(
                subject(context),
                new PermissionRequirement(permission, "concept", conceptName)
        );
        if (!decision.allowed()) {
            audit(context, action, conceptName, id, "DENIED", decision.code(), context.tenantId());
            trace(
                    requestContext(
                            actionOperation(action),
                            conceptName,
                            id,
                            context.tenantId(),
                            Map.of(),
                            context,
                            Optional.empty()
                    ),
                    "DENIED",
                    decision.code(),
                    ConceptSemanticDecision.deny(
                            decision.code(),
                            decision.message().isBlank() ? "Concept Gateway permission denied." : decision.message()
                    )
            );
            throw new ConceptGatewayAccessDeniedException(
                    "PERMISSION_DENIED",
                    decision.message().isBlank() ? "Concept Gateway permission denied." : decision.message()
            );
        }
    }

    /**
     * REG-16-resid Round 3 (R3-F2): the same two gates {@link #save} applies -- {@code concept.write}
     * permission, then row-level {@code access.write} scope against the record's CURRENT state --
     * with nothing persisted afterwards.
     *
     * <p>The order matches {@code save}'s deliberately, including REG-41's constraint that
     * authorization precede anything that reads the previous record's data. Nothing here touches
     * that data: the previous record is fetched only so {@code enforceRowWritable} can evaluate the
     * scope rule against it, which is the same use {@code save} makes of it.</p>
     */
    @Override
    public void authorizeWrite(ConceptReadRequest request, ExecutionContext context) {
        ExecutionContext effectiveContext = normalizeContext(context);
        String tenantId = enforceTenant(
                request.tenantId(), effectiveContext, "CONCEPT_WRITE", request.conceptName(), request.id());

        ConceptGatewayRequestContext requestContext = requestContext(
                ConceptGatewayOperation.SAVE,
                request.conceptName(),
                request.id(),
                tenantId,
                Map.of(),
                effectiveContext,
                Optional.empty()
        ).withPreviousRecord(store.findById(tenantId, request.conceptName(), request.id()));

        enforcePermission(effectiveContext, "concept.write", request.conceptName(), "CONCEPT_WRITE", request.id());
        enforceRowWritable(requestContext, "CONCEPT_WRITE");
    }

    /**
     * LNCH-13: row-level write scoping -- {@link ConceptGatewaySemanticPolicy#isRowWritable}
     * against the previous record (update/delete) or incoming data (create). Unlike the read
     * path (which fails closed to "not found" per-record), a denied write throws immediately:
     * there's no silent-omission equivalent for "this save/delete may not proceed."
     */
    private void enforceRowWritable(ConceptGatewayRequestContext requestContext, String action) {
        if (semanticPolicy.isRowWritable(requestContext)) {
            return;
        }
        ExecutionContext context = requestContext.executionContext();
        String message = "Concept Gateway denied row-level write access for concept " + requestContext.conceptName();
        audit(context, action, requestContext.conceptName(), requestContext.id(), "DENIED", "row_scope_denied", requestContext.tenantId());
        trace(
                requestContext,
                "DENIED",
                "row_scope_denied",
                ConceptSemanticDecision.deny("ROW_SCOPE_DENIED", message)
        );
        throw new ConceptGatewayAccessDeniedException("ROW_SCOPE_DENIED", message);
    }

    private void audit(
            ExecutionContext context,
            String action,
            String conceptName,
            String id,
            String outcome,
            String reasonCode,
            String requestedTenantId
    ) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("conceptName", conceptName);
        meta.put("recordId", id);
        meta.put("requestedTenantId", requestedTenantId);
        auditLogStore.append(AuditRecord.create(
                context.tenantId(),
                context.actorId(),
                context.roles(),
                action,
                RESOURCE_TYPE,
                conceptName + ":" + id,
                outcome,
                reasonCode,
                context.tags(),
                meta
        ));
    }

    private void trace(
            ConceptGatewayRequestContext request,
            String outcome,
            String reasonCode,
            ConceptSemanticDecision decision
    ) {
        traceSink.append(ConceptGatewayTraceRecord.fromDecision(request, outcome, reasonCode, decision));
    }

    private static ConceptGatewayRequestContext requestContext(
            ConceptGatewayOperation operation,
            String conceptName,
            String id,
            String tenantId,
            Map<String, Object> data,
            ExecutionContext context,
            Optional<ConceptRecord> previousRecord
    ) {
        return new ConceptGatewayRequestContext(
                operation,
                conceptName,
                id,
                tenantId,
                data,
                context,
                previousRecord
        );
    }

    private static List<ConceptRuleProfile> ruleProfilesForRead(ExecutionContext context) {
        List<ConceptRuleProfile> profiles = new ArrayList<>();
        profiles.add(ConceptRuleProfile.ALWAYS);
        profiles.add(modeProfile(context));
        profiles.add(ConceptRuleProfile.QUERY);
        return List.copyOf(profiles);
    }

    private static List<ConceptRuleProfile> ruleProfilesForWriteBeforeCommit(ExecutionContext context) {
        List<ConceptRuleProfile> profiles = new ArrayList<>();
        profiles.add(ConceptRuleProfile.ALWAYS);
        profiles.add(modeProfile(context));
        profiles.add(ConceptRuleProfile.BEFORE_COMMIT);
        return List.copyOf(profiles);
    }

    private static List<ConceptRuleProfile> ruleProfilesForAfterCommit(ExecutionContext context) {
        List<ConceptRuleProfile> profiles = new ArrayList<>();
        profiles.add(ConceptRuleProfile.AFTER_COMMIT);
        profiles.add(modeProfile(context));
        return List.copyOf(profiles);
    }

    private static ConceptRuleProfile modeProfile(ExecutionContext context) {
        String mode = context == null ? null : context.tags().get("executionMode");
        if (mode == null || mode.isBlank()) {
            mode = context == null ? null : context.tags().get("npdev.executionMode");
        }
        if (mode != null && mode.trim().toLowerCase(Locale.ROOT).contains("interactive")) {
            return ConceptRuleProfile.INTERACTIVE;
        }
        return ConceptRuleProfile.HEADLESS;
    }

    private static String actionName(ConceptGatewayOperation operation) {
        if (operation == null) {
            return "CONCEPT_UNKNOWN";
        }
        return switch (operation) {
            case READ -> "CONCEPT_READ";
            case LIST -> "CONCEPT_LIST";
            case SAVE -> "CONCEPT_WRITE";
            case DELETE -> "CONCEPT_DELETE";
        };
    }

    private static ConceptGatewayOperation actionOperation(String action) {
        if (action == null) {
            return ConceptGatewayOperation.SAVE;
        }
        return switch (action) {
            case "CONCEPT_READ" -> ConceptGatewayOperation.READ;
            case "CONCEPT_LIST" -> ConceptGatewayOperation.LIST;
            case "CONCEPT_DELETE" -> ConceptGatewayOperation.DELETE;
            default -> ConceptGatewayOperation.SAVE;
        };
    }

    private static PermissionSubject subject(ExecutionContext context) {
        return new PermissionSubject(
                context.actorId(),
                context.tenantId(),
                new ArrayList<>(context.roles()),
                List.of()
        );
    }

    private static ExecutionContext normalizeContext(ExecutionContext context) {
        return context == null ? ExecutionContext.anonymous() : context;
    }

    private static String normalizeTenant(String requestedTenantId, String fallbackTenantId) {
        if (requestedTenantId == null || requestedTenantId.isBlank()) {
            return fallbackTenantId;
        }
        return requestedTenantId.trim();
    }
}
