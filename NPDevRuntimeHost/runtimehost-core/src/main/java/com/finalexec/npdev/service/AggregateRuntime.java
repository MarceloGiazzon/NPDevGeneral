package com.finalexec.npdev.service;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledAggregate;
import com.npdev.dsl.v1.compiled.CompiledAggregateBalance;
import com.npdev.dsl.v1.compiled.CompiledAggregateCollection;
import com.npdev.dsl.v1.compiled.CompiledAggregateCollectionLookupField;
import com.npdev.dsl.v1.compiled.CompiledAggregateInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryPredicateCompiler;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.procedures.ProcedureExecutionResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.UUID;

/**
 * Loads and commits a declared aggregate as a nested tree: the root record plus
 * every owned child collection, recursively, joined by each collection's
 * {@code childField}. Part of the Aggregate Workbench (ADR-0004 / ADR-0005).
 */
@Service
public class AggregateRuntime {

    // REG-208 (B28 lift): a live ModelHolder, not a directly-injected CompiledModel snapshot -- see
    // aggregateDefinition(), the only reader below, which does a fresh per-call lookup.
    private final ModelHolder modelHolder;
    private final ConceptGateway conceptGateway;
    private final ProcedureRunner procedureRunner;
    private final TransactionTemplate transactionTemplate;
    // R4.4: optional, and resolved LAZILY rather than in the constructor like the four
    // collaborators above. The InvariantEngine bean is built from CompiledModel, so calling
    // getIfAvailable() during construction forces that whole chain to be instantiated at context
    // startup for every app -- including surface profiles that never commit an aggregate. Resolving
    // on first use instead keeps this class's startup cost exactly what it was before R4.4, and
    // absent an engine entirely (no bean, or a direct constructor call that predates this field)
    // declared invariants are simply not evaluated -- today's behaviour for every existing caller.
    private final Supplier<InvariantEngine> invariantEngine;

    @Autowired
    public AggregateRuntime(
            ModelHolder modelHolder,
            ObjectProvider<ConceptGateway> conceptGateway,
            ObjectProvider<ProcedureRunner> procedureRunner,
            ObjectProvider<PlatformTransactionManager> transactionManager,
            ObjectProvider<InvariantEngine> invariantEngine
    ) {
        this(
                modelHolder,
                conceptGateway == null ? null : conceptGateway.getIfAvailable(),
                procedureRunner == null ? null : procedureRunner.getIfAvailable(),
                transactionManager == null ? null : transactionManager.getIfAvailable(),
                invariantEngine == null ? () -> null : invariantEngine::getIfAvailable
        );
    }

    public AggregateRuntime(CompiledModel compiledModel, ConceptGateway conceptGateway) {
        this(compiledModel, conceptGateway, null, null);
    }

    public AggregateRuntime(CompiledModel compiledModel, ConceptGateway conceptGateway, ProcedureRunner procedureRunner) {
        this(compiledModel, conceptGateway, procedureRunner, null);
    }

    /**
     * G1 (docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md, REG-72): {@code transactionManager} wraps
     * {@link #commit} in a real transaction when one is available, so the root upsert, every
     * recursive child upsert, and every reconcile-delete either all land or none do. A
     * {@code TransactionTemplate} (not {@code @Transactional}) on purpose: this class is
     * routinely constructed directly (every test above, and any future non-Spring caller), which
     * silently defeats an annotation-driven AOP proxy but not an explicitly-invoked template.
     */
    public AggregateRuntime(
            CompiledModel compiledModel,
            ConceptGateway conceptGateway,
            ProcedureRunner procedureRunner,
            PlatformTransactionManager transactionManager
    ) {
        // Cast required: the two five-argument constructors below differ only in their last
        // parameter (InvariantEngine vs the private Supplier form), and a bare null matches both.
        this(compiledModel, conceptGateway, procedureRunner, transactionManager, (InvariantEngine) null);
    }

