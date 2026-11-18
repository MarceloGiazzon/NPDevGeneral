package com.npdev.kernel.procedures;

import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.dsl.v1.expr.ResolverScope;
import com.npdev.kernel.concepts.ValueExpressionFunctions;
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
import com.npdev.kernel.ports.IdProvider;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * FORM P3-T7: the ONE implementation of the concept-bound step types a flow runs -- {@code set} (EVALUATE),
 * {@code readConcept}, {@code listConcepts}, {@code runQuery}, {@code deleteConcept}, the {@code map} select forms
 * ({@code MAP_LIST} / {@code BUILD_OBJECT}) and {@code updateConcept}'s read-modify-write ({@code PATCH_CONCEPT}).
 * The flow engine ({@code KernelRunner}, via {@code ProcedureFlowStep}) hands each such step here as a one-step
 * definition. Control flow (if / forEach / return), capability calls, events and persistence writes are the flow
 * engine's own steps; this class no longer carries a second copy of them (procedures[] are never executed: they are
 * declarative stubs for trusted Java code, and every executable procedure became a flow in P3-T5c).
 */
public final class DefaultProcedureExecutor implements ProcedureExecutor {
    /** LIFT-QUERY-P1: soft cap applied to a {@code runQuery} step's results when the query
     * declares no explicit {@code limit}, so a capability is never handed an unbounded list. */
    private static final int DEFAULT_QUERY_ROW_CAP = 1_000;

    private final ConceptGateway conceptGateway;
    private final ProcedureExecutionLimits limits;
    private final Map<String, CompiledQuery> queriesByName;
    private final IdProvider idProvider;

    public DefaultProcedureExecutor(ConceptGateway conceptGateway) {
        this(conceptGateway, ProcedureExecutionLimits.defaults(), Map.of(), null);
    }

    public DefaultProcedureExecutor(ConceptGateway conceptGateway, Map<String, CompiledQuery> queriesByName) {
        this(conceptGateway, ProcedureExecutionLimits.defaults(), queriesByName, null);
    }

    /** LIFT-QUERY-P1: {@code queriesByName} (keyed case-insensitively) lets {@code runQuery} steps
     * honor their declared query's {@code where}/{@code orderBy}/{@code limit} instead of always
     * returning every row for the concept; a name absent from the map still runs, unfiltered.
     * {@code idProvider} backs {@code patchConcept}'s {@code createIfMissing} (defaults to {@link IdProvider#uuid()}). */
    public DefaultProcedureExecutor(
            ConceptGateway conceptGateway,
            ProcedureExecutionLimits limits,
            Map<String, CompiledQuery> queriesByName,
            IdProvider idProvider
    ) {
        this.conceptGateway = Objects.requireNonNull(conceptGateway, "conceptGateway");
        this.limits = limits == null ? ProcedureExecutionLimits.defaults() : limits;
        Map<String, CompiledQuery> normalizedQueries = new LinkedHashMap<>();
        if (queriesByName != null) {
            for (Map.Entry<String, CompiledQuery> entry : queriesByName.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    normalizedQueries.put(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getValue());
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
        Objects.requireNonNull(definition, "definition");
        ExecutionContext effectiveContext = context == null ? ExecutionContext.anonymous() : context;
        if (definition.runAsSystem() && !ExecutionContext.isSystem(effectiveContext)) {
            // runAs: "system" -- same tenant, the platform's system principal; the original caller is kept for audit.
            String callerActor = effectiveContext.actorId();
            ExecutionContext elevated = ExecutionContext.system(effectiveContext.tenantId());
            String previous = ProcedureElevation.swap(callerActor);
            try {
                return runSteps(definition, input, elevated);
            } finally {
                ProcedureElevation.swap(previous);
            }
        }
        return runSteps(definition, input, effectiveContext);
    }

    private ProcedureExecutionResult runSteps(
            ProcedureDefinition definition,
            Map<String, Object> input,
            ExecutionContext effectiveContext
    ) {
        Map<String, Object> state = new LinkedHashMap<>();
        if (input != null) {
            state.putAll(input);
        }
        state.putIfAbsent("input", input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input)));

        ProcedureExecutionScope scope = new ProcedureExecutionScope(limits);
        List<ProcedureStepResult> stepResults = new ArrayList<>();
        for (ProcedureStep step : definition.steps()) {
            ProcedureStepResult result = executeStepWithBudget(step, state, effectiveContext, scope);
            stepResults.add(result);
            if (!result.ok()) {
                return ProcedureExecutionResult.failure(state, stepResults, result.code(), result.message());
            }
        }
        return ProcedureExecutionResult.success(state, stepResults);
    }

