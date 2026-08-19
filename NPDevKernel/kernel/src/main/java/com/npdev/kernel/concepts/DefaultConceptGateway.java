package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import com.npdev.kernel.ports.TransactionRunner;
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
    private final TransactionRunner transactionRunner;

    public DefaultConceptGateway(ConceptStore store) {
        this(store, PermissionEvaluator.allowAll(), TenantIsolationPolicy.STRICT_EQUALS, AuditLogStore.noop());
    }

    /**
     * Move 6 §7.5 (docs/MOVE6_TYPED_SURFACE_PLAN.md): builds a gateway wired with the REAL
     * governed semantic policy compiled from {@code model} -- the SAME policy every generated app
     * actually runs (see {@link ConfiguredConceptGatewaySemanticPolicy#fromCompiledModel}).
     * <b>Prefer this over {@link #DefaultConceptGateway(ConceptStore)}</b> (which defaults to a
     * noop policy -- no field-required/enum/lifecycle enforcement at all) whenever a test
     * exercises save/patch/delete against a real compiled model: REG-83 shipped broken for nine
     * commits because every existing unit test used a noop-policy gateway, so a real bug in the
     * write path (an auto-generated id never folded back into the write's own data map) had
     * nothing to trip over. "Fix the default test gateway once, rather than remembering per
     * feature."
     */
    public static DefaultConceptGateway governedBy(ConceptStore store, CompiledModel model) {
        return governedBy(store, model, TransactionRunner.none());
    }

    /**
     * B18 (Move 9 A2): same as {@link #governedBy(ConceptStore, CompiledModel)}, with an explicit
     * {@link TransactionRunner} -- for a test proving the row-authz race is closed when a real
     * transaction manager is wired (mirroring {@code AggregateRuntimeCommitTransactionalTest}'s
     * paired RED/GREEN precedent: {@link TransactionRunner#none()} documents today's degraded,
     * non-atomic path; a real one closes it).
     */
    public static DefaultConceptGateway governedBy(ConceptStore store, CompiledModel model, TransactionRunner transactionRunner) {
        return new DefaultConceptGateway(
                store,
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                ConfiguredConceptGatewaySemanticPolicy.fromCompiledModel(model),
                ConceptGatewayTraceSink.noop(),
                transactionRunner
        );
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
        this(store, permissionEvaluator, tenantIsolationPolicy, auditLogStore, semanticPolicy, traceSink,
                TransactionRunner.none());
    }

    /**
     * B18 (Move 9 A2, {@code docs/ACCEPTED_BOUNDARIES.md}): {@code transactionRunner} wraps {@link #save}/
     * {@link #delete}'s check-then-act critical section (read-for-update through persist) in one
     * transaction when a real one is supplied, closing the race window between evaluating
     * {@code isRowWritable} and persisting a write based on it. {@link TransactionRunner#none()} (what
     * every OTHER constructor above still defaults to) preserves today's behavior exactly -- no
     * caller signature changes, no existing test needed modifying.
     */
    public DefaultConceptGateway(
            ConceptStore store,
            PermissionEvaluator permissionEvaluator,
            TenantIsolationPolicy tenantIsolationPolicy,
            AuditLogStore auditLogStore,
            ConceptGatewaySemanticPolicy semanticPolicy,
            ConceptGatewayTraceSink traceSink,
            TransactionRunner transactionRunner
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.permissionEvaluator = Objects.requireNonNull(permissionEvaluator, "permissionEvaluator");
        this.tenantIsolationPolicy = Objects.requireNonNull(tenantIsolationPolicy, "tenantIsolationPolicy");
        this.auditLogStore = Objects.requireNonNull(auditLogStore, "auditLogStore");
        this.semanticPolicy = Objects.requireNonNull(semanticPolicy, "semanticPolicy");
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
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

    /**
     * RUN-1 (R8a): pushes the cap down to {@link ConceptStore#findAllCapped} (a real SQL
     * {@code LIMIT} on the JDBC adapter) instead of the interface default's fetch-everything-
     * then-trim. Row-level {@code access.read} scoping and the {@code filterField}/{@code
     * filterValue} match are applied to the already-capped slice, not pushed into the store call --
     * the SAME deliberate v1 boundary {@link #query} documents for its own SQL-windowed page (a
     * denied/filtered-out row can shrink the effective result below {@code maxRows} without that
     * meaning fewer than {@code maxRows} rows existed) -- so {@code truncated} reports what the
     * STORE saw, not what survived filtering.
     */
    @Override
    public ConceptListSlice<ConceptRecord> listCapped(ConceptListRequest request, ExecutionContext context, int maxRows) {
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

        ConceptListSlice<ConceptRecord> slice = store.findAllCapped(tenantId, request.conceptName(), maxRows);
        List<ConceptRecord> records = slice.records().stream()
                .filter(item -> semanticPolicy.isRowReadable(item, requestContext))
                .map(item -> semanticPolicy.filterVisibleFields(item, requestContext))
                .filter(item -> matchesExact(item, request.filterField(), request.filterValue()))
                .toList();
        audit(effectiveContext, "CONCEPT_LIST", request.conceptName(), "*", "SUCCESS", "allowed", tenantId);
        trace(requestContext, "SUCCESS", "allowed", decision);
        return new ConceptListSlice<>(records, slice.truncated());
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

    /**
     * Move 10 B1 (LC-B1, MOVE10_AI_LOWCODE_PLAN Part B): pushes the aggregation down to
     * {@link ConceptStore#aggregate} (real SQL {@code GROUP BY} on a database-backed store) instead
     * of the interface default's in-memory-over-{@code list()} evaluation.
     *
     * <p><b>The hard stop this method exists to enforce:</b> {@link #query} applies its row-level
     * {@code access.read} filter AFTER {@code store.query(...)} returns (see that method's own
     * comments) -- correct there because the filter runs before the caller ever sees a row.
     * A {@code GROUP BY} pushed to SQL has no equivalent moment: the database computes a group's
     * {@code sum}/{@code count}/etc. over EVERY matching row before this method gets anything back,
     * so a caller could read a computed total that reveals information about rows their own
     * {@code access.read} scope says they may not see individually -- the aggregate becomes the
     * leak. The compile-time validator ({@code PackValidation#validateAggregateQuery}) already
     * refuses a model that declares {@code groupBy}/{@code aggregates} on such a concept; this is
     * the SAME refusal at runtime, for any query somehow reaching here without going through that
     * validator (a hand-built {@link ConceptAggregateRequest}, a future caller). Accepted boundary
     * until {@code access.read} gains a SQL translation (tracked in the same ledger item as the
     * compile-time check) -- do not silently aggregate the unscoped rows instead.
     *
     * <p>S4 (roadmap B27, ADR-0011 D1, C3): widens this SAME hard stop to a {@code groupBy} join's
     * WHOLE path, not just the request's own base concept -- a join makes the leak strictly worse,
     * since now any concept reached through the join with {@code access.read} taints the aggregate.
     * {@link ConceptGatewaySemanticPolicy#resolveReferenceTarget} is how this runtime backstop
     * resolves a join's target concept without needing a {@link com.npdev.dsl.v1.compiled.CompiledModel}
     * of its own (the NOOP policy's default empty answer means this loop simply finds nothing to
     * widen the check to, same as before this existed).
     *
     * <p>S8 W1.1 (roadmap deferred item #1): a join may chain up to
     * {@code GroupByJoinGrammar.MAX_JOIN_HOPS} hops -- the inner loop below walks EVERY hop in order
     * (resolving each one's target concept off the PREVIOUS hop's target, not always the request's
     * base concept), checking {@code hasRowReadScope} at each one. It stops early (without erroring)
     * the moment a hop can't be resolved, same as the single-hop version did -- the policy simply has
     * nothing further to widen the check to.
     */
    @Override
    public ConceptAggregateResult aggregate(ConceptAggregateRequest request, ExecutionContext context) {
        ExecutionContext effectiveContext = normalizeContext(context);
        String tenantId = enforceTenant(
                request.tenantId(), effectiveContext, "CONCEPT_AGGREGATE", request.conceptName(), "*");
        enforcePermission(effectiveContext, "concept.list", request.conceptName(), "CONCEPT_AGGREGATE", "*");

        if (semanticPolicy.hasRowReadScope(request.conceptName())) {
            throw new ConceptGatewayAccessDeniedException(
                    "AGGREGATE_ACCESS_READ_UNSUPPORTED",
                    "Concept " + request.conceptName() + " declares access.read; groupBy/aggregate "
                            + "queries against it are refused (LC-B1 accepted boundary -- a pushed-down "
                            + "GROUP BY would compute totals over rows access.read exists to hide).");
        }
        for (ConceptAggregateQuery.GroupByField groupByField : request.query().groupBy()) {
            if (!(com.npdev.dsl.v1.query.GroupByJoinGrammar.parse(groupByField.field())
                    instanceof com.npdev.dsl.v1.query.GroupByJoinGrammar.Target.Join join)) {
                continue;
            }
            String currentConcept = request.conceptName();
            for (String referenceField : join.referenceFields()) {
                String targetConcept = semanticPolicy.resolveReferenceTarget(currentConcept, referenceField).orElse(null);
                if (targetConcept == null) {
                    break;
                }
                if (semanticPolicy.hasRowReadScope(targetConcept)) {
                    throw new ConceptGatewayAccessDeniedException(
                            "AGGREGATE_ACCESS_READ_UNSUPPORTED",
                            "groupBy join \"" + groupByField.field() + "\" crosses into concept " + targetConcept
                                    + ", which declares access.read; groupBy/aggregate queries reached through "
                                    + "this join are refused (C3 -- the same leak whether the restricted "
                                    + "concept is queried directly or reached through a join).");
                }
                currentConcept = targetConcept;
            }
        }

        ConceptGatewayRequestContext requestContext = requestContext(
                ConceptGatewayOperation.LIST,
                request.conceptName(),
                "*",
                tenantId,
                Map.of(),
                effectiveContext,
                Optional.empty()
        );
        ConceptSemanticDecision decision = evaluateRuleProfiles(requestContext, ruleProfilesForRead(effectiveContext));

        ConceptAggregateResult result = store.aggregate(tenantId, request.conceptName(), request.query());
        audit(effectiveContext, "CONCEPT_AGGREGATE", request.conceptName(), "*", "SUCCESS", "allowed", tenantId);
        trace(requestContext, "SUCCESS", "allowed", decision);
        return result;
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
        return transactionRunner.runInTransaction(() -> saveWithinTransaction(request, effectiveContext, tenantId));
    }

    /**
     * B18 (Move 9 A2, {@code docs/ACCEPTED_BOUNDARIES.md}): the whole check-then-act body -- reading
     * the row {@code isRowWritable} evaluates through to persisting the write based on that decision
     * -- runs inside {@link #transactionRunner}'s transaction (a no-op wrapper by default; a real one
     * when the host wires it). {@link ConceptStore#findByIdForUpdate} (not {@link ConceptStore#findById})
     * locks the row for the remainder of the transaction on a store that supports it, so a concurrent
     * writer's own {@code findByIdForUpdate} against the same row blocks until this transaction
     * commits or rolls back -- closing the race window a plain {@code findById} left open.
     */
    private ConceptRecord saveWithinTransaction(ConceptWriteRequest request, ExecutionContext effectiveContext, String tenantId) {
        ConceptGatewayRequestContext requestContext = requestContext(
                ConceptGatewayOperation.SAVE,
                request.conceptName(),
                request.id(),
                tenantId,
                request.data(),
                effectiveContext,
                Optional.empty()
        );
        Optional<ConceptRecord> previous = store.findByIdForUpdate(tenantId, request.conceptName(), request.id());
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
        enforceFieldWriteAccess(requestContext, "CONCEPT_WRITE");

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

        // REG-48 (delete-side twin of REG-41/LNCH13-F2): authorization must run BEFORE any semantic
        // evaluation that touches the previous record's data (evaluateRuleProfiles's concept
        // invariants, here) -- otherwise a caller with no delete permission or no row-scope access
        // can learn something true about the row's current state from an invariant-rejection detail
        // thrown before authorization ever ran. The previous-record FETCH above stays (enforceRowWritable
        // needs it); only the semantic-validation USE of that data must wait until authorization passes.
        enforcePermission(effectiveContext, "concept.delete", request.conceptName(), "CONCEPT_DELETE", request.id());
        enforceRowWritable(requestContext, "CONCEPT_DELETE");

        ConceptSemanticDecision decision = evaluateRuleProfiles(
                requestContext,
                ruleProfilesForWriteBeforeCommit(effectiveContext)
        );

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
     * REG-120: {@link ConceptGateway#authorizeCreate}'s implementation -- the same permission +
     * row-level + BEFORE_COMMIT rule-profile enforcement {@link #saveWithinTransaction} runs, minus
     * the {@code store.save(...)} call and the AFTER_COMMIT profile evaluation (which needs an
     * actually-persisted record to run against, and there is deliberately none here). There is no
     * previous record for a create, so {@code previousRecord} is always {@link Optional#empty()} --
     * exactly what {@code store.findByIdForUpdate} already returns for a not-yet-existing id in
     * {@link #saveWithinTransaction}, so this matches how a genuine create looks to the rest of this
     * pipeline today.
     */
    @Override
    public void authorizeCreate(ConceptWriteRequest request, ExecutionContext context) {
        ExecutionContext effectiveContext = normalizeContext(context);
        String tenantId = enforceTenant(
                request.tenantId(), effectiveContext, "CONCEPT_WRITE", request.conceptName(), request.id());
        ConceptGatewayRequestContext requestContext = requestContext(
                ConceptGatewayOperation.SAVE,
                request.conceptName(),
                request.id(),
                tenantId,
                request.data(),
                effectiveContext,
                Optional.empty()
        );
        enforcePermission(effectiveContext, "concept.write", request.conceptName(), "CONCEPT_WRITE", request.id());
        enforceRowWritable(requestContext, "CONCEPT_WRITE");
        enforceFieldWriteAccess(requestContext, "CONCEPT_WRITE");
        ConceptSemanticDecision decision = runWriteSemantics(
                requestContext,
                ruleProfilesForWriteBeforeCommit(effectiveContext)
        );
        audit(effectiveContext, "CONCEPT_WRITE", request.conceptName(), request.id(), "SUCCESS", "authorized_no_persist", tenantId);
        trace(requestContext, "SUCCESS", "authorized_no_persist", decision);
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

    /**
     * R5.5: field-level write scoping -- {@link ConceptGatewaySemanticPolicy#deniedWriteFields}
     * against the request's incoming data, evaluated right alongside {@link #enforceRowWritable}
     * (same position: an authorization gate, run before any semantic validation touches the
     * previous record's data, per REG-41). A denial rejects the WHOLE write -- there is no
     * per-field "drop the denied field and keep going" path, matching {@code
     * ConceptGatewaySemanticPolicy#deniedWriteFields}'s own javadoc: silently dropping a field
     * would let the caller believe their write fully succeeded when it didn't. The thrown message
     * names the denied field(s) -- safe to disclose since the caller supplied that field name
     * themselves in the request body; it never includes the field's previous or attempted value.
     */
    private void enforceFieldWriteAccess(ConceptGatewayRequestContext requestContext, String action) {
        List<String> deniedFields = semanticPolicy.deniedWriteFields(requestContext);
        if (deniedFields.isEmpty()) {
            return;
        }
        ExecutionContext context = requestContext.executionContext();
        String message = "Concept Gateway denied field-level write access for concept "
                + requestContext.conceptName() + ", field(s): " + String.join(", ", deniedFields);
        audit(context, action, requestContext.conceptName(), requestContext.id(), "DENIED", "field_scope_denied", requestContext.tenantId());
        trace(
                requestContext,
                "DENIED",
                "field_scope_denied",
                ConceptSemanticDecision.deny("FIELD_SCOPE_DENIED", message)
        );
        throw new ConceptGatewayAccessDeniedException("FIELD_SCOPE_DENIED", message);
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