    /**
     * R4.4: adds the {@link InvariantEngine} that evaluates a declared aggregate's
     * {@code invariants[]} in {@link #commit}'s pre-commit slot. Kept as a widened constructor with
     * the four-argument form above delegating to it, so every existing direct caller -- the tests
     * in this package and any non-Spring caller -- compiles and behaves unchanged.
     */
    public AggregateRuntime(
            CompiledModel compiledModel,
            ConceptGateway conceptGateway,
            ProcedureRunner procedureRunner,
            PlatformTransactionManager transactionManager,
            InvariantEngine invariantEngine
    ) {
        this(compiledModel, conceptGateway, procedureRunner, transactionManager,
                () -> invariantEngine);
    }

    /** Convenience overload for the existing (CompiledModel, ...) constructors -- wraps in a
     * non-reloading holder. Production wiring (the {@code @Autowired} constructor above) uses a
     * real {@link ModelHolder} directly. */
    private AggregateRuntime(
            CompiledModel compiledModel,
            ConceptGateway conceptGateway,
            ProcedureRunner procedureRunner,
            PlatformTransactionManager transactionManager,
            Supplier<InvariantEngine> invariantEngine
    ) {
        this(new ModelHolder(compiledModel), conceptGateway, procedureRunner, transactionManager, invariantEngine);
    }

    private AggregateRuntime(
            ModelHolder modelHolder,
            ConceptGateway conceptGateway,
            ProcedureRunner procedureRunner,
            PlatformTransactionManager transactionManager,
            Supplier<InvariantEngine> invariantEngine
    ) {
        this.modelHolder = modelHolder;
        this.conceptGateway = conceptGateway;
        this.procedureRunner = procedureRunner;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.invariantEngine = invariantEngine;
    }

    /**
     * Load the aggregate named {@code aggregateName} rooted at {@code rootId} into a nested map:
     * {@code {aggregate, id, <root fields...>, <collectionName>: [ {id, ...fields, <nested>...} ] } }.
     *
     * @throws IllegalArgumentException if the aggregate is unknown or the root record is not found
     * @throws IllegalStateException    if no ConceptGateway is available
     */
    public Map<String, Object> load(String aggregateName, String rootId, ExecutionContext context) {
        CompiledAggregate aggregate = findAggregate(aggregateName);
        ExecutionContext effectiveContext = context == null ? ExecutionContext.anonymous() : context;
        ConceptGateway gateway = requireConceptGateway();

        Optional<ConceptRecord> root = gateway.read(
                new ConceptReadRequest(aggregate.root(), rootId, null), effectiveContext);
        if (root.isEmpty()) {
            throw new IllegalArgumentException(
                    "Aggregate " + aggregate.name() + " root " + aggregate.root()
                            + " not found for id: " + rootId);
        }

        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("aggregate", aggregate.name());
        putRecord(tree, root.get());
        // Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): every declared lookupFields[] query is run
        // ONCE per load (not once per row) -- see runLookupQueries's javadoc for why a per-row
        // parameterized query is not something the platform's query-execution path supports today.
        Map<String, List<ConceptRecord>> lookupQueryResults =
                runLookupQueries(aggregate.collections(), gateway, effectiveContext);
        for (CompiledAggregateCollection collection : aggregate.collections()) {
            tree.put(collection.name(),
                    loadCollection(collection, rootId, gateway, effectiveContext, lookupQueryResults));
        }
        return tree;
    }

