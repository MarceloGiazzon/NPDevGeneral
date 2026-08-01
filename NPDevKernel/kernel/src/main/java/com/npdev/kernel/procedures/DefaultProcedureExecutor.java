package com.npdev.kernel.procedures;

import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
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
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.IdProvider;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DefaultProcedureExecutor implements ProcedureExecutor {
    /** LIFT-QUERY-P1: soft cap applied to a {@code runQuery} step's results when the query
     * declares no explicit {@code limit}, so a capability is never handed an unbounded list. */
    private static final int DEFAULT_QUERY_ROW_CAP = 1_000;

    private final ConceptGateway conceptGateway;
    private final CapabilityDispatcher capabilityDispatcher;
    private final EventBus eventBus;
    private final Map<String, ProcedureDefinition> procedureRegistry;
    private final ProcedureExecutionLimits limits;
    private final Map<String, CompiledQuery> queriesByName;
    private final IdProvider idProvider;

    public DefaultProcedureExecutor(
            ConceptGateway conceptGateway,
            CapabilityDispatcher capabilityDispatcher,
            EventBus eventBus
    ) {
        this(conceptGateway, capabilityDispatcher, eventBus, Map.of());
    }

    public DefaultProcedureExecutor(
            ConceptGateway conceptGateway,
            CapabilityDispatcher capabilityDispatcher,
            EventBus eventBus,
            Map<String, ProcedureDefinition> procedureRegistry
    ) {
        this(conceptGateway, capabilityDispatcher, eventBus, procedureRegistry, ProcedureExecutionLimits.defaults());
    }

    public DefaultProcedureExecutor(
            ConceptGateway conceptGateway,
            CapabilityDispatcher capabilityDispatcher,
            EventBus eventBus,
            Map<String, ProcedureDefinition> procedureRegistry,
            ProcedureExecutionLimits limits
    ) {
        this(conceptGateway, capabilityDispatcher, eventBus, procedureRegistry, limits, Map.of());
    }

    /** LIFT-QUERY-P1: {@code queriesByName} (keyed case-insensitively) lets {@code runQuery} steps
     * honor their declared query's {@code where}/{@code orderBy}/{@code limit} instead of always
     * returning every row for the concept. Defaults to empty so existing callers compile unchanged
     * -- a {@code runQuery} step referencing a name absent from this map still runs, just unfiltered
     * (its prior behavior), rather than failing. */
    public DefaultProcedureExecutor(
            ConceptGateway conceptGateway,
            CapabilityDispatcher capabilityDispatcher,
            EventBus eventBus,
            Map<String, ProcedureDefinition> procedureRegistry,
            ProcedureExecutionLimits limits,
            Map<String, CompiledQuery> queriesByName
    ) {
        this(conceptGateway, capabilityDispatcher, eventBus, procedureRegistry, limits, queriesByName, null);
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1B): {@code idProvider} backs
     * {@code saveConcept}'s blank-idRef fallback and {@code patchConcept}'s {@code createIfMissing}
     * -- the create-with-generated-id half REG-77 found missing, using the same port
     * {@code KernelRunner} already has ({@link IdProvider#uuid()} by default so no existing caller
     * needs to change).
     */
    public DefaultProcedureExecutor(
            ConceptGateway conceptGateway,
            CapabilityDispatcher capabilityDispatcher,
            EventBus eventBus,
            Map<String, ProcedureDefinition> procedureRegistry,
            ProcedureExecutionLimits limits,
            Map<String, CompiledQuery> queriesByName,
            IdProvider idProvider
    ) {
        this.conceptGateway = Objects.requireNonNull(conceptGateway, "conceptGateway");
        this.capabilityDispatcher = Objects.requireNonNull(capabilityDispatcher, "capabilityDispatcher");
        this.eventBus = eventBus == null ? event -> { } : eventBus;
        this.procedureRegistry = procedureRegistry == null ? Map.of() : Map.copyOf(procedureRegistry);
        this.limits = limits == null ? ProcedureExecutionLimits.defaults() : limits;
        Map<String, CompiledQuery> normalizedQueries = new java.util.LinkedHashMap<>();
        if (queriesByName != null) {
            for (Map.Entry<String, CompiledQuery> entry : queriesByName.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    normalizedQueries.put(entry.getKey().trim().toLowerCase(java.util.Locale.ROOT), entry.getValue());
                }
            }
        }
        this.queriesByName = Map.copyOf(normalizedQueries);
        this.idProvider = idProvider == null ? IdProvider.uuid() : idProvider;
    }

    @Override
    public ProcedureExecutionResult execute(
            ProcedureDefinition definition,
            Map<String, Object> input,
            ExecutionContext context
    ) {
        return executeDefinition(definition, input, context, new ProcedureExecutionScope(limits), 1);
    }

    private ProcedureExecutionResult executeDefinition(
            ProcedureDefinition definition,
            Map<String, Object> input,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        Objects.requireNonNull(definition, "definition");
        ExecutionContext effectiveContext = context == null ? ExecutionContext.anonymous() : context;
        Map<String, Object> state = new LinkedHashMap<>();
        if (input != null) {
            state.putAll(input);
        }
        state.putIfAbsent("input", input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input)));

        List<ProcedureStepResult> stepResults = new ArrayList<>();
        for (int index = 0; index < definition.steps().size(); index++) {
            ProcedureStep step = definition.steps().get(index);
            ProcedureStepResult result = executeStepWithBudget(
                    definition,
                    step,
                    index,
                    state,
                    effectiveContext,
                    scope,
                    recursionDepth
            );
            stepResults.add(result);
            if (!result.ok()) {
                return ProcedureExecutionResult.failure(state, stepResults, result.code(), result.message());
            }
            if (step.type() == ProcedureStepType.RETURN) {
                return ProcedureExecutionResult.success(state, stepResults);
            }
        }
        return ProcedureExecutionResult.success(state, stepResults);
    }

    private ProcedureStepResult executeStepWithBudget(
            ProcedureDefinition definition,
            ProcedureStep step,
            int stepIndex,
            Map<String, Object> state,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        ProcedureStepResult budget = scope.beforeStep(step);
        if (!budget.ok()) {
            return budget;
        }
        try {
            return executeStep(definition, step, stepIndex, state, context, scope, recursionDepth);
        } catch (UnresolvableReferenceException exception) {
            // X0-6 (REG-100): named separately from the generic catch below so callers can branch on
            // "REF_UNRESOLVABLE" specifically, the same way QUERY_NOT_FOUND (X0-7) is named rather
            // than folded into a generic step failure.
            return ProcedureStepResult.failure(step, "REF_UNRESOLVABLE", exception.getMessage());
        } catch (RuntimeException exception) {
            return ProcedureStepResult.failure(
                    step,
                    "PROCEDURE_STEP_FAILED",
                    exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage()
            );
        }
    }

    private ProcedureStepResult executeStep(
            ProcedureDefinition definition,
            ProcedureStep step,
            int stepIndex,
            Map<String, Object> state,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        return switch (step.type()) {
            case SAVE_CONCEPT -> saveConcept(step, state, context);
            case PATCH_CONCEPT -> patchConcept(step, state, context);
            case READ_CONCEPT -> readConcept(step, state, context);
            case LIST_CONCEPTS -> listConcepts(step, state, context);
            case RUN_QUERY -> runQuery(step, state, context);
            case DELETE_CONCEPT -> deleteConcept(step, state, context);
            case CALL_CAPABILITY -> callCapability(step, state, context);
            case PUBLISH_EVENT -> publishEvent(definition, step, stepIndex, state, context);
            case CALL_PROCEDURE -> callProcedure(step, state, context, scope, recursionDepth);
            case IF -> ifThenElse(definition, step, stepIndex, state, context, scope, recursionDepth);
            case FOR_EACH -> forEach(definition, step, stepIndex, state, context, scope, recursionDepth);
            case MAP_LIST -> mapList(step, state);
            case MAP_VALUE -> mapValue(step, state);
            case COMPUTE_VALUE -> computeValue(step, state);
            case RETURN -> returnValue(step, state);
        };
    }

    private ProcedureStepResult saveConcept(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        String id = resolveOrGenerateId(state, step.idRef(), step.conceptName());
        Map<String, Object> data = requireMap(state, step.dataRef(), step.name(), "dataRef");
        // Found live (Move 5, final item / REG-78 investigation): a real governed ConceptGateway's
        // semantic policy (ConfiguredConceptGatewaySemanticPolicy) requires every declared required
        // field to be present IN THE DATA MAP, including "id" -- but resolveOrGenerateId's fallback
        // id was never folded back into data, only passed as the write request's separate id
        // parameter. Every dataRef that doesn't already carry an "id" key (the normal case for a
        // fresh record, e.g. a client payload with no id yet) was silently CONCEPT_FIELD_REQUIRED-
        // denied against any real policy -- invisible in kernel unit tests, which construct
        // DefaultConceptGateway with a permissive/noop semantic policy, not a real one.
        data.putIfAbsent("id", id);
        ConceptRecord saved = conceptGateway.save(new ConceptWriteRequest(step.conceptName(), id, null, data), context);
        putOutput(state, step.outputKey(), saved);
        return ProcedureStepResult.success(step);
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1B): a blank/unresolved idRef no longer
     * fails saveConcept -- it falls back to a generated id, the same auto-id fallback
     * {@code PostgresPersistenceCapabilityAdapter.save()} already gives flow-bound
     * createConcept/updateConcept steps (REG-77's exact asymmetry: flows got this for free via the
     * persistence capability, procedures never did).
     */
    private String resolveOrGenerateId(Map<String, Object> state, String ref, String conceptName) {
        Object value = ref == null || ref.isBlank() ? null : resolve(state, ref);
        if (value == null || String.valueOf(value).isBlank()) {
            return idProvider.nextId(conceptName);
        }
        return String.valueOf(value).trim();
    }

    /**
     * Move 4 (docs/MOVE4_CROSS_RECORD_WRITE_PLAN.md): read-modify-write a concept record, preserving
     * every field the step's {@code set} map doesn't name -- the single declarative operation REG-75
     * found procedures had no way to express (a {@code readConcept} result could not be merged with
     * caller-supplied overrides and passed onward). {@code set} values are literals by default; a
     * String starting with "$" resolves against procedure state (mirroring every other *Ref field's
     * convention), and "$$x" escapes to the literal string "$x".
     *
     * <p>Move 5 (Wave 1B): {@code createIfMissing} opts into the create half REG-77 found missing.
     * Default {@code false} preserves this exactly as it was -- {@code idRef} must resolve
     * non-blank, and {@code CONCEPT_NOT_FOUND} still fails. Opting in tolerates a blank/unresolved
     * idRef (nothing to look up yet) and, on a miss, builds a brand-new record from {@code set}
     * alone with a freshly generated id -- deliberately NOT the (missing) lookup id, so a caller
     * that queried for a match first (e.g. via a prior {@code listConcepts} step) and found none can
     * still invoke this with a blank idRef.
     */
    private ProcedureStepResult patchConcept(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        if (!step.createIfMissing()) {
            String id = requireString(state, step.idRef(), step.name(), "idRef");
            Optional<ConceptRecord> existing = conceptGateway.read(new ConceptReadRequest(step.conceptName(), id, null), context);
            if (existing.isEmpty()) {
                return ProcedureStepResult.failure(step, "CONCEPT_NOT_FOUND", "Concept record not found: " + step.conceptName() + ":" + id);
            }
            return patchExistingConcept(step, state, context, id, existing.orElseThrow());
        }

        Object idValue = step.idRef() == null || step.idRef().isBlank() ? null : resolve(state, step.idRef());
        String id = idValue == null ? null : String.valueOf(idValue).trim();
        Optional<ConceptRecord> existing = (id == null || id.isBlank())
                ? Optional.empty()
                : conceptGateway.read(new ConceptReadRequest(step.conceptName(), id, null), context);
        if (existing.isPresent()) {
            return patchExistingConcept(step, state, context, id, existing.orElseThrow());
        }

        String newId = idProvider.nextId(step.conceptName());
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("id", newId);
        step.setValues().forEach((key, raw) -> created.put(key, resolveSetValue(state, raw, step.name(), key)));
        ConceptRecord saved = conceptGateway.save(new ConceptWriteRequest(step.conceptName(), newId, null, created), context);
        putOutput(state, step.outputKey(), saved);
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult patchExistingConcept(
            ProcedureStep step, Map<String, Object> state, ExecutionContext context, String id, ConceptRecord existing
    ) {
        Map<String, Object> merged = new LinkedHashMap<>(existing.data());
        step.setValues().forEach((key, raw) -> merged.put(key, resolveSetValue(state, raw, step.name(), key)));
        ConceptRecord saved = conceptGateway.save(new ConceptWriteRequest(step.conceptName(), id, null, merged), context);
        putOutput(state, step.outputKey(), saved);
        return ProcedureStepResult.success(step);
    }

    /**
     * Literal by default; a "$"-prefixed String resolves from state; "$$x" escapes to the literal
     * "$x". X0-6 (REG-100): the "$ref" branch resolves STRICTLY -- a path that cannot be traversed
     * (missing segment, non-container mid-path) throws {@link UnresolvableReferenceException} rather
     * than silently producing {@code null}. A key present in state/a map with an explicit
     * {@code null} value is still a legitimate resolved null, not a failure -- only genuine absence
     * (a typo'd field, a rename) is refused. This single choke point covers every {@code
     * resolveSetValue} consumer (patchConcept.set, mapList.select, mapValue, computeValue's
     * left/right, return's valueRef) rather than special-casing three of the five.
     */
    private static Object resolveSetValue(Map<String, Object> state, Object raw, String stepName, String field) {
        if (raw instanceof String s && s.startsWith("$")) {
            return s.startsWith("$$") ? s.substring(1) : resolveStrict(state, s, stepName, field);
        }
        return raw;
    }

    private ProcedureStepResult readConcept(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        String id = requireString(state, step.idRef(), step.name(), "idRef");
        Optional<ConceptRecord> record = conceptGateway.read(new ConceptReadRequest(step.conceptName(), id, null), context);
        if (record.isEmpty()) {
            return ProcedureStepResult.failure(step, "CONCEPT_NOT_FOUND", "Concept record not found: " + step.conceptName() + ":" + id);
        }
        putOutput(state, step.outputKey(), record.orElseThrow());
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult listConcepts(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        List<ConceptRecord> records = conceptGateway.list(new ConceptListRequest(step.conceptName(), null), context);
        putOutput(state, step.outputKey(), records);
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult runQuery(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        if (step.conceptName() == null || step.conceptName().isBlank()) {
            return ProcedureStepResult.failure(step, "QUERY_UNSUPPORTED", "Procedure query steps require conceptName in the current runtime.");
        }
        // LIFT-QUERY-P1: the query name is threaded through the (legacy-named) "operation" slot --
        // see ProcedureStep.runQuery.
        String queryName = step.operation() == null ? null : step.operation().trim();
        CompiledQuery query = queryName == null || queryName.isEmpty()
                ? null
                : queriesByName.get(queryName.toLowerCase(java.util.Locale.ROOT));
        // X0-7 (REG-100), fixed alongside LC-P0: a NAMED query that does not resolve used to fall
        // through to "unfiltered", so a rename or a normalization mismatch silently returned every
        // row. Naming a query and getting no filter is the same defect LC-P0 removes one layer down;
        // an unresolvable name is now refused, while declaring no query at all stays a plain list.
        if (query == null && queryName != null && !queryName.isEmpty()) {
            return ProcedureStepResult.failure(step, "QUERY_NOT_FOUND",
                    "Procedure query step names query '" + queryName + "', which is not declared in this model. "
                            + "Refusing rather than returning every row unfiltered: a named query that silently "
                            + "does not filter returns rows the author asked to exclude, with no error (LC-P0/X0-7). "
                            + "Declared queries: " + queriesByName.keySet());
        }
        // LC-P0 scale half (MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 0): push the compiled predicate,
        // sort, and limit down to ConceptGateway.query -- the store narrows (WHERE/LIMIT on SQL for
        // the JDBC adapters) instead of fetching every row and filtering in the JVM.
        List<ConceptRecord> records;
        try {
            List<ConceptQuery.Filter> filters = query == null
                    ? List.of() : ConceptQueryPredicateCompiler.compile(query.where());
            List<ConceptQuery.Sort> sorts = query == null
                    ? List.of() : ConceptQueryPredicateCompiler.compileOrderBy(query.orderBy());
            Integer declaredLimit = query == null ? null : query.limit();
            int limit = declaredLimit != null && declaredLimit > 0 ? declaredLimit : DEFAULT_QUERY_ROW_CAP;
            ConceptQuery conceptQuery = new ConceptQuery(filters, sorts, 0, limit);
            ConceptPage page = conceptGateway.query(new ConceptQueryRequest(step.conceptName(), conceptQuery), context);
            records = page.items();
        } catch (ConceptQueryPredicateCompiler.UnsupportedPredicateException unsupported) {
            // LC-P0: surface it as a named STEP failure rather than letting it escape as an
            // exception -- callers already branch on step failure codes, and a procedure whose
            // query cannot be compiled is a modelling error, not a crash.
            return ProcedureStepResult.failure(step, "QUERY_PREDICATE_UNSUPPORTED", unsupported.getMessage());
        }
        putOutput(state, step.outputKey(), records);
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult deleteConcept(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        String id = requireString(state, step.idRef(), step.name(), "idRef");
        conceptGateway.delete(new ConceptReadRequest(step.conceptName(), id, null), context);
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult callCapability(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        List<Object> args = new ArrayList<>();
        for (String ref : step.argRefs()) {
            // Move 4 (docs/MOVE4_CROSS_RECORD_WRITE_PLAN.md, REG-75 wound #2): resolve() only
            // unwraps a ConceptRecord while traversing a dotted path, not on a bare-ref hit -- the
            // same asymmetry requireMap already works around for saveConcept's dataRef. Matching it
            // here means a readConcept result can be passed straight to a capability call.
            Object v = resolve(state, ref);
            args.add(v instanceof ConceptRecord r ? r.data() : v);
        }
        CapabilityResult result = capabilityDispatcher.invoke(
                new CapabilityCall(
                        step.capability(),
                        step.capabilityType(),
                        step.adapterId(),
                        step.operation(),
                        args,
                        correlationId(context),
                        context.idempotencyKey()
                ),
                state
        );
        if (!result.ok()) {
            String code = result.error() == null ? "CAPABILITY_FAILED" : result.error().code();
            String message = result.error() == null ? "Capability failed." : result.error().message();
            return ProcedureStepResult.failure(step, code, message);
        }
        putOutput(state, step.outputKey(), result.value());
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult publishEvent(
            ProcedureDefinition definition,
            ProcedureStep step,
            int stepIndex,
            Map<String, Object> state,
            ExecutionContext context
    ) {
        Map<String, Object> payload = requireMap(state, step.payloadRef(), step.name(), "payloadRef");
        eventBus.publish(EventEnvelope.create(
                step.eventName(),
                payload,
                correlationId(context),
                UUID.randomUUID().toString(),
                definition.name(),
                stepIndex,
                context.tenantId(),
                context.actorId()
        ));
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult callProcedure(
            ProcedureStep step,
            Map<String, Object> state,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        if (step.procedureName() == null || step.procedureName().isBlank()) {
            return ProcedureStepResult.failure(step, "PROCEDURE_REQUIRED", "Procedure step requires procedureName.");
        }
        if (recursionDepth >= limits.maxRecursionDepth()) {
            return ProcedureStepResult.failure(
                    step,
                    "PROCEDURE_RECURSION_LIMIT_EXCEEDED",
                    "Procedure recursion depth exceeded maxRecursionDepth=" + limits.maxRecursionDepth()
            );
        }
        ProcedureDefinition nestedDefinition = procedureRegistry.get(step.procedureName());
        if (nestedDefinition == null) {
            return ProcedureStepResult.failure(step, "PROCEDURE_NOT_FOUND", "Procedure not found: " + step.procedureName());
        }
        Map<String, Object> input = step.payloadRef() == null ? Map.of() : requireMap(state, step.payloadRef(), step.name(), "payloadRef");
        ProcedureExecutionResult result = executeDefinition(nestedDefinition, input, context, scope, recursionDepth + 1);
        if (!result.ok()) {
            return ProcedureStepResult.failure(step, result.failureCode(), result.failureMessage());
        }
        Object output = result.state().containsKey("return") ? result.state().get("return") : result.state();
        putOutput(state, step.outputKey(), output);
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult ifThenElse(
            ProcedureDefinition definition,
            ProcedureStep step,
            int stepIndex,
            Map<String, Object> state,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        // REG-96 (Wave 0.6): was truthy(resolve(...)) -- a bare reference tested for truthiness,
        // which could never ask "does it equal 'Concluido'". A bare ref still means exactly what it
        // used to; a comparison is now expressible using the SAME grammar visibleWhen carries.
        boolean condition;
        try {
            condition = ProcedureConditionEvaluator.evaluate(
                    step.conditionRef(), state, DefaultProcedureExecutor::resolve);
        } catch (ProcedureConditionEvaluator.UnsupportedConditionException unsupported) {
            return ProcedureStepResult.failure(step, "CONDITION_UNSUPPORTED", unsupported.getMessage());
        }
        List<ProcedureStep> selectedSteps = condition ? step.thenSteps() : step.elseSteps();
        ProcedureStepResult nestedResult = executeNestedSteps(
                definition,
                selectedSteps,
                stepIndex,
                state,
                context,
                scope,
                recursionDepth
        );
        return nestedResult.ok() ? ProcedureStepResult.success(step) : nestedResult;
    }

    private ProcedureStepResult forEach(
            ProcedureDefinition definition,
            ProcedureStep step,
            int stepIndex,
            Map<String, Object> state,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        Iterable<?> items = toIterable(resolve(state, step.collectionRef()));
        if (items == null) {
            return ProcedureStepResult.failure(step, "COLLECTION_REQUIRED", "Procedure forEach requires an iterable collection.");
        }
        String itemKey = step.itemKey() == null ? "item" : step.itemKey();
        Object previous = state.get(itemKey);
        boolean hadPrevious = state.containsKey(itemKey);
        int iterations = 0;
        try {
            for (Object item : items) {
                iterations++;
                if (iterations > limits.maxLoopIterations()) {
                    return ProcedureStepResult.failure(
                            step,
                            "PROCEDURE_LOOP_LIMIT_EXCEEDED",
                            "Procedure loop exceeded maxLoopIterations=" + limits.maxLoopIterations()
                    );
                }
                state.put(itemKey, item);
                ProcedureStepResult nestedResult = executeNestedSteps(
                        definition,
                        step.steps(),
                        stepIndex,
                        state,
                        context,
                        scope,
                        recursionDepth
                );
                if (!nestedResult.ok()) {
                    return nestedResult;
                }
            }
        } finally {
            if (hadPrevious) {
                state.put(itemKey, previous);
            } else {
                state.remove(itemKey);
            }
        }
        return ProcedureStepResult.success(step);
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3A / Gap 6): unlike {@link #forEach}, which
     * only iterates for side effects, {@code mapList} PRODUCES a new collection -- one output object
     * per input item, built from {@code step.setValues()} ("select" in the DSL) resolved against
     * that item via the SAME {@link #resolveSetValue} literal-vs-{@code $ref} convention
     * {@code patchConcept}'s {@code set} already established (one convention, not a second one).
     * Reuses {@code collectionRef}/{@code itemKey}/{@code outputKey} exactly like {@code forEach} --
     * no new kernel-level fields needed.
     */
    private ProcedureStepResult mapList(ProcedureStep step, Map<String, Object> state) {
        Iterable<?> items = toIterable(resolve(state, step.collectionRef()));
        if (items == null) {
            return ProcedureStepResult.failure(step, "COLLECTION_REQUIRED", "Procedure mapList requires an iterable collection.");
        }
        String itemKey = step.itemKey() == null ? "item" : step.itemKey();
        Object previous = state.get(itemKey);
        boolean hadPrevious = state.containsKey(itemKey);
        List<Map<String, Object>> mapped = new ArrayList<>();
        int iterations = 0;
        try {
            for (Object item : items) {
                iterations++;
                if (iterations > limits.maxLoopIterations()) {
                    return ProcedureStepResult.failure(
                            step,
                            "PROCEDURE_LOOP_LIMIT_EXCEEDED",
                            "Procedure loop exceeded maxLoopIterations=" + limits.maxLoopIterations()
                    );
                }
                state.put(itemKey, item);
                Map<String, Object> selected = new LinkedHashMap<>();
                step.setValues().forEach((key, raw) -> selected.put(key, resolveSetValue(state, raw, step.name(), key)));
                mapped.add(selected);
            }
        } finally {
            if (hadPrevious) {
                state.put(itemKey, previous);
            } else {
                state.remove(itemKey);
            }
        }
        putOutput(state, step.outputKey(), mapped);
        return ProcedureStepResult.success(step);
    }

    /**
     * REG-86: {@code valueRef} resolves via {@link #resolveSetValue}, the SAME literal-vs-{@code $ref}
     * convention {@code patchConcept}'s {@code set} uses -- not the always-a-state-path {@link
     * #resolve}, which a literal array/object could never survive (it would be treated as a dotted
     * path and resolve to {@code null}).
     */
    private ProcedureStepResult mapValue(ProcedureStep step, Map<String, Object> state) {
        Object value = resolveSetValue(state, step.valueRef(), step.name(), "valueRef");
        putOutput(state, step.outputKey(), value);
        return ProcedureStepResult.success(step);
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, final item / REG-78): {@code left}/{@code right}
     * live in {@code step.setValues()} (keys "left"/"right") and resolve via the SAME {@link
     * #resolveSetValue} literal-vs-{@code $ref} convention {@code patchConcept}'s {@code set} and
     * {@code mapList}'s {@code select} already use -- a fixed delta needs no "$", a value read off a
     * prior step (e.g. {@code $existing.quantidade}) does. {@code step.operation()} carries the
     * operator ("add"/"subtract", the minimum REG-78 named). Operands are coerced through
     * {@link BigDecimal} so a "long" concept field (WMS quantities) added to a plain JSON integer
     * literal doesn't round-trip through binary floating point; the result is normalized back to a
     * {@code Long} when it has no fractional part, a {@code Double} otherwise.
     */
    private ProcedureStepResult computeValue(ProcedureStep step, Map<String, Object> state) {
        Object leftRaw = resolveSetValue(state, step.setValues().get("left"), step.name(), "left");
        Object rightRaw = resolveSetValue(state, step.setValues().get("right"), step.name(), "right");
        BigDecimal left;
        BigDecimal right;
        try {
            left = toBigDecimal(leftRaw);
            right = toBigDecimal(rightRaw);
        } catch (NumberFormatException | ArithmeticException exception) {
            return ProcedureStepResult.failure(
                    step,
                    "COMPUTE_VALUE_INVALID_OPERAND",
                    "Procedure computeValue requires numeric operands; got left=" + leftRaw + ", right=" + rightRaw
            );
        }
        String operator = step.operation() == null ? "" : step.operation().trim().toLowerCase(Locale.ROOT);
        BigDecimal result = switch (operator) {
            case "add" -> left.add(right);
            case "subtract" -> left.subtract(right);
            default -> null;
        };
        if (result == null) {
            return ProcedureStepResult.failure(
                    step,
                    "COMPUTE_VALUE_UNKNOWN_OPERATOR",
                    "Procedure computeValue has an unknown operator (expected add/subtract): " + step.operation()
            );
        }
        putOutput(state, step.outputKey(), normalizeComputedNumber(result));
        return ProcedureStepResult.success(step);
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            throw new NumberFormatException("computeValue operand is null");
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(String.valueOf(value).trim());
    }

    /**
     * Deliberately an if/else, NOT a ternary: {@code cond ? stripped.longValueExact() :
     * stripped.doubleValue()} would undergo Java's binary numeric promotion for conditional
     * expressions (JLS 15.25) -- since one branch is {@code long} and the other {@code double}, the
     * WHOLE expression's static type becomes {@code double}, silently widening the long branch's
     * result to a double even when it is the one chosen. Found live: every whole-number computeValue
     * result (e.g. 10+5) came back as a Double (15.0) instead of a Long (15) until this was an
     * if/else, each branch autoboxing independently with no cross-branch promotion.
     */
    private static Object normalizeComputedNumber(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            return stripped.longValueExact();
        }
        return stripped.doubleValue();
    }

    /** REG-86: same {@link #resolveSetValue} literal-vs-{@code $ref} convention as {@link #mapValue}. */
    private ProcedureStepResult returnValue(ProcedureStep step, Map<String, Object> state) {
        state.put("return", resolveSetValue(state, step.returnRef(), step.name(), "returnRef"));
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult executeNestedSteps(
            ProcedureDefinition definition,
            List<ProcedureStep> steps,
            int parentStepIndex,
            Map<String, Object> state,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        for (int offset = 0; offset < steps.size(); offset++) {
            ProcedureStep child = steps.get(offset);
            ProcedureStepResult childResult = executeStepWithBudget(
                    definition,
                    child,
                    parentStepIndex + offset + 1,
                    state,
                    context,
                    scope,
                    recursionDepth
            );
            if (!childResult.ok()) {
                return childResult;
            }
            if (child.type() == ProcedureStepType.RETURN) {
                return childResult;
            }
        }
        return ProcedureStepResult.success(new ProcedureStep(
                definition.name() + ".nested",
                ProcedureStepType.MAP_VALUE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                false
        ));
    }

    private static void putOutput(Map<String, Object> state, String outputKey, Object value) {
        if (outputKey != null && !outputKey.isBlank()) {
            state.put(outputKey, value);
        }
    }

    private static String requireString(Map<String, Object> state, String ref, String stepName, String field) {
        Object value = resolve(state, ref);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Procedure step " + stepName + " requires non-blank " + field + ": " + ref);
        }
        return String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Map<String, Object> state, String ref, String stepName, String field) {
        Object value = resolve(state, ref);
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> out = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> out.put(String.valueOf(key), item));
            return out;
        }
        if (value instanceof ConceptRecord record) {
            return record.data();
        }
        throw new IllegalArgumentException("Procedure step " + stepName + " requires map " + field + ": " + ref);
    }

    private static Object resolve(Map<String, Object> state, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String normalized = ref.trim();
        if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        if (state.containsKey(normalized)) {
            return state.get(normalized);
        }
        String[] parts = normalized.split("\\.");
        Object current = state.get(parts[0]);
        for (int index = 1; index < parts.length; index++) {
            if (current instanceof ConceptRecord record) {
                current = record.data();
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[index]);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * X0-6 (REG-100): the strict counterpart to {@link #resolve}, used wherever a {@code "$ref"}
     * names a value that is about to be WRITTEN somewhere (a concept field, a mapped-list item, a
     * return value). {@code resolve} treats "path segment missing" and "path segment present but
     * null" identically -- both produce {@code null} -- which is exactly what let a typo'd {@code
     * $item.quantidad} write {@code quantidade: null} into a real column with no error anywhere.
     * This version distinguishes them via {@code containsKey}: an explicit null already stored in
     * state is a legitimately resolved value; a key that was never bound is refused.
     */
    private static Object resolveStrict(Map<String, Object> state, String ref, String stepName, String field) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String normalized = ref.trim();
        if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        if (state.containsKey(normalized)) {
            return state.get(normalized);
        }
        String[] parts = normalized.split("\\.");
        if (!state.containsKey(parts[0])) {
            throw new UnresolvableReferenceException(stepName, field, ref);
        }
        Object current = state.get(parts[0]);
        for (int index = 1; index < parts.length; index++) {
            if (current instanceof ConceptRecord record) {
                current = record.data();
            }
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(parts[index])) {
                    throw new UnresolvableReferenceException(stepName, field, ref);
                }
                current = map.get(parts[index]);
            } else {
                throw new UnresolvableReferenceException(stepName, field, ref);
            }
        }
        return current;
    }

    /** X0-6 (REG-100): named failure for a {@code "$ref"} whose path cannot be resolved. */
    static final class UnresolvableReferenceException extends RuntimeException {
        UnresolvableReferenceException(String stepName, String field, String ref) {
            super("Procedure step " + stepName + " cannot resolve " + field + " reference " + ref
                    + ": no such path in the current procedure state");
        }
    }

    private static String correlationId(ExecutionContext context) {
        String existing = context.correlationId();
        return existing == null || existing.isBlank() ? UUID.randomUUID().toString() : existing;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static Iterable<?> toIterable(Object value) {
        if (value instanceof Iterable<?> iterable) {
            return iterable;
        }
        if (value instanceof Collection<?> collection) {
            return collection;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                items.add(Array.get(value, index));
            }
            return items;
        }
        return null;
    }

    private static final class ProcedureExecutionScope {
        private final ProcedureExecutionLimits limits;
        private int stepsExecuted;

        private ProcedureExecutionScope(ProcedureExecutionLimits limits) {
            this.limits = limits;
        }

        private ProcedureStepResult beforeStep(ProcedureStep step) {
            stepsExecuted++;
            if (stepsExecuted > limits.maxSteps()) {
                return ProcedureStepResult.failure(
                        step,
                        "PROCEDURE_STEP_LIMIT_EXCEEDED",
                        "Procedure exceeded maxSteps=" + limits.maxSteps()
                );
            }
            return ProcedureStepResult.success(step);
        }
    }
}