    private ProcedureStepResult executeStepWithBudget(
            ProcedureStep step,
            Map<String, Object> state,
            ExecutionContext context,
            ProcedureExecutionScope scope
    ) {
        ProcedureStepResult budget = scope.beforeStep(step);
        if (!budget.ok()) {
            return budget;
        }
        try {
            return executeStep(step, state, context);
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

    private ProcedureStepResult executeStep(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        return switch (step.type()) {
            case PATCH_CONCEPT -> patchConcept(step, state, context);
            case READ_CONCEPT -> readConcept(step, state, context);
            case LIST_CONCEPTS -> listConcepts(step, state, context);
            case RUN_QUERY -> runQuery(step, state, context);
            case DELETE_CONCEPT -> deleteConcept(step, state, context);
            case MAP_LIST -> mapList(step, state);
            case EVALUATE -> evaluate(step, state);
            case BUILD_OBJECT -> buildObject(step, state);
        };
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
        //
        // R4.3 (Roadmap Wave 1) lockstep fix: this used to call ConceptQueryPredicateCompiler#compile
        // (the v1-only AND-only grammar), which is exactly why PackValidation#validateQueryWhereCompiles
        // stayed v1-only too -- widening authoring-time validation to the v2 grammar (OR-groups, in,
        // contains/startsWith, is-null, reference-path joins) without rewiring THIS call site first
        // would have let a model validate clean and then throw the first time a runQuery step tried to
        // execute it (REG-101's trap, one layer up). Now calls #compileToConceptQueryFilters, the v2
        // sibling that already turns an OR-of-AND predicate into a single ConceptQuery.Operator#OR_GROUPS
        // marker filter (or a flat AND-list when there is only one group) -- the exact shape
        // JdbcBusinessConceptStore's query()/aggregate() already render as real SQL (proven against H2
        // by JdbcBusinessConceptStorePredicateV2Test), so a runQuery step now reaches the same,
        // already-proven SQL pushdown path a declared v2 predicate always could reach through a
        // hand-built ConceptQuery.
        List<ConceptRecord> records;
        try {
            List<ConceptQuery.Filter> filters = query == null
                    ? List.of() : ConceptQueryPredicateCompiler.compileToConceptQueryFilters(
                            query.where(), query.parameters(), resolveQueryParams(step, query, state), context);
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


    /** K3: {code, message, kind, retryable, httpStatus?, details}. */
    public static Map<String, Object> errorDescription(
            com.npdev.kernel.CapabilityError error, String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("message", message);
        com.npdev.kernel.CapabilityErrorKind kind = error == null ? null : error.kind();
        out.put("kind", kind == null ? null : kind.name());
        Map<String, Object> details = error == null || error.details() == null ? Map.of() : error.details();
        Object httpStatus = details.get("httpStatus");
        Integer status = httpStatus instanceof Number n ? n.intValue() : null;
        boolean retryable = kind == com.npdev.kernel.CapabilityErrorKind.TRANSIENT
                || kind == com.npdev.kernel.CapabilityErrorKind.RATE_LIMIT
                || kind == com.npdev.kernel.CapabilityErrorKind.TIMEOUT
                || (status != null && (status == 408 || status == 429 || status >= 500));
        out.put("retryable", retryable);
        if (status != null) {
            out.put("httpStatus", status);
        }
        out.put("details", new LinkedHashMap<>(details));
        return out;
    }

    /** K2: resolve a runQuery step's {@code params} (literal or $ref) into the query's bound parameter values. */
    private Map<String, Object> resolveQueryParams(ProcedureStep step, CompiledQuery query, Map<String, Object> state) {
        Map<String, Object> bound = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : step.queryParams().entrySet()) {
            bound.put(entry.getKey(), resolveSetValue(state, entry.getValue(), step.name(), "params." + entry.getKey()));
        }
        return bound;
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
    /**
     * FORM P3-T5c: the flow {@code set} step. The value is ONE expression of the one expression language; every
     * {@code $ref} resolves through {@link #resolveStrict} (a typo fails the step with REF_UNRESOLVABLE) and a bare
     * name is refused, exactly as in {@link #evaluateCondition}. A parse/arithmetic error refuses the step.
     */
    private ProcedureStepResult evaluate(ProcedureStep step, Map<String, Object> state) {
        String expression = step.valueRef() == null ? null : step.valueRef().toString();
        if (expression == null || expression.isBlank()) {
            return ProcedureStepResult.failure(step, "EXPRESSION_REQUIRED", "A set step needs a value expression.");
        }
        Object value;
        try {
            value = ComputedExpression.evaluateStrict(
                    expression,
                    new ResolverScope(name -> {
                        if (!name.startsWith("$")) {
                            throw new ComputedExpression.ExpressionException(
                                    "unknown name '" + name + "' -- flow state is referenced as $" + name
                                            + " (a text literal is quoted: 'text')");
                        }
                        return resolveStrict(state, name, step.name(), "value");
                    }),
                    ValueExpressionFunctions.flow());
        } catch (ComputedExpression.ExpressionException invalid) {
            return ProcedureStepResult.failure(step, "EXPRESSION_INVALID",
                    "EXPRESSION_INVALID: cannot evaluate \"" + expression + "\" -- " + invalid.getMessage());
        }
        putOutput(state, step.outputKey(), value);
        return ProcedureStepResult.success(step);
    }

    /** FORM P3-T5c: a flow {@code map} step without a collection: one object from {@code select} (strict literal-or-ref values). */
    private ProcedureStepResult buildObject(ProcedureStep step, Map<String, Object> state) {
        Map<String, Object> built = new LinkedHashMap<>();
        step.setValues().forEach((key, raw) -> built.put(key, resolveSetValue(state, raw, step.name(), key)));
        putOutput(state, step.outputKey(), built);
        return ProcedureStepResult.success(step);
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

    /**
     * FORM P3-T5c: a concept record that crossed a flow step boundary is the plain map {@code {conceptName, id, tenantId,
     * data, rowVersion}} (FlowStepSupport.plainTree). A ref reads such a record's FIELDS first -- exactly as it does on a
     * {@link ConceptRecord} inside one procedure -- so {@code $ride.status} means the same in a flow as it did in the
     * procedure it replaced; {@code $ride.data.status} and {@code $ride.rowVersion} still work through the map's own keys.
     * Null when {@code map} is not record-shaped.
     */
    public static Map<?, ?> recordFields(Map<?, ?> map) {
        return map.containsKey("conceptName") && map.get("data") instanceof Map<?, ?> data ? data : null;
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
                Map<?, ?> fields = recordFields(map);
                current = fields != null && fields.containsKey(parts[index]) ? fields.get(parts[index]) : map.get(parts[index]);
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
                Map<?, ?> fields = recordFields(map);
                if (fields != null && fields.containsKey(parts[index])) {
                    current = fields.get(parts[index]);
                } else if (!map.containsKey(parts[index])) {
                    throw new UnresolvableReferenceException(stepName, field, ref);
                } else {
                    current = map.get(parts[index]);
                }
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