    /**
     * Commit a draft aggregate tree: upsert the root and every owned child (assigning each child's
     * {@code childField} to its parent id), and delete any currently-persisted child no longer present
     * in the draft (reconcile, cascading down the tree). New rows without an {@code id} are assigned one.
     * Returns the freshly re-loaded tree.
     *
     * <p>G1 (REG-72): the root upsert, every recursive child upsert, and every reconcile-delete run
     * inside a single transaction when a {@link PlatformTransactionManager} was supplied -- a commit
     * that fails partway (e.g. the last child write throws) leaves every prior write in this call
     * rolled back, including any reconcile-delete already issued, instead of a half-written aggregate
     * with unrecoverably deleted rows. With no transaction manager (e.g. an in-proc store that can't
     * participate in one), this degrades to the original, non-atomic behavior -- unchanged for every
     * existing caller.
     *
     * @throws IllegalArgumentException if the aggregate is unknown
     * @throws IllegalStateException    if no ConceptGateway is available
     */
    public Map<String, Object> commit(String aggregateName, Map<String, Object> draft, ExecutionContext context) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status -> commitInternal(aggregateName, draft, context));
        }
        return commitInternal(aggregateName, draft, context);
    }

    private Map<String, Object> commitInternal(String aggregateName, Map<String, Object> draft, ExecutionContext context) {
        CompiledAggregate aggregate = findAggregate(aggregateName);
        ExecutionContext ctx = context == null ? ExecutionContext.anonymous() : context;
        ConceptGateway gateway = requireConceptGateway();
        Map<String, Object> rootDraft = draft == null ? Map.of() : draft;

        // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3B / Gap 8): a declared onValidate
        // procedure runs here, BEFORE the root upsert (and every recursive child upsert) -- a
        // sibling of onCommit above, not a flag on it: different timing (before any write exists
        // to roll back, rather than after the whole tree is already written), different contract
        // (a server-side guard that rejects a proposed draft outright, vs. a post-commit side
        // effect). Receives the DRAFT as-is (the proposed tree, not yet persisted), unlike
        // onCommit which receives the freshly reloaded, already-written tree.
        if (aggregate.onValidate() != null && procedureRunner != null) {
            ProcedureExecutionResult onValidateResult = procedureRunner.execute(aggregate.onValidate(), rootDraft, ctx);
            if (!onValidateResult.ok()) {
                throw new IllegalStateException(
                        "Aggregate " + aggregate.name() + " onValidate procedure " + aggregate.onValidate()
                                + " failed: " + onValidateResult.failureCode() + " " + onValidateResult.failureMessage());
            }
        }

        // R4.4: declared invariants[] evaluate in this SAME pre-commit slot, right after onValidate
        // and still before the root upsert -- so a veto happens while there is nothing written to
        // roll back, and inside commit()'s transaction when one exists either way. Throwing
        // IllegalStateException (rather than returning a failure) is deliberate and matches the
        // onValidate branch directly above: AggregateApiController already catches that exact type
        // and surfaces its message, so the failing rule's NAME reaches the commit API error without
        // any controller change. Every failing rule is named, not just the first -- an author
        // fixing a draft should see all of them in one round trip.
        assertAggregateInvariants(aggregate, rootDraft);

        // Session 1: declared balances[] evaluate in this SAME pre-commit slot, right after
        // invariants -- the server-side truth backstop behind the on-demand checkBalances path
        // below (checklist S6: "an unbalanced document cannot be saved, and says why before the
        // attempt" -- the client already refuses via checkBalances, but the server never trusts it).
        assertAggregateBalances(aggregate, rootDraft);

        String rootId = idOrNew(rootDraft.get("id"));
        Set<String> rootCollectionKeys = collectionNames(aggregate.collections());
        Map<String, Object> rootFields = scalarFields(rootDraft, rootCollectionKeys, null, null);
        rootFields.put("id", rootId); // the gateway requires the id field present in the write payload
        gateway.save(new ConceptWriteRequest(aggregate.root(), rootId, ctx.tenantId(), rootFields), ctx);

        commitCollections(aggregate.collections(), rootDraft, rootId, gateway, ctx);
        Map<String, Object> reloaded = load(aggregate.name(), rootId, ctx);

        // Move 4 (docs/MOVE4_CROSS_RECORD_WRITE_PLAN.md): a declared onCommit procedure runs here,
        // AFTER the tree is written but still INSIDE this method's caller's transaction (commit()
        // wraps commitInternal in transactionTemplate when one is available) -- so a side-effect
        // write this procedure makes to a SIBLING concept (e.g. syncing a stock ledger) either lands
        // together with the aggregate tree or rolls back together with it. Throwing (not returning a
        // failure) is deliberate: it propagates out through the transaction boundary and rolls back
        // every prior write in this commit, root included. This is only safe because G1 (REG-72)
        // landed first -- on the pre-G1 code this hook would have silently half-committed.
        if (aggregate.onCommit() != null && procedureRunner != null) {
            ProcedureExecutionResult onCommitResult = procedureRunner.execute(aggregate.onCommit(), reloaded, ctx);
            if (!onCommitResult.ok()) {
                throw new IllegalStateException(
                        "Aggregate " + aggregate.name() + " onCommit procedure " + aggregate.onCommit()
                                + " failed: " + onCommitResult.failureCode() + " " + onCommitResult.failureMessage());
            }
        }
        return reloaded;
    }

    /**
     * Invoke a declared procedure over an in-flight aggregate draft and return the patched draft
     * (procedure-over-aggregate, e.g. "Gerar Demanda"/recompute). The draft is passed as the procedure
     * input; the procedure's resulting state — its top-level fields plus any step targets, minus the
     * internal {@code input} echo — is returned as the new draft. This does NOT persist: the client
     * re-renders the returned draft and the user commits (or discards) it explicitly.
     *
     * @throws IllegalArgumentException if the aggregate or procedure is unknown
     * @throws IllegalStateException    if no ProcedureRunner is wired or the procedure fails
     */
    public Map<String, Object> invoke(
            String aggregateName, String procedureName, Map<String, Object> draft, ExecutionContext context) {
        findAggregate(aggregateName); // validate the aggregate exists before touching the procedure
        ExecutionContext ctx = context == null ? ExecutionContext.anonymous() : context;
        if (procedureRunner == null) {
            throw new IllegalStateException("No ProcedureRunner is available to invoke procedures over aggregates.");
        }
        if (!procedureRunner.hasProcedure(procedureName)) {
            throw new IllegalArgumentException("Procedure not found: " + procedureName);
        }
        ProcedureExecutionResult result =
                procedureRunner.execute(procedureName, draft == null ? Map.of() : draft, ctx);
        if (!result.ok()) {
            throw new IllegalStateException(
                    "Procedure " + procedureName + " failed: "
                            + result.failureCode() + " " + result.failureMessage());
        }
        Map<String, Object> patched = new LinkedHashMap<>(result.state());
        patched.remove("input"); // drop the executor's echo of the initial input
        return patched;
    }

    /**
     * Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): the on-demand "Recalcular Saldos" affordance --
     * evaluates the named {@code balances[]} rules against {@code draft} (the client's full
     * in-progress, unsaved tree, exactly as {@link #invoke} already receives it) and returns the
     * draft merged with a {@code __balances} report, WITHOUT invoking any procedure and WITHOUT
     * persisting anything -- same "does not persist" contract {@link #invoke} documents. A rule name
     * that does not resolve is skipped rather than thrown (model validation, not runtime request
     * handling, is where an unresolvable {@code checkBalances} name is refused -- see
     * {@code PanelValidation#validateWorkbenchActions}).
     *
     * @throws IllegalArgumentException if the aggregate is unknown
     */
    public Map<String, Object> checkBalances(String aggregateName, List<String> balanceNames, Map<String, Object> draft) {
        CompiledAggregate aggregate = findAggregate(aggregateName);
        Map<String, Object> rootDraft = draft == null ? new LinkedHashMap<>() : new LinkedHashMap<>(draft);
        Map<String, Object> report = new LinkedHashMap<>();
        for (String balanceName : balanceNames == null ? List.<String>of() : balanceNames) {
            CompiledAggregateBalance balance = aggregate.balances().stream()
                    .filter(candidate -> candidate.name() != null && candidate.name().equalsIgnoreCase(balanceName))
                    .findFirst()
                    .orElse(null);
            if (balance == null) {
                continue;
            }
            List<BalanceEvaluator.GroupResult> results = BalanceEvaluator.evaluate(balance, rootDraft);
            boolean balanced = results.stream().allMatch(BalanceEvaluator.GroupResult::balanced);
            List<Map<String, Object>> groups = new ArrayList<>();
            for (BalanceEvaluator.GroupResult result : results) {
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("group", result.groupKey());
                group.put("leftTotal", result.leftTotal());
                group.put("rightTotal", result.rightTotal());
                group.put("delta", result.delta());
                group.put("balanced", result.balanced());
                group.put("message", result.message());
                groups.add(group);
            }
            Map<String, Object> ruleReport = new LinkedHashMap<>();
            ruleReport.put("balanced", balanced);
            ruleReport.put("groups", groups);
            report.put(balance.name(), ruleReport);
        }
        rootDraft.put("__balances", report);
        return rootDraft;
    }

    /**
     * Session 1: throws naming every unbalanced group's message if any of the aggregate's declared
     * balances[] rules has a group out of balance -- the same shape {@link #assertAggregateInvariants}
     * already throws, evaluated in the same pre-commit slot.
     */
    private void assertAggregateBalances(CompiledAggregate aggregate, Map<String, Object> rootDraft) {
        if (aggregate.balances().isEmpty()) {
            return;
        }
        List<String> violations = new ArrayList<>();
        for (CompiledAggregateBalance balance : aggregate.balances()) {
            for (BalanceEvaluator.GroupResult result : BalanceEvaluator.evaluate(balance, rootDraft)) {
                if (!result.balanced()) {
                    violations.add(result.message());
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Aggregate " + aggregate.name() + " balance(s) violated: " + String.join("; ", violations));
        }
    }

    /**
     * Session 1: runs every distinct {@code lookupFields[].query} declared anywhere in the
     * aggregate's collection tree exactly ONCE (not once per row, and not once per nested-collection
     * call, even though {@link #loadCollection} recurses once per parent row for a depth-2
     * collection) -- the query is unparameterized (no runtime query-parameter binding exists
     * anywhere in the platform's query-execution path, {@code ConceptQueryPredicateCompiler
     * .compileToConceptQueryFilters(query.where())}, the same one a procedure's {@code runQuery}
     * step uses), so every row of a given query's result is identical regardless of which draft row
     * is asking. Returns raw {@link ConceptRecord}s, keyed by normalized query name; {@link
     * #loadCollection} joins them to rows in memory by each lookup field's own {@code joinField}.
     */
    private Map<String, List<ConceptRecord>> runLookupQueries(
            List<CompiledAggregateCollection> collections, ConceptGateway gateway, ExecutionContext context) {
        Set<String> queryNames = new LinkedHashSet<>();
        collectLookupQueryNames(collections, queryNames);
        if (queryNames.isEmpty()) {
            return Map.of();
        }
        CompiledModel model = modelHolder.get();
        List<CompiledQuery> declaredQueries = model == null ? List.of() : model.getQueries();
        Map<String, List<ConceptRecord>> out = new LinkedHashMap<>();
        for (String queryName : queryNames) {
            CompiledQuery query = declaredQueries.stream()
                    .filter(candidate -> candidate.name() != null && normalize(candidate.name()).equals(queryName))
                    .findFirst()
                    .orElse(null);
            if (query == null) {
                out.put(queryName, List.of());
                continue;
            }
            List<ConceptQuery.Filter> filters;
            try {
                filters = ConceptQueryPredicateCompiler.compileToConceptQueryFilters(query.where());
            } catch (ConceptQueryPredicateCompiler.UnsupportedPredicateException unsupported) {
                out.put(queryName, List.of());
                continue;
            }
            List<ConceptQuery.Sort> sorts = ConceptQueryPredicateCompiler.compileOrderBy(query.orderBy());
            int limit = query.limit() != null && query.limit() > 0 ? query.limit() : ConceptQuery.MAX_LIMIT;
            ConceptPage page = gateway.query(
                    new ConceptQueryRequest(query.concept(), new ConceptQuery(filters, sorts, 0, limit)), context);
            out.put(queryName, page.items());
        }
        return out;
    }

    private static void collectLookupQueryNames(
            List<CompiledAggregateCollection> collections, Set<String> out) {
        for (CompiledAggregateCollection collection : collections) {
            for (CompiledAggregateCollectionLookupField lookupField : collection.lookupFields()) {
                if (lookupField.query() != null) {
                    out.add(normalize(lookupField.query()));
                }
            }
            collectLookupQueryNames(collection.collections(), out);
        }
    }

    private void commitCollections(
            List<CompiledAggregateCollection> collections,
            Map<String, Object> parentDraft,
            String parentId,
            ConceptGateway gateway,
            ExecutionContext ctx
    ) {
        for (CompiledAggregateCollection collection : collections) {
            List<Map<String, Object>> draftRows = asRowList(parentDraft.get(collection.name()));
            Set<String> grandKeys = collectionNames(collection.collections());
            // Session 1: a declared lookupField is attached at load time and never persisted --
            // exclude it from the write payload the same way a nested collection's own key is.
            for (CompiledAggregateCollectionLookupField lookupField : collection.lookupFields()) {
                if (lookupField.name() != null) {
                    grandKeys.add(normalize(lookupField.name()));
                }
            }
            Set<String> keptIds = new LinkedHashSet<>();
            for (Map<String, Object> row : draftRows) {
                String childId = idOrNew(row.get("id"));
                keptIds.add(childId);
                Map<String, Object> fields = scalarFields(row, grandKeys, collection.childField(), parentId);
                fields.put("id", childId); // the gateway requires the id field present in the write payload
                gateway.save(new ConceptWriteRequest(collection.concept(), childId, ctx.tenantId(), fields), ctx);
                commitCollections(collection.collections(), row, childId, gateway, ctx);
            }
            // Reconcile: delete persisted children of this parent that are absent from the draft.
            List<ConceptRecord> current = gateway.list(
                    new ConceptListRequest(collection.concept(), null, collection.childField(), parentId), ctx);
            for (ConceptRecord existing : current) {
                if (!keptIds.contains(existing.id())) {
                    gateway.delete(new ConceptReadRequest(collection.concept(), existing.id(), null), ctx);
                }
            }
        }
    }

    /** The row's scalar fields to persist: drop id/aggregate/child-collection keys; set childField=parentId. */
    private static Map<String, Object> scalarFields(
            Map<String, Object> row, Set<String> collectionKeys, String childField, String parentId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key.equals("id") || key.equals("aggregate") || key.equals("__children")
                    || collectionKeys.contains(normalize(key))) {
                continue;
            }
            fields.put(key, entry.getValue());
        }
        if (childField != null && !childField.isBlank()) {
            fields.put(childField, parentId);
        }
        return fields;
    }

    private static Set<String> collectionNames(List<CompiledAggregateCollection> collections) {
        Set<String> names = new LinkedHashSet<>();
        for (CompiledAggregateCollection collection : collections) {
            names.add(normalize(collection.name()));
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRowList(Object value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    rows.add((Map<String, Object>) map);
                }
            }
        }
        return rows;
    }

    private static String idOrNew(Object value) {
        String id = value == null ? null : String.valueOf(value);
        return (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<Map<String, Object>> loadCollection(
            CompiledAggregateCollection collection,
            String parentId,
            ConceptGateway gateway,
            ExecutionContext context,
            Map<String, List<ConceptRecord>> lookupQueryResults
    ) {
        List<ConceptRecord> children = gateway.list(
                new ConceptListRequest(collection.concept(), null, collection.childField(), parentId),
                context);
        // Session 1: one join index per declared lookupField, built once for this collection call
        // (not once per row) from the query results already fetched by runLookupQueries.
        List<CompiledAggregateCollectionLookupField> lookupFields = collection.lookupFields();
        List<Map<String, Object>> lookupIndexes = new ArrayList<>(lookupFields.size());
        for (CompiledAggregateCollectionLookupField lookupField : lookupFields) {
            lookupIndexes.add(buildLookupIndex(
                    lookupQueryResults.getOrDefault(normalize(lookupField.query()), List.of()), lookupField));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConceptRecord child : children) {
            Map<String, Object> row = new LinkedHashMap<>();
            putRecord(row, child);
            for (int i = 0; i < lookupFields.size(); i++) {
                CompiledAggregateCollectionLookupField lookupField = lookupFields.get(i);
                Object joinValue = row.get(lookupField.joinField());
                // Never persisted -- scalarFields() excludes every declared lookupField name from
                // the commit payload the same way __children/collection keys already are.
                row.put(lookupField.name(),
                        joinValue == null ? null : lookupIndexes.get(i).get(String.valueOf(joinValue)));
            }
            for (CompiledAggregateCollection nested : collection.collections()) {
                row.put(nested.name(), loadCollection(nested, child.id(), gateway, context, lookupQueryResults));
            }
            rows.add(row);
        }
        return rows;
    }

    /** {@code joinField} value (from each fetched query row) -> {@code valueField} value, so
     *  attaching a lookup to a draft row is an O(1) map lookup with zero extra queries. */
    private static Map<String, Object> buildLookupIndex(
            List<ConceptRecord> queryRows, CompiledAggregateCollectionLookupField lookupField) {
        Map<String, Object> index = new LinkedHashMap<>();
        for (ConceptRecord queryRow : queryRows) {
            Object joinValue = fieldValue(queryRow, lookupField.joinField());
            if (joinValue != null) {
                index.put(String.valueOf(joinValue), fieldValue(queryRow, lookupField.valueField()));
            }
        }
        return index;
    }

    private static Object fieldValue(ConceptRecord record, String fieldName) {
        return "id".equalsIgnoreCase(fieldName) ? record.id() : record.data().get(fieldName);
    }

    /** Flatten a record into the row map: id at the top, then its data fields. */
    private static void putRecord(Map<String, Object> target, ConceptRecord record) {
        target.put("id", record.id());
        target.putAll(record.data());
    }

    private void assertAggregateInvariants(CompiledAggregate aggregate, Map<String, Object> rootDraft) {
        if (aggregate.invariants().isEmpty()) {
            return;
        }
        InvariantEngine engine = invariantEngine == null ? null : invariantEngine.get();
        if (engine == null) {
            return;
        }
        List<InvariantEngine.AggregateInvariantSpec> specs = new ArrayList<>();
        for (CompiledAggregateInvariant invariant : aggregate.invariants()) {
            specs.add(new InvariantEngine.AggregateInvariantSpec(
                    invariant.name(), invariant.expression(), invariant.message()));
        }
        List<InvariantEngine.Violation> violations =
                engine.evaluateAggregateInvariants(aggregate.name(), aggregate.root(), specs, rootDraft);
        if (violations == null || violations.isEmpty()) {
            return;
        }
        StringBuilder names = new StringBuilder();
        StringBuilder details = new StringBuilder();
        for (InvariantEngine.Violation violation : violations) {
            if (names.length() > 0) {
                names.append(", ");
                details.append("; ");
            }
            names.append(violation.invariantRef());
            details.append(violation.message());
        }
        throw new IllegalStateException(
                "Aggregate " + aggregate.name() + " invariant(s) violated: " + names + " -- " + details);
    }

    private CompiledAggregate findAggregate(String aggregateName) {
        if (aggregateName == null || aggregateName.isBlank()) {
            throw new IllegalArgumentException("aggregate name is required");
        }
        CompiledModel model = modelHolder.get();
        if (model == null) {
            throw new IllegalStateException("Compiled model is not configured.");
        }
        String normalized = aggregateName.trim().toLowerCase(Locale.ROOT);
        return model.getAggregates().stream()
                .filter(aggregate -> aggregate.name() != null
                        && aggregate.name().trim().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Aggregate not found: " + aggregateName));
    }

    private ConceptGateway requireConceptGateway() {
        if (conceptGateway == null) {
            throw new IllegalStateException("ConceptGateway is required for aggregate data.");
        }
        return conceptGateway;
    }
}
