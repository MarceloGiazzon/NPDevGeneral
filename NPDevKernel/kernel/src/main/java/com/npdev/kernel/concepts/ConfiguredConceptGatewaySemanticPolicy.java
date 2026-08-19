package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledConceptAccess;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFieldAccess;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.CompiledSequence;
import com.npdev.dsl.v1.compiled.CompiledStateMachineState;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.dsl.v1.expr.SequenceNumberFormat;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.SequenceAllocator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfiguredConceptGatewaySemanticPolicy implements ConceptGatewaySemanticPolicy {
    private final Map<String, ConceptDefinition> conceptsByName;
    private final Map<String, CompiledSequence> sequencesByName;
    private final SequenceAllocator sequenceAllocator;

    public ConfiguredConceptGatewaySemanticPolicy(List<ConceptDefinition> concepts) {
        this(concepts, List.of(), SequenceAllocator.inMemory());
    }

    /**
     * R5.3: adds the declared {@code sequences[]} (document-numbering counters, see {@code
     * SequenceAst}) and the allocator that resolves a field's {@code nextNumber('name')}
     * defaultExpression -- see {@link #evaluateFieldDefault}. The single-argument constructor above
     * keeps every existing caller compiling unchanged, defaulting to an in-memory allocator (correct
     * for tests and the {@code *-inproc} adapters; a real app wires a JDBC-backed one -- see {@link
     * SequenceAllocator}'s own javadoc).
     */
    public ConfiguredConceptGatewaySemanticPolicy(
            List<ConceptDefinition> concepts, List<CompiledSequence> sequences, SequenceAllocator sequenceAllocator) {
        Map<String, ConceptDefinition> byName = new LinkedHashMap<>();
        for (ConceptDefinition concept : concepts == null ? List.<ConceptDefinition>of() : concepts) {
            byName.put(normalizeKey(concept.name()), concept);
        }
        this.conceptsByName = Map.copyOf(byName);
        Map<String, CompiledSequence> sequencesByNameBuilder = new LinkedHashMap<>();
        for (CompiledSequence sequence : sequences == null ? List.<CompiledSequence>of() : sequences) {
            sequencesByNameBuilder.put(normalizeKey(sequence.name()), sequence);
        }
        this.sequencesByName = Map.copyOf(sequencesByNameBuilder);
        this.sequenceAllocator = Objects.requireNonNull(sequenceAllocator, "sequenceAllocator");
    }

    public static ConfiguredConceptGatewaySemanticPolicy empty() {
        return new ConfiguredConceptGatewaySemanticPolicy(List.of());
    }

    /**
     * Move 6 §7.5 (docs/MOVE6_TYPED_SURFACE_PLAN.md): builds the SAME governed policy every
     * generated app actually runs, directly from a {@link CompiledModel} -- moved here (kernel
     * already owns both this class and {@code CompiledModel}) from what had been RuntimeHost-only
     * wiring ({@code RuntimeConceptGatewaySemanticPolicies.fromCompiledModel}, which now delegates
     * to this), so it's reachable from a plain kernel-module unit test too, with no RuntimeHost
     * dependency. REG-83 shipped broken for nine commits because every existing unit test used a
     * gateway with no field-required/enum/lifecycle enforcement at all (a noop policy), so a real
     * bug in the write path had nothing to trip over -- prefer this over {@link #empty()} whenever
     * a test exercises save/patch/delete against a real compiled model.
     */
    public static ConfiguredConceptGatewaySemanticPolicy fromCompiledModel(CompiledModel compiledModel) {
        return fromCompiledModel(compiledModel, SequenceAllocator.inMemory());
    }

    /** R5.3: {@link #fromCompiledModel(CompiledModel)} with an explicit {@link SequenceAllocator}
     *  -- the production wiring path (see {@code NpdevCapabilityBindingConfig.conceptGateway},
     *  RuntimeHost) supplies a JDBC-backed one when a real DataSource is available. */
    public static ConfiguredConceptGatewaySemanticPolicy fromCompiledModel(
            CompiledModel compiledModel, SequenceAllocator sequenceAllocator) {
        if (compiledModel == null) {
            return empty();
        }
        List<ConceptDefinition> definitions = new ArrayList<>();
        for (CompiledConcept concept : compiledModel.getConcepts()) {
            definitions.add(toConceptDefinition(concept));
        }
        return new ConfiguredConceptGatewaySemanticPolicy(
                definitions, compiledModel.getSequences(), sequenceAllocator);
    }

    private static ConceptDefinition toConceptDefinition(CompiledConcept concept) {
        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        for (CompiledField field : concept.getFields()) {
            fields.put(field.getName(), toFieldDefinition(field));
        }
        return new ConceptDefinition(
                concept.getName(),
                fields,
                invariantsOf(concept),
                lifecycleOf(concept.getLifecycle()),
                hiddenFieldsOf(fields),
                accessRulesOf(concept.getAccess())
        );
    }

    private static AccessRules accessRulesOf(CompiledConceptAccess access) {
        if (access == null) {
            return null;
        }
        return new AccessRules(access.getRead(), access.getWrite());
    }

    private static FieldDefinition toFieldDefinition(CompiledField field) {
        CompiledSchema schema = field.getSchema();
        List<String> enumValues = new ArrayList<>(field.getEnumValues());
        if (schema != null) {
            for (String enumValue : schema.getEnumValues()) {
                if (!enumValues.contains(enumValue)) {
                    enumValues.add(enumValue);
                }
            }
        }
        return new FieldDefinition(
                field.getName(),
                field.isRequired(),
                enumValues,
                schema == null ? null : schema.getDefaultValue(),
                schema == null ? null : schema.getDefaultExpression(),
                schema == null ? null : schema.getDerivedExpression(),
                false,
                field.getReferenceTarget(),
                fieldAccessRulesOf(field.getAccess())
        );
    }

    /** R5.5: a field's own declared {read, write} rule -- reuses {@link AccessRules}, the SAME
     *  two-property shape {@code concept.access} already uses, one rung down the ladder. */
    private static AccessRules fieldAccessRulesOf(CompiledFieldAccess access) {
        if (access == null) {
            return null;
        }
        return new AccessRules(access.getRead(), access.getWrite());
    }

    private static List<InvariantDefinition> invariantsOf(CompiledConcept concept) {
        List<InvariantDefinition> invariants = new ArrayList<>();
        int index = 1;
        for (String expression : concept.getExpressionInvariants()) {
            if (hasText(expression)) {
                invariants.add(new InvariantDefinition("expressionInvariant" + index, expression));
                index++;
            }
        }
        for (CompiledInvariant invariant : concept.getInvariants()) {
            if (hasText(invariant.getExpression())) {
                invariants.add(new InvariantDefinition(
                        hasText(invariant.getRef()) ? invariant.getRef() : "compiledInvariant" + index,
                        invariant.getExpression()));
                index++;
            }
        }
        return List.copyOf(invariants);
    }

    private static LifecycleDefinition lifecycleOf(CompiledLifecycle lifecycle) {
        if (lifecycle == null || !hasText(lifecycle.getStatusField())) {
            return null;
        }
        List<String> states = new ArrayList<>();
        String initial = null;
        for (CompiledStateMachineState state : lifecycle.getStates()) {
            if (!hasText(state.getValue())) {
                continue;
            }
            states.add(state.getValue());
            if (state.isInitial()) {
                initial = state.getValue();
            }
        }
        if (initial == null && !states.isEmpty()) {
            initial = states.get(0);
        }
        List<StateTransition> transitions = new ArrayList<>();
        for (CompiledStateTransition transition : lifecycle.getTransitions()) {
            if (hasText(transition.getFrom()) && hasText(transition.getTo())) {
                transitions.add(new StateTransition(transition.getFrom(), transition.getTo()));
            }
        }
        return LifecycleDefinition.of(lifecycle.getStatusField(), initial, states, transitions);
    }

    private static LinkedHashSet<String> hiddenFieldsOf(Map<String, FieldDefinition> fields) {
        LinkedHashSet<String> hiddenFields = new LinkedHashSet<>();
        for (FieldDefinition field : fields.values()) {
            if (field.hidden()) {
                hiddenFields.add(field.name());
            }
        }
        return hiddenFields;
    }

    @Override
    public ConceptSemanticDecision normalizeAndValidate(ConceptGatewayRequestContext request) {
        ConceptDefinition concept = concept(request);
        if (concept == null) {
            return ConceptSemanticDecision.allow(request.data());
        }

        Map<String, Object> data = new LinkedHashMap<>(request.data());
        for (FieldDefinition field : concept.fields().values()) {
            Object value = data.get(field.name());
            if (field.required() && isBlankValue(value) && !canApplyDefault(concept, field)) {
                return ConceptSemanticDecision.deny(
                        "CONCEPT_FIELD_REQUIRED",
                        "Required concept field is missing: " + request.conceptName() + "." + field.name(),
                        Map.of("concept", request.conceptName(), "field", field.name())
                );
            }
            if (!field.enumValues().isEmpty() && !isBlankValue(value)
                    && field.enumValues().stream().noneMatch(item -> item.equals(String.valueOf(value)))) {
                return ConceptSemanticDecision.deny(
                        "CONCEPT_ENUM_INVALID",
                        "Concept field value is outside the declared enum: " + request.conceptName() + "." + field.name(),
                        Map.of("concept", request.conceptName(), "field", field.name(), "value", String.valueOf(value))
                );
            }
        }
        return ConceptSemanticDecision.allow(data);
    }

    @Override
    public ConceptSemanticDecision applyDefaultsAndDerivedValues(ConceptGatewayRequestContext request) {
        ConceptDefinition concept = concept(request);
        if (concept == null) {
            return ConceptSemanticDecision.allow(request.data());
        }

        Map<String, Object> data = new LinkedHashMap<>(request.data());
        List<String> defaults = new ArrayList<>();
        List<String> derived = new ArrayList<>();
        for (FieldDefinition field : concept.fields().values()) {
            if (isBlankValue(data.get(field.name()))) {
                Object defaultValue = field.defaultValue();
                if (defaultValue == null && hasText(field.defaultExpression())) {
                    defaultValue = evaluateFieldDefault(field.defaultExpression(), data, request.tenantId());
                }
                if (defaultValue != null) {
                    data.put(field.name(), defaultValue);
                    defaults.add(field.name());
                }
            }
            if (hasText(field.derivedExpression())) {
                Object derivedValue = evaluateValueExpression(field.derivedExpression(), data);
                if (derivedValue != null) {
                    data.put(field.name(), derivedValue);
                    derived.add(field.name());
                }
            }
        }
        return new ConceptSemanticDecision(
                true,
                "allowed",
                "allowed",
                data,
                List.of(),
                defaults,
                derived,
                null,
                Map.of()
        );
    }

    @Override
    public ConceptSemanticDecision validateLifecycleTransition(ConceptGatewayRequestContext request) {
        ConceptDefinition concept = concept(request);
        if (concept == null || concept.lifecycle() == null || !hasText(concept.lifecycle().statusField())) {
            return ConceptSemanticDecision.allow(request.data());
        }

        LifecycleDefinition lifecycle = concept.lifecycle();
        Map<String, Object> data = new LinkedHashMap<>(request.data());
        String statusField = lifecycle.statusField();
        String previous = request.previousRecord()
                .map(record -> stringValue(record.data().get(statusField)))
                .filter(ConfiguredConceptGatewaySemanticPolicy::hasText)
                .orElse(null);
        String next = stringValue(data.get(statusField));

        List<String> defaults = new ArrayList<>();
        if (!hasText(next) && previous == null && hasText(lifecycle.initialState())) {
            next = lifecycle.initialState();
            data.put(statusField, next);
            defaults.add(statusField);
        }

        if (!hasText(next) || Objects.equals(previous, next)) {
            return new ConceptSemanticDecision(
                    true,
                    "allowed",
                    "allowed",
                    data,
                    List.of(),
                    defaults,
                    List.of(),
                    previous == null && hasText(next) ? "null->" + next : null,
                    Map.of()
            );
        }

        if (!lifecycle.states().isEmpty() && !lifecycle.states().contains(next)) {
            return ConceptSemanticDecision.deny(
                    "CONCEPT_LIFECYCLE_STATE_INVALID",
                    "Concept lifecycle target state is not declared: " + next,
                    Map.of("concept", request.conceptName(), "statusField", statusField, "state", next)
            );
        }

        if (previous != null && !lifecycle.transitions().contains(new StateTransition(previous, next))) {
            return ConceptSemanticDecision.deny(
                    "CONCEPT_LIFECYCLE_TRANSITION_INVALID",
                    "Concept lifecycle transition is not allowed: " + previous + " -> " + next,
                    Map.of("concept", request.conceptName(), "statusField", statusField, "from", previous, "to", next)
            );
        }

        return new ConceptSemanticDecision(
                true,
                "allowed",
                "allowed",
                data,
                List.of(),
                defaults,
                List.of(),
                (previous == null ? "null" : previous) + "->" + next,
                Map.of()
        );
    }

    @Override
    public ConceptSemanticDecision evaluateRuleProfiles(
            ConceptGatewayRequestContext request,
            List<ConceptRuleProfile> ruleProfiles
    ) {
        ConceptDefinition concept = concept(request);
        if (concept == null || concept.invariants().isEmpty()) {
            return allowWithSemanticDetails(request, ruleProfiles, List.of(), "noRulesConfigured");
        }
        if (request.operation() == ConceptGatewayOperation.READ || request.operation() == ConceptGatewayOperation.LIST) {
            List<Map<String, Object>> rules = new ArrayList<>();
            for (InvariantDefinition invariant : concept.invariants()) {
                rules.add(ruleDetail(invariant, "notAppliedToQueryOperation", true));
            }
            return allowWithSemanticDetails(request, ruleProfiles, rules, "queryRestrictionsExplicit");
        }

        Map<String, Object> facts = new LinkedHashMap<>(request.data());
        facts.put("operation", request.operation().name().toLowerCase(Locale.ROOT));
        facts.put("concept", request.conceptName());
        facts.put("tenantId", request.tenantId());
        List<Map<String, Object>> rulesEvaluated = new ArrayList<>();
        for (InvariantDefinition invariant : concept.invariants()) {
            String expression = invariant.expression();
            if (!isSupportedBooleanExpression(expression)) {
                // This policy only understands a small comparison/uniqueBy grammar; richer
                // invariants (e.g. conflict-detection functions like overlapsProvider(...))
                // are already fully validated by the kernel's CEL invariant engine before this
                // gateway-side check runs (see GeneratedCrudRuntimeSupport.enforceWithKernel),
                // so we skip rather than deny instead of double-rejecting on syntax we can't parse.
                rulesEvaluated.add(ruleDetail(invariant, "skippedUnsupportedExpression", true));
                continue;
            }
            boolean passed = evaluateBooleanExpression(expression, facts);
            rulesEvaluated.add(ruleDetail(invariant, passed ? "passed" : "failed", passed));
            if (!passed) {
                return ConceptSemanticDecision.deny(
                        "CONCEPT_INVARIANT_REJECTED",
                        "Concept invariant rejected operation: " + invariant.name(),
                        Map.of(
                                "concept", request.conceptName(),
                                "invariant", invariant.name(),
                                "expression", expression,
                                "operation", request.operation().name(),
                                "rulesEvaluated", rulesEvaluated
                        )
                ).withRuleProfiles(ruleProfiles);
            }
        }
        return allowWithSemanticDetails(request, ruleProfiles, rulesEvaluated, "allowed");
    }

    @Override
    public ConceptRecord filterVisibleFields(ConceptRecord record, ConceptGatewayRequestContext request) {
        ConceptDefinition concept = concept(request);
        if (concept == null || (concept.hiddenFields().isEmpty() && !hasFieldReadAccessRules(concept))) {
            return record;
        }
        Map<String, Object> visible = new LinkedHashMap<>(record.data());
        for (String hiddenField : concept.hiddenFields()) {
            visible.remove(hiddenField);
        }
        // R5.5: field-level read authorization -- evaluated against the RECORD'S OWN RAW data
        // (record.data(), not the partially-filtered `visible` map), same reasoning LNCH-13's
        // row-level check already documents above: a hidden field the rule references, or a field
        // whose own access.read rule references ANOTHER field this loop hasn't reached yet, must
        // still be visible to the expression itself. A denied field is OMITTED from the response
        // entirely (removed from `visible`), never returned masked/null -- returning it as null
        // would still confirm to the caller that a value is present, just not what it is; the
        // caller cannot tell a denied field apart from a field that was never set.
        for (FieldDefinition field : concept.fields().values()) {
            if (field.access() == null || !hasText(field.access().read())) {
                continue;
            }
            if (!visible.containsKey(field.name())) {
                continue;
            }
            if (!evaluateAccessRule(field.access().read(), record.data(), request.executionContext())) {
                visible.remove(field.name());
            }
        }
        return new ConceptRecord(record.conceptName(), record.id(), record.tenantId(), visible);
    }

    /** R5.5: does ANY field on this concept declare a {@code field.access.read} rule? Guards the
     *  fast path in {@link #filterVisibleFields} the same way {@link #hasRowReadScope} guards
     *  {@code query}'s extra count -- a concept with no field-level read rule at all pays nothing
     *  extra. */
    private static boolean hasFieldReadAccessRules(ConceptDefinition concept) {
        for (FieldDefinition field : concept.fields().values()) {
            if (field.access() != null && hasText(field.access().read())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isRowReadable(ConceptRecord record, ConceptGatewayRequestContext request) {
        ConceptDefinition concept = concept(request);
        if (concept == null || concept.access() == null || !hasText(concept.access().read())) {
            return true;
        }
        return evaluateAccessRule(concept.access().read(), record.data(), request.executionContext());
    }

    @Override
    public boolean hasRowReadScope(String conceptName) {
        ConceptDefinition concept = conceptsByName.get(normalizeKey(conceptName));
        return concept != null && concept.access() != null && hasText(concept.access().read());
    }

    @Override
    public Optional<String> resolveReferenceTarget(String conceptName, String fieldName) {
        ConceptDefinition concept = conceptsByName.get(normalizeKey(conceptName));
        if (concept == null) {
            return Optional.empty();
        }
        FieldDefinition field = concept.fields().get(fieldName);
        if (field == null || field.referenceTarget() == null || field.referenceTarget().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(field.referenceTarget());
    }

    @Override
    public boolean isRowWritable(ConceptGatewayRequestContext request) {
        ConceptDefinition concept = concept(request);
        if (concept == null || concept.access() == null || !hasText(concept.access().write())) {
            return true;
        }
        // The previous record (update/delete) if present, else the incoming data (create) --
        // see the interface javadoc for why: a caller must not be able to modify/delete a row
        // outside their own scope, NOR create one claiming ownership outside their own scope.
        Map<String, Object> subject = request.previousRecord()
                .map(ConceptRecord::data)
                .orElse(request.data());
        return evaluateAccessRule(concept.access().write(), subject, request.executionContext());
    }

    /**
     * R5.5: field-level write authorization -- see the interface javadoc for the full contract.
     * Mirrors {@link #isRowWritable}'s subject choice (previous record if present, else incoming
     * data) so a field rule can reference sibling fields the same way {@code concept.access.write}
     * already can. Only a field the caller actually attempted to CHANGE is ever evaluated: a field
     * absent from the incoming data, or present with the SAME value the record already had, is
     * skipped -- this is what lets a client resend the whole record (a plain HTML form's readonly
     * input still round-trips its current value) without tripping a denial on fields it never
     * touched.
     */
    @Override
    public List<String> deniedWriteFields(ConceptGatewayRequestContext request) {
        ConceptDefinition concept = concept(request);
        if (concept == null) {
            return List.of();
        }
        Map<String, Object> incoming = request.data();
        Map<String, Object> previousData = request.previousRecord().map(ConceptRecord::data).orElse(Map.of());
        Map<String, Object> subject = request.previousRecord().map(ConceptRecord::data).orElse(incoming);
        List<String> denied = new ArrayList<>();
        for (FieldDefinition field : concept.fields().values()) {
            if (field.access() == null || !hasText(field.access().write())) {
                continue;
            }
            if (!incoming.containsKey(field.name())) {
                continue;
            }
            Object incomingValue = incoming.get(field.name());
            Object previousValue = previousData.get(field.name());
            if (Objects.equals(normalizeComparable(incomingValue), normalizeComparable(previousValue))) {
                continue;
            }
            if (!evaluateAccessRule(field.access().write(), subject, request.executionContext())) {
                denied.add(field.name());
            }
        }
        return List.copyOf(denied);
    }

    /**
     * REG-195: an access rule is evaluated via the plain two-argument {@code evaluateBoolean},
     * which resolves to an EMPTY {@link ComputedExpression.FunctionRegistry} -- so
     * {@code $user.roles.contains(...)}, the only idiom this platform's own docs/corpus use for
     * "does the actor have role X" inside {@code access.read}/{@code access.write}, throws
     * "unknown function: contains" and the catch below turned that into a permanent, silent deny
     * regardless of the actor's roles. {@code contains} does not exist in ANY {@code
     * ComputedExpression} caller yet (not just this one) -- it is registered here, scoped to
     * access-rule evaluation, following the extension pattern {@code docs/EXPRESSIONS.md} already
     * documents ("a function registry is just a Map ... a future call site can register its own
     * without touching the grammar itself") rather than reaching into the invariant engine's
     * adapter-side registry, which kernel has no dependency on.
     */
    private static final ComputedExpression.FunctionRegistry ACCESS_RULE_FUNCTIONS =
            ComputedExpression.FunctionRegistry.of(Map.of(
                    "contains", (args, vars) -> {
                        Object receiver = args.get(0).eval(vars);
                        Object needle = args.get(1).eval(vars);
                        if (receiver instanceof java.util.Collection<?> collection) {
                            return collection.contains(needle);
                        }
                        if (receiver instanceof String haystack) {
                            return needle != null && haystack.contains(String.valueOf(needle));
                        }
                        return false;
                    }
            ));

    private static boolean evaluateAccessRule(String expression, Map<String, Object> recordData, ExecutionContext context) {
        Map<String, Object> scope = new LinkedHashMap<>(recordData == null ? Map.of() : recordData);
        ExecutionContext effectiveContext = context == null ? ExecutionContext.anonymous() : context;
        scope.put("$user.id", effectiveContext.actorId());
        scope.put("$user.actorId", effectiveContext.actorId());
        scope.put("$user.tenantId", effectiveContext.tenantId());
        scope.put("$user.roles", effectiveContext.roles());
        try {
            return ComputedExpression.evaluateBoolean(expression, scope, ACCESS_RULE_FUNCTIONS);
        } catch (ComputedExpression.ExpressionException malformed) {
            // Fail closed: a row-level access rule that doesn't evaluate cleanly must never
            // silently grant access -- SemanticValidator already rejects this at model-compile
            // time, so reaching this at runtime means something bypassed validation (e.g. a
            // hand-edited compiled model); denying is the only safe default.
            return false;
        }
    }

    private static boolean canApplyDefault(ConceptDefinition concept, FieldDefinition field) {
        if (field.defaultValue() != null || hasText(field.defaultExpression())) {
            return true;
        }
        return concept.lifecycle() != null
                && field.name().equals(concept.lifecycle().statusField())
                && hasText(concept.lifecycle().initialState());
    }

    private ConceptDefinition concept(ConceptGatewayRequestContext request) {
        return conceptsByName.get(normalizeKey(request.conceptName()));
    }

    /** Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): delegates to the shared {@link ValueExpressionEvaluator}
     * so a new row's default and an existing row's expression-default backfill preview compute
     * identically -- pure extraction, no behavior change. */
    private static Object evaluateValueExpression(String expression, Map<String, Object> data) {
        return ValueExpressionEvaluator.evaluate(expression, data);
    }

    /**
     * R5.3: a field's {@code defaultExpression} evaluator, widened to recognize {@code
     * nextNumber('name')} BEFORE falling through to the generic pure {@link
     * ValueExpressionEvaluator} -- allocation is a real side effect ({@link #sequenceAllocator}
     * may be JDBC-backed), which {@link ValueExpressionEvaluator} deliberately never is (it stays
     * pure so a backfill preview can call it too without allocating anything). Only reachable from
     * {@code defaultExpression} -- {@code derivedExpression} never routes through this method (see
     * {@link #applyDefaultsAndDerivedValues}), because a derived value is recomputed on every save
     * and would otherwise burn a fresh number on every unrelated update; {@code
     * SequenceValidation} (DSL) refuses {@code nextNumber()} there at author time.
     *
     * <p>An unrecognized sequence name (should already be impossible -- {@code SequenceValidation}
     * requires every {@code nextNumber('name')} to reference a declared sequence) falls through to
     * the generic evaluator rather than throwing, so a hand-edited compiled model degrades the same
     * way any other unrecognized function call does (evaluates to null) instead of failing the
     * write outright.
     */
    private Object evaluateFieldDefault(String expression, Map<String, Object> data, String tenantId) {
        Matcher matcher = NEXT_NUMBER_PATTERN.matcher(expression == null ? "" : expression.trim());
        if (matcher.matches()) {
            CompiledSequence sequence = sequencesByName.get(normalizeKey(matcher.group(1)));
            if (sequence != null) {
                return allocateSequenceNumber(sequence, tenantId);
            }
        }
        return evaluateValueExpression(expression, data);
    }

    /** R5.3: composes the allocator's scope key (sequence name + tenant segment, if {@code scope:
     *  "tenant"} + any date-bucket {@link SequenceNumberFormat#scopeKeySuffix} derives from the
     *  format's own date tokens) and renders the allocated counter value through the sequence's
     *  {@code format} template. */
    private Object allocateSequenceNumber(CompiledSequence sequence, String tenantId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String scopeKey = sequence.name()
                + ("tenant".equals(sequence.scope()) ? "|" + (tenantId == null ? "" : tenantId) : "")
                + SequenceNumberFormat.scopeKeySuffix(sequence.format(), today);
        long next = sequenceAllocator.allocateNext(scopeKey);
        return SequenceNumberFormat.render(sequence.format(), next, today);
    }

    private static final Pattern NEXT_NUMBER_PATTERN =
            Pattern.compile("^nextNumber\\(\\s*['\"]([^'\"]*)['\"]\\s*\\)$");

    private static final Pattern UNIQUE_BY_PATTERN =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\.uniqueBy\\(([A-Za-z_][A-Za-z0-9_]*)\\)$");

    private static boolean evaluateBooleanExpression(String expression, Map<String, Object> facts) {
        String text = expression == null ? "" : expression.trim();
        if (text.isEmpty()) {
            return true;
        }
        Matcher uniqueByMatcher = UNIQUE_BY_PATTERN.matcher(text);
        if (uniqueByMatcher.matches()) {
            return evaluateUniqueBy(uniqueByMatcher.group(1), uniqueByMatcher.group(2), facts);
        }
        for (String disjunct : text.split("\\s+\\|\\|\\s+")) {
            boolean all = true;
            for (String conjunct : disjunct.split("\\s+&&\\s+")) {
                all = all && evaluateComparison(conjunct.trim(), facts);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static boolean evaluateUniqueBy(String fieldName, String subfield, Map<String, Object> facts) {
        Object value = facts.get(fieldName);
        if (!(value instanceof List<?> list)) {
            return true;
        }
        Set<Object> seen = new LinkedHashSet<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> elementMap)) {
                continue;
            }
            Object subValue = elementMap.get(subfield);
            if (subValue == null) {
                continue;
            }
            if (!seen.add(normalizeComparable(subValue))) {
                return false;
            }
        }
        return true;
    }

    private static boolean evaluateComparison(String expression, Map<String, Object> facts) {
        Matcher matcher = Pattern.compile("^([A-Za-z_][A-Za-z0-9_.-]*)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$").matcher(expression);
        if (!matcher.matches()) {
            return false;
        }
        Object left = facts.get(matcher.group(1));
        String operator = matcher.group(2);
        Object right = literal(matcher.group(3), facts);
        int comparison = compare(left, right);
        return switch (operator) {
            case "==" -> Objects.equals(normalizeComparable(left), normalizeComparable(right));
            case "!=" -> !Objects.equals(normalizeComparable(left), normalizeComparable(right));
            case ">=" -> comparison >= 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case "<" -> comparison < 0;
            default -> false;
        };
    }

    private static boolean isSupportedBooleanExpression(String expression) {
        String text = expression == null ? "" : expression.trim();
        if (text.isEmpty()) {
            return true;
        }
        if (UNIQUE_BY_PATTERN.matcher(text).matches()) {
            return true;
        }
        for (String disjunct : text.split("\\s+\\|\\|\\s+")) {
            for (String conjunct : disjunct.split("\\s+&&\\s+")) {
                String item = conjunct.trim();
                if (item.isEmpty()) {
                    continue;
                }
                if (!Pattern.compile("^([A-Za-z_][A-Za-z0-9_.-]*)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$").matcher(item).matches()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static ConceptSemanticDecision allowWithSemanticDetails(
            ConceptGatewayRequestContext request,
            List<ConceptRuleProfile> ruleProfiles,
            List<Map<String, Object>> rulesEvaluated,
            String decisionOutcome
    ) {
        return new ConceptSemanticDecision(
                true,
                "allowed",
                "allowed",
                request.data(),
                List.of(),
                List.of(),
                List.of(),
                null,
                Map.of(
                        "operation", request.operation().name(),
                        "concept", request.conceptName(),
                        "decisionOutcome", decisionOutcome,
                        "rulesEvaluated", rulesEvaluated
                )
        ).withRuleProfiles(ruleProfiles);
    }

    private static Map<String, Object> ruleDetail(InvariantDefinition invariant, String outcome, boolean allowed) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rule", invariant.name());
        detail.put("expression", invariant.expression());
        detail.put("outcome", outcome);
        detail.put("allowed", allowed);
        return Map.copyOf(detail);
    }

    private static Object literal(String raw, Map<String, Object> facts) {
        String text = raw == null ? "" : raw.trim();
        if ("null".equalsIgnoreCase(text)) {
            return null;
        }
        if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""))) {
            return text.substring(1, text.length() - 1);
        }
        if (facts.containsKey(text)) {
            return facts.get(text);
        }
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    private static int compare(Object left, Object right) {
        Object normalizedLeft = normalizeComparable(left);
        Object normalizedRight = normalizeComparable(right);
        if (normalizedLeft instanceof Number leftNumber && normalizedRight instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        }
        return String.valueOf(normalizedLeft).compareTo(String.valueOf(normalizedRight));
    }

    private static Object normalizeComparable(Object value) {
        if (value instanceof Number) {
            return value;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    private static boolean isBlankValue(Object value) {
        return value == null || (value instanceof CharSequence text && text.toString().trim().isEmpty());
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record ConceptDefinition(
            String name,
            Map<String, FieldDefinition> fields,
            List<InvariantDefinition> invariants,
            LifecycleDefinition lifecycle,
            Set<String> hiddenFields,
            AccessRules access
    ) {
        public ConceptDefinition {
            name = Objects.requireNonNull(name, "name");
            fields = fields == null ? Map.of() : Map.copyOf(fields);
            invariants = invariants == null ? List.of() : List.copyOf(invariants);
            hiddenFields = hiddenFields == null ? Set.of() : Set.copyOf(hiddenFields);
        }

        public ConceptDefinition(
                String name,
                Map<String, FieldDefinition> fields,
                List<InvariantDefinition> invariants,
                LifecycleDefinition lifecycle,
                Set<String> hiddenFields
        ) {
            this(name, fields, invariants, lifecycle, hiddenFields, null);
        }

        public static ConceptDefinition of(
                String name,
                List<FieldDefinition> fields,
                List<InvariantDefinition> invariants,
                LifecycleDefinition lifecycle
        ) {
            Map<String, FieldDefinition> byName = new LinkedHashMap<>();
            for (FieldDefinition field : fields == null ? List.<FieldDefinition>of() : fields) {
                byName.put(field.name(), field);
            }
            Set<String> hiddenFields = new LinkedHashSet<>();
            for (FieldDefinition field : fields == null ? List.<FieldDefinition>of() : fields) {
                if (field.hidden()) {
                    hiddenFields.add(field.name());
                }
            }
            return new ConceptDefinition(name, byName, invariants, lifecycle, hiddenFields);
        }
    }

    public record FieldDefinition(
            String name,
            boolean required,
            List<String> enumValues,
            Object defaultValue,
            String defaultExpression,
            String derivedExpression,
            boolean hidden,
            /** S4: this field's declared {@code reference.target} concept name, or null if this
             *  field isn't a reference at all. */
            String referenceTarget,
            /** R5.5: this field's own declared {read, write} authorization rule, or null if this
             *  field declares no field-level rule. Reuses {@link AccessRules}, the same shape
             *  {@code concept.access} uses -- field scope is one rung down the SAME ladder. */
            AccessRules access
    ) {
        public FieldDefinition(
                String name,
                boolean required,
                List<String> enumValues,
                Object defaultValue,
                String defaultExpression,
                String derivedExpression
        ) {
            this(name, required, enumValues, defaultValue, defaultExpression, derivedExpression, false, null, null);
        }

        /** S4: pre-existing 7-arg shape (name..hidden, no referenceTarget), preserved so callers
         *  that predate the reference-target widening keep compiling unchanged. */
        public FieldDefinition(
                String name,
                boolean required,
                List<String> enumValues,
                Object defaultValue,
                String defaultExpression,
                String derivedExpression,
                boolean hidden
        ) {
            this(name, required, enumValues, defaultValue, defaultExpression, derivedExpression, hidden, null, null);
        }

        /** S4/R5.5 boundary: pre-existing 8-arg shape (name..referenceTarget, no access), preserved
         *  so callers that predate field-level access keep compiling unchanged. */
        public FieldDefinition(
                String name,
                boolean required,
                List<String> enumValues,
                Object defaultValue,
                String defaultExpression,
                String derivedExpression,
                boolean hidden,
                String referenceTarget
        ) {
            this(name, required, enumValues, defaultValue, defaultExpression, derivedExpression, hidden, referenceTarget, null);
        }

        public FieldDefinition {
            name = Objects.requireNonNull(name, "name");
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }
    }

    public record InvariantDefinition(String name, String expression) {
        public InvariantDefinition {
            name = hasText(name) ? name.trim() : "anonymousInvariant";
            expression = expression == null ? "" : expression.trim();
        }
    }

    public record LifecycleDefinition(
            String statusField,
            String initialState,
            Set<String> states,
            Set<StateTransition> transitions
    ) {
        public LifecycleDefinition {
            states = states == null ? Set.of() : Set.copyOf(states);
            transitions = transitions == null ? Set.of() : Set.copyOf(transitions);
        }

        public static LifecycleDefinition of(
                String statusField,
                String initialState,
                List<String> states,
                List<StateTransition> transitions
        ) {
            return new LifecycleDefinition(
                    statusField,
                    initialState,
                    states == null ? Set.of() : new LinkedHashSet<>(states),
                    transitions == null ? Set.of() : new LinkedHashSet<>(transitions)
            );
        }
    }

    public record StateTransition(String from, String to) {
        public StateTransition {
            from = from == null ? "" : from.trim();
            to = to == null ? "" : to.trim();
        }
    }

    /** LNCH-13: a concept's declarative row-level authorization rule (access: {read, write}). */
    public record AccessRules(String read, String write) {
        public AccessRules {
            read = (read == null || read.isBlank()) ? null : read.trim();
            write = (write == null || write.isBlank()) ? null : write.trim();
        }
    }
}
