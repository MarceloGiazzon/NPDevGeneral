package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.CapabilityAst;
import com.npdev.dsl.v1.ast.ConceptAccessAst;
import com.npdev.dsl.v1.ast.CapabilityBindingAst;
import com.npdev.dsl.v1.ast.CapabilityOperationAst;
import com.npdev.dsl.v1.ast.DomainTypeAst;
import com.npdev.dsl.v1.ast.CapabilityPolicyAst;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.EventAst;
import com.npdev.dsl.v1.ast.EventPayloadAst;
import com.npdev.dsl.v1.ast.ExternalAiAst;
import com.npdev.dsl.v1.ast.EnumOptionAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.FlowScheduleAst;
import com.npdev.dsl.v1.ast.InvariantAst;
import com.npdev.dsl.v1.ast.LifecycleAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.OrchestrationActionAst;
import com.npdev.dsl.v1.ast.OrchestrationAst;
import com.npdev.dsl.v1.ast.OrchestrationTriggerAst;
import com.npdev.dsl.v1.ast.AggregateAst;
import com.npdev.dsl.v1.ast.AggregateCollectionAst;
import com.npdev.dsl.v1.ast.AutoPanelAst;
import com.npdev.dsl.v1.ast.AutoPanelComputedAst;
import com.npdev.dsl.v1.ast.AutoPanelSurfaceAst;
import com.npdev.dsl.v1.ast.SelectorAst;
import com.npdev.dsl.v1.ast.GuidePageAst;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.dsl.v1.ast.GuidePageGadgetAst;
import com.npdev.dsl.v1.compiled.FieldWidgetDefaults;
import com.npdev.dsl.v1.compiled.GuidePageDefaults;
import com.npdev.dsl.v1.ast.PanelActionAst;
import com.npdev.dsl.v1.ast.PanelAst;
import com.npdev.dsl.v1.ast.PanelDataSourceAst;
import com.npdev.dsl.v1.ast.PresentationMetadataAst;
import com.npdev.dsl.v1.ast.ProcedureAst;
import com.npdev.dsl.v1.ast.ProcedureParameterAst;
import com.npdev.dsl.v1.ast.ProcedureStepAst;
import com.npdev.dsl.v1.ast.QueryAst;
import com.npdev.dsl.v1.ast.ReferenceSemanticsAst;
import com.npdev.dsl.v1.ast.RuleProfileAst;
import com.npdev.dsl.v1.ast.TruthLevel;
import com.npdev.dsl.v1.ast.SchemaAst;
import com.npdev.dsl.v1.ast.StateMachineStateAst;
import com.npdev.dsl.v1.ast.StateTransitionAst;
import com.npdev.dsl.v1.ast.StepAst;
import com.npdev.dsl.v1.resolution.ModelResolutionException;
import com.npdev.dsl.v1.resolution.ModelResolver;
import com.npdev.dsl.v1.resolution.ResolvedModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.npdev.dsl.v1.validation.ConceptValidation.EffectiveEntity;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;
import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.ConceptValidation.KNOWN_TYPES;
import static com.npdev.dsl.v1.validation.ConceptValidation.resolveEffective;
import static com.npdev.dsl.v1.validation.PackValidation.validateCapabilityPolicy;
import static com.npdev.dsl.v1.validation.PackValidation.validateReferencedCapabilityBindings;

/**
 * Semantic validation for events, flows (steps of every kind), and declarative orchestration rules
 * (event-triggered actions). Mirrors the {@code events} / {@code flows} / {@code orchestrationRules}
 * sections of {@code model.schema.json}, which sit adjacent to each other in that schema.
 *
 * <p>Split out of {@code SemanticValidator} (T1.15); see that class for the orchestration entry
 * point.
 */
final class FlowValidation {

    private FlowValidation() {
    }

    private static final Map<String, Set<String>> BUILTIN_CAPABILITY_OPERATIONS = Map.of(
            "persistencecapability", Set.of("save", "delete", "query", "exists"),
            "persistence", Set.of("save", "delete", "query", "exists"),
            "messagingcapability", Set.of("publish", "subscribe", "unsubscribe"),
            "emailcapability", Set.of("send"),
            "fiscalcapability", Set.of("generatexml", "sign", "transmit", "querystatus"),
            "signaturecapability", Set.of("sign", "verify"),
            "eventbus", Set.of("publish", "subscribe", "unsubscribe"),
            "invariantengine", Set.of("evaluate")
    );

    static void validateEvents(ModelAst modelAst, List<String> errors) {
        Set<String> names = new HashSet<>();
        for (EventAst event : modelAst.getEvents()) {
            if (!names.add(normalize(event.getName()))) {
                errors.add("Duplicate event name: " + event.getName());
            }

            Set<String> payloadNames = new HashSet<>();
            for (EventPayloadAst payloadField : event.getPayloadFields()) {
                String fieldName = normalize(payloadField.getName());
                if (fieldName.isBlank()) {
                    errors.add("Event " + event.getName() + ": payload field name must be non-blank");
                    continue;
                }
                if (!payloadNames.add(fieldName)) {
                    errors.add("Event " + event.getName() + ": duplicate payload field " + payloadField.getName());
                }
                String payloadType = normalize(payloadField.getType());
                if (!payloadType.isBlank() && !KNOWN_TYPES.contains(payloadType)) {
                    errors.add("Event " + event.getName() + ": unknown payload type " + payloadField.getType());
                }
            }
        }
    }

    /**
     * LNCH-12: structural-only cron check (field count, not full cron grammar -- this module has
     * no Spring dependency to reuse {@code CronExpression.parse} for real validation, and the
     * runtime scheduler will reject a malformed expression loudly at boot anyway). Accepts both
     * the classic 5-field cron (minute hour day month weekday) and Spring's 6-field
     * seconds-first form, since the DoD explicitly calls for shrinking a schedule to seconds in a
     * gate test.
     */
    private static void validateFlowSchedule(FlowAst flow, List<String> errors) {
        FlowScheduleAst schedule = flow.getSchedule();
        if (schedule == null) {
            return;
        }
        String cron = schedule.getCron();
        if (cron == null || cron.isBlank()) {
            errors.add("Flow " + flow.getName() + ": schedule.cron must not be blank");
            return;
        }
        int fieldCount = cron.trim().split("\\s+").length;
        if (fieldCount != 5 && fieldCount != 6) {
            errors.add("Flow " + flow.getName() + ": schedule.cron must have 5 or 6 space-separated fields, got "
                    + fieldCount + " (\"" + cron + "\")");
        }
        for (String tenantId : schedule.getTenantScope()) {
            if (tenantId == null || tenantId.isBlank()) {
                errors.add("Flow " + flow.getName() + ": schedule.tenantScope must not contain blank entries");
                break;
            }
        }
    }

    static void validateFlows(
            ModelAst modelAst,
            Map<String, ConceptAst> entitiesByLower,
            Map<String, EffectiveEntity> effectiveCache,
            boolean allowUnboundFlowCapabilities,
            List<String> errors,
            List<String> warnings
    ) {
        Set<String> flowNames = new HashSet<>();
        Map<String, Set<String>> operationsByCapability = resolveCapabilityOperations(modelAst);
        Set<String> eventNames = modelAst.getEvents().stream()
                .map(EventAst::getName)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Set<String> referencedCapabilities = new HashSet<>();
        Map<String, String> ownedConceptToAggregate = AggregateValidation.ownedConceptToAggregate(modelAst);
        // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): callProcedure needs to know which
        // procedure names actually exist, same as a capability step already checks operationsByCapability.
        Set<String> knownProcedureNames = modelAst.getProcedures().stream()
                .map(ProcedureAst::name)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());

        for (FlowAst flow : modelAst.getFlows()) {
            String flowKey = normalize(flow.getName());
            if (!flowNames.add(flowKey)) {
                errors.add("Duplicate flow name: " + flow.getName());
            }

            if (flow.getSteps().isEmpty()) {
                errors.add("Flow " + flow.getName() + ": steps must not be empty");
            }

            validateFlowSchedule(flow, errors);

            ConceptAst concept = entitiesByLower.get(normalize(flow.getConcept()));
            if (concept == null) {
                errors.add("Flow " + flow.getName() + ": references unknown concept " + flow.getConcept());
                continue;
            }

            EffectiveEntity effectiveConcept = resolveEffective(
                    concept,
                    entitiesByLower,
                    effectiveCache,
                    new HashSet<>(),
                    errors
            );
            Set<String> conceptInvariantRefs = collectInvariantReferences(flow, concept, effectiveConcept);
            validateFlowSteps(
                    flow,
                    flow.getSteps(),
                    operationsByCapability,
                    eventNames,
                    conceptInvariantRefs,
                    referencedCapabilities,
                    knownProcedureNames,
                    new HashSet<>(),
                    errors
            );
            validateAggregateTransactionalBoundary(flow, ownedConceptToAggregate, errors);
            warnCreateOrUpdateFlowWithoutPersistenceSemantics(flow, warnings);
        }

        if (!allowUnboundFlowCapabilities) {
            validateReferencedCapabilityBindings(modelAst, referencedCapabilities, errors);
        }
    }

    private static void warnCreateOrUpdateFlowWithoutPersistenceSemantics(FlowAst flow, List<String> warnings) {
        if (flow == null || warnings == null) {
            return;
        }
        String mode = normalize(flow.getMode());
        if (!"create".equals(mode) && !"update".equals(mode)) {
            return;
        }
        if (hasPersistenceSemantics(flow.getSteps())) {
            return;
        }
        warnings.add("Flow " + flow.getName() + ": input mode '" + flow.getMode()
                + "' does not imply persistence by name or mode alone. Declare an explicit createConcept, "
                + "updateConcept, saveConcept, or persistence.save step to persist business data.");
    }

    private static boolean hasPersistenceSemantics(List<StepAst> steps) {
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        for (StepAst step : steps) {
            if (step == null) {
                continue;
            }
            String type = normalize(step.getType());
            if ("createconcept".equals(type)
                    || "updateconcept".equals(type)
                    || "saveconcept".equals(type)
                    || "createentity".equals(type)
                    || "updateentity".equals(type)
                    || "saveentity".equals(type)) {
                return true;
            }
            String capability = normalize(step.getCapability());
            String operation = normalize(step.getOperation());
            if (("capability".equals(type) || "capabilitycall".equals(type))
                    && "persistence".equals(capability)
                    && ("save".equals(operation) || "delete".equals(operation))) {
                return true;
            }
            // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): a callProcedure step commonly
            // invokes a procedure that persists (saveConcept/patchConcept) -- this heuristic can't
            // see inside the named procedure's own steps, but treating callProcedure itself as
            // persistence-implying (like the capability/persistence.save case above) avoids a false
            // "does not imply persistence" warning on the one flow-step type invented specifically
            // to reach patchConcept.
            if ("callprocedure".equals(type)) {
                return true;
            }
            if (hasPersistenceSemantics(step.getThenSteps()) || hasPersistenceSemantics(step.getElseSteps())) {
                return true;
            }
        }
        return false;
    }

    /**
     * P6.1 (docs/NEXT_EXECUTION_PLAN.md 3.7): DDD's core rule -- one aggregate = one transaction =
     * one consistency boundary. {@code aggregates} carry ownership, but nothing previously enforced
     * that a single flow may not write concept-mutation steps belonging to two DIFFERENT aggregates.
     * Makes the construct load-bearing instead of descriptive.
     *
     * <p>Scope, stated rather than silently assumed complete: only the four alias step types
     * ({@code createConcept}/{@code updateConcept}/{@code createEntity}/{@code updateEntity}) are
     * traced, via their required {@code scope} field -- the one reliable, statically-resolvable
     * concept-write signal ({@link #validatePersistenceMutationAliasStep}). A raw
     * {@code capability: persistence, operation: save|delete} step (also a legal way to persist, per
     * {@link #hasPersistenceSemantics}) is NOT traced: its target concept is not a structured field,
     * only opaque {@code input}/{@code args}, so it cannot be resolved without runtime argument
     * evaluation this validator does not do. A concept not owned by any declared aggregate is not a
     * violation -- the rule only applies where an aggregate boundary actually exists to cross.
     */
    private static void validateAggregateTransactionalBoundary(
            FlowAst flow, Map<String, String> ownedConceptToAggregate, List<String> errors) {
        if (ownedConceptToAggregate.isEmpty()) {
            return;
        }
        Set<String> mutatedConcepts = new LinkedHashSet<>();
        collectConceptMutationScopes(flow.getSteps(), mutatedConcepts);

        Map<String, Set<String>> aggregatesByTouchedConcepts = new LinkedHashMap<>();
        for (String concept : mutatedConcepts) {
            String aggregate = ownedConceptToAggregate.get(concept);
            if (aggregate != null) {
                aggregatesByTouchedConcepts.computeIfAbsent(aggregate, key -> new TreeSet<>()).add(concept);
            }
        }
        if (aggregatesByTouchedConcepts.size() <= 1) {
            return;
        }
        String detail = aggregatesByTouchedConcepts.entrySet().stream()
                .map(entry -> entry.getKey() + " (" + String.join(", ", entry.getValue()) + ")")
                .collect(Collectors.joining("; "));
        errors.add("Flow " + flow.getName() + ": writes concepts across " + aggregatesByTouchedConcepts.size()
                + " different aggregates in one flow: " + detail
                + " -- DDD's one-aggregate-one-transaction rule: split this into one flow per "
                + "aggregate, coordinated by a domain event, rather than writing both roots here."
                + " -- suggestedFix: keep the writes of one aggregate in this flow, move the rest into a "
                + "second flow, and have this one emit an event the second flow subscribes to.");
    }

    private static void collectConceptMutationScopes(List<StepAst> steps, Set<String> scopes) {
        if (steps == null) {
            return;
        }
        for (StepAst step : steps) {
            if (step == null) {
                continue;
            }
            String type = normalize(step.getType());
            if ("createentity".equals(type) || "updateentity".equals(type)
                    || "createconcept".equals(type) || "updateconcept".equals(type)) {
                String scope = normalize(step.getScope());
                if (!scope.isBlank()) {
                    scopes.add(scope);
                }
            }
            collectConceptMutationScopes(step.getThenSteps(), scopes);
            collectConceptMutationScopes(step.getElseSteps(), scopes);
            collectConceptMutationScopes(step.getLoopSteps(), scopes);
        }
    }

    private static void validateFlowSteps(
            FlowAst flow,
            List<StepAst> steps,
            Map<String, Set<String>> operationsByCapability,
            Set<String> eventNames,
            Set<String> conceptInvariantRefs,
            Set<String> referencedCapabilities,
            Set<String> knownProcedureNames,
            Set<String> knownStepNames,
            List<String> errors
    ) {
        for (StepAst step : steps) {
            String normalizedName = normalize(step.getName());
            if (!normalizedName.isBlank() && !knownStepNames.add(normalizedName)) {
                errors.add("Flow " + flow.getName() + ": duplicate step name " + step.getName());
            }

            String stepType = normalize(step.getType());
            switch (stepType) {
                case "invariant" -> validateInvariantStep(flow, step, conceptInvariantRefs, errors);
                case "capability" -> validateCapabilityStep(
                        flow,
                        step,
                        operationsByCapability,
                        referencedCapabilities,
                        errors
                );
                case "createentity", "updateentity", "createconcept", "updateconcept" -> validatePersistenceMutationAliasStep(
                        flow,
                        step,
                        operationsByCapability,
                        referencedCapabilities,
                        errors
                );
                case "event" -> validateEventStep(flow, step, eventNames, errors);
                case "scheduleevent" -> validateScheduleEventStep(flow, step, eventNames, errors);
                case "return" -> validateReturnStep(flow, step, errors);
                case "map" -> validateMapStep(flow, step, errors);
                case "branch" -> validateBranchStep(
                        flow,
                        step,
                        operationsByCapability,
                        eventNames,
                        conceptInvariantRefs,
                        referencedCapabilities,
                        knownProcedureNames,
                        knownStepNames,
                        errors
                );
                case "await" -> validateAwaitStep(
                        flow,
                        step,
                        operationsByCapability,
                        eventNames,
                        conceptInvariantRefs,
                        referencedCapabilities,
                        knownProcedureNames,
                        knownStepNames,
                        errors
                );
                case "generatedaction" -> validateGeneratedActionStep(flow, step, errors);
                case "foreach" -> validateForEachStep(
                        flow,
                        step,
                        operationsByCapability,
                        eventNames,
                        conceptInvariantRefs,
                        referencedCapabilities,
                        knownProcedureNames,
                        knownStepNames,
                        errors
                );
                case "callprocedure" -> validateCallProcedureStep(flow, step, knownProcedureNames, errors);
                default -> errors.add("Flow " + flow.getName() + " step " + step.getName()
                        + ": unsupported step type " + step.getType());
            }
        }
    }

    /** LIFT-LOOP-P4: flow state keys a forEach itemKey must never shadow -- KernelRunner writes
     * {@code state.put(itemKey, item)} on every iteration, so reusing one of these silently
     * clobbers framework-critical state (e.g. itemKey="input" would overwrite the flow's own
     * input mid-loop, corrupting every later {@code input.*} reference in that same iteration). */
    private static final Set<String> RESERVED_FLOW_STATE_KEYS = Set.of(
            "input", "last", "executionid", "correlationid", "causationid",
            "tenantid", "actorid", "_npdeventityname"
    );

    private static final int MAX_LOOP_ITERATIONS_CEILING = 1_000_000;

    /**
     * LIFT-LOOP-P1/P4, B15(A) (Move 16), B15(B) (S6): {@code forEach} flow step validation.
     * Collection/itemKey/non-empty body are already required by the JSON Schema; this adds the
     * semantic checks the schema can't express: item-var shadowing (of reserved flow state, the
     * loop's own collection, and any enclosing loop's item variable), a sane ceiling on
     * {@code maxLoopIterations}, and a cap of one reachable {@code await} step per loop body.
     * That cap is deliberately narrower than "any number of awaits are fine now" -- B15(A)'s own
     * kernel-side mechanism (see {@code ForEachStep}'s javadoc in the kernel module) derives
     * exactly one per-iteration correlation id and one satisfaction marker per loop, keyed off the
     * FIRST reachable await step's name; a loop body with two or more reachable awaits was never
     * exercised by this Move's own restart-proof tests, so it stays rejected rather than silently
     * accepted-but-unproven.
     *
     * <p><b>{@code parallelAwait=true} (B15(B), lifted 2026-08-03 once its own restart-proof test
     * passed -- see {@code KernelRunnerParallelAwaitInLoopRestartProofTest} in the kernel module):
     * N iterations' awaits genuinely outstanding at once, instead of the default sequential
     * (one-at-a-time) behavior.</b> Originally scoped to EXACTLY one {@code await} step, no steps
     * before or after it, because a step mutating a non-namespaced {@code state} key would clobber
     * across independently-attempted iterations (sequential mode never has more than one iteration
     * in flight, so it never hit this). <b>Wave 3 (S8_DEFERRED_FIVE_PLAN.md, 2026-08-04, I5)
     * widened this to any number of other steps before/after the one required await</b>, once its
     * own hard-stop restart proof passed for a genuinely multi-step body (iterations parked at
     * DIFFERENT steps across a real process restart -- see
     * {@code KernelRunnerParallelAwaitInLoopRestartProofTest}'s
     * {@code multiStepBodyRestartWithIterationsAtDifferentStepsDoesNotReprocessTheResolvedIteration}
     * and {@code ...OutOfOrderDeliveryResumesEachIterationExactlyOnce}, kernel module): the clobber
     * this cap originally guarded against is now closed by {@code ParallelLoopIterationScope}
     * (kernel module) -- see {@code FlowStateCodec}'s {@code PARALLEL_LOOP_*} javadoc for the full
     * per-iteration state-isolation design. Still refused: a {@code forEach} step nested in the
     * body (I3, vector 9 -- composes two independent per-iteration scoping schemes), and an await
     * nested inside a branch rather than at the loop body's top level (the kernel-side runtime scan
     * in {@code ParallelAwaitForEachStep} only looks at the body's top-level steps for its one
     * required await -- see the check below, deliberately NOT the recursive {@link
     * #countAwaitSteps}, so the validator never accepts a shape the runtime would then reject).
     */
    private static void validateForEachStep(
            FlowAst flow,
            StepAst step,
            Map<String, Set<String>> operationsByCapability,
            Set<String> eventNames,
            Set<String> conceptInvariantRefs,
            Set<String> referencedCapabilities,
            Set<String> knownProcedureNames,
            Set<String> knownStepNames,
            List<String> errors
    ) {
        String itemKey = step.getItemKey();
        String normalizedItemKey = normalize(itemKey);
        if (hasText(itemKey) && RESERVED_FLOW_STATE_KEYS.contains(normalizedItemKey)) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": itemKey '" + itemKey + "' shadows a reserved flow state key");
        }
        if (hasText(itemKey) && hasText(step.getCollectionRef())) {
            String collectionRoot = normalize(step.getCollectionRef()).split("\\.", 2)[0];
            if (normalizedItemKey.equals(collectionRoot)) {
                errors.add("Flow " + flow.getName() + " step " + step.getName()
                        + ": itemKey must not shadow its own collection reference"
                        + " -- suggestedFix: rename itemKey to something other than '" + itemKey
                        + "' (the collection's own root key), so the loop body can still read the "
                        + "collection it is iterating");
            }
        }
        if (step.getMaxLoopIterations() != null) {
            if (step.getMaxLoopIterations() <= 0) {
                errors.add("Flow " + flow.getName() + " step " + step.getName()
                        + ": maxLoopIterations must be positive");
            } else if (step.getMaxLoopIterations() > MAX_LOOP_ITERATIONS_CEILING) {
                errors.add("Flow " + flow.getName() + " step " + step.getName()
                        + ": maxLoopIterations must not exceed " + MAX_LOOP_ITERATIONS_CEILING
                        + " -- suggestedFix: set maxLoopIterations to " + MAX_LOOP_ITERATIONS_CEILING
                        + " or less; a larger batch belongs in a scheduled flow that pages, not in one "
                        + "synchronous loop");
            }
        }
        int reachableAwaitStepCount = countAwaitSteps(step.getLoopSteps());
        if (reachableAwaitStepCount > 1) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": a forEach loop body supports at most one await step (found "
                    + reachableAwaitStepCount + ") -- B15(A)/B15(B) (Move 16/S6) lifted the"
                    + " single-await case, sequential or parallel, each durably proven across a"
                    + " real process restart; more than one reachable await per iteration is not"
                    + " supported");
        }
        if (Boolean.TRUE.equals(step.getParallelAwait())) {
            List<StepAst> loopSteps = step.getLoopSteps();
            // I5 (Wave 3): top-level-only count, deliberately NOT the recursive countAwaitSteps
            // above -- ParallelAwaitForEachStep's runtime scan (kernel module) only looks at the
            // body's TOP-LEVEL steps for its one required await, so an await buried inside a
            // branch must be rejected here too, or the validator would accept a shape the runtime
            // then fails at execution time (the REG-70 shape this ordering exists to prevent).
            long topLevelAwaitCount = loopSteps.stream()
                    .filter(loopStep -> "await".equals(normalize(loopStep.getType())))
                    .count();
            if (topLevelAwaitCount != 1) {
                errors.add("Flow " + flow.getName() + " step " + step.getName()
                        + ": parallelAwait=true requires the loop body to contain exactly one await"
                        + " step at the TOP LEVEL (not nested inside a branch) -- found "
                        + topLevelAwaitCount);
            }
            // I3 (Wave 3, S8_DEFERRED_FIVE_PLAN.md): a nested forEach inside a parallel forEach
            // composes two independent per-iteration scoping schemes -- ForEachStep itself writes
            // 5 state keys including its own progress marker, none of which are namespaced by the
            // OUTER parallel loop's iteration scope (see ParallelLoopIterationScope). Refused with
            // a named error rather than left to discover the composition is broken later, exactly
            // as B15(B) originally refused a multi-step body outright. Checked independently of the
            // single-await-body cap above so this stays correct even once Wave 3's own I5 relaxes
            // that cap for a multi-step body.
            for (StepAst nested : loopSteps) {
                if ("foreach".equals(normalize(nested.getType()))) {
                    errors.add("Flow " + flow.getName() + " step " + step.getName()
                            + ": parallelAwait=true does not support a nested forEach step ("
                            + nested.getName() + ") in its loop body -- composing two independent"
                            + " per-iteration scoping schemes is not supported (I3, Wave 3)");
                }
            }
        }
        if (hasText(itemKey)) {
            checkNestedItemKeyShadowing(flow, step, normalizedItemKey, step.getLoopSteps(), errors);
        }
        validateFlowSteps(
                flow,
                step.getLoopSteps(),
                operationsByCapability,
                eventNames,
                conceptInvariantRefs,
                referencedCapabilities,
                knownProcedureNames,
                knownStepNames,
                errors
        );
    }

    /** LIFT-LOOP-P4: flags a nested forEach reusing an enclosing loop's itemKey -- the inner
     * loop's {@code state.put(itemKey, item)} would silently shadow the outer item for the rest
     * of that inner iteration, a common source of "wrong item" authoring bugs. */
    private static void checkNestedItemKeyShadowing(
            FlowAst flow,
            StepAst outerStep,
            String outerItemKey,
            List<StepAst> steps,
            List<String> errors
    ) {
        for (StepAst step : steps) {
            String type = normalize(step.getType());
            if ("foreach".equals(type) && outerItemKey.equals(normalize(step.getItemKey()))) {
                errors.add("Flow " + flow.getName() + " step " + step.getName()
                        + ": itemKey '" + step.getItemKey() + "' shadows enclosing forEach step "
                        + outerStep.getName() + "'s item variable");
            }
            checkNestedItemKeyShadowing(flow, outerStep, outerItemKey, step.getThenSteps(), errors);
            checkNestedItemKeyShadowing(flow, outerStep, outerItemKey, step.getElseSteps(), errors);
            checkNestedItemKeyShadowing(flow, outerStep, outerItemKey, step.getLoopSteps(), errors);
        }
    }

    /** B15(A) (Move 16): counts every reachable await step (recursing into branch/loop nesting),
     * replacing the former {@code containsAwaitStep} boolean check now that up to one is allowed --
     * see {@link #validateForEachStep}'s own javadoc for why the cap is exactly one, not "any". */
    private static int countAwaitSteps(List<StepAst> steps) {
        int count = 0;
        for (StepAst step : steps) {
            String type = normalize(step.getType());
            if ("await".equals(type)) {
                count++;
            }
            count += countAwaitSteps(step.getThenSteps())
                    + countAwaitSteps(step.getElseSteps())
                    + countAwaitSteps(step.getLoopSteps());
        }
        return count;
    }

    private static void validateInvariantStep(
            FlowAst flow,
            StepAst step,
            Set<String> conceptInvariantRefs,
            List<String> errors
    ) {
        String checkpoint = normalize(step.getCheckpoint());
        if (!checkpoint.isBlank() && !"pre".equals(checkpoint) && !"post".equals(checkpoint)) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": invariant checkpoint must be pre or post");
        }

        String scope = normalize(step.getScope());
        if (step.getInvariants().isEmpty() && scope.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": invariant step must reference invariants or define scope"
                    + " -- suggestedFix: declare invariants[] naming the concept invariants to check, or "
                    + "declare scope naming the concept whose invariants all run here");
            return;
        }

        for (String invariantRef : step.getInvariants()) {
            if (!conceptInvariantRefs.contains(normalize(invariantRef))) {
                errors.add("Flow " + flow.getName() + " step " + step.getName()
                        + ": references unknown invariant " + invariantRef);
            }
        }

        if (!scope.isBlank() && !scope.equals(normalize(flow.getConcept()))) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": invariant scope must match flow concept");
        }
    }

    private static void validateCapabilityStep(
            FlowAst flow,
            StepAst step,
            Map<String, Set<String>> operationsByCapability,
            Set<String> referencedCapabilities,
            List<String> errors
    ) {
        String capability = normalize(step.getCapability());
        String operation = normalize(step.getOperation());
        if (capability.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": capability is required for capability step");
            return;
        }
        if (!operationsByCapability.containsKey(capability)) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": references unknown capability " + step.getCapability());
            return;
        }
        referencedCapabilities.add(capability);
        if (operation.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": operation is required for capability step");
            return;
        }

        Set<String> operations = operationsByCapability.get(capability);
        if (!operations.contains(operation)) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": references unknown operation " + step.getOperation()
                    + " for capability " + step.getCapability());
        }

        validateCapabilityPolicy(
                "Flow " + flow.getName() + " step " + step.getName() + ": ",
                step.getCapabilityPolicy(),
                errors
        );
    }

    private static void validatePersistenceMutationAliasStep(
            FlowAst flow,
            StepAst step,
            Map<String, Set<String>> operationsByCapability,
            Set<String> referencedCapabilities,
            List<String> errors
    ) {
        String scope = normalize(step.getScope());
        if (scope.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": " + step.getType() + " step must define scope");
        }
        String inputRef = normalize(step.getInput());
        if (inputRef.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": " + step.getType() + " step must define input");
        }
        String outputRef = normalize(step.getOutput());
        if (outputRef.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": " + step.getType() + " step must define output/out");
        }

        if (!operationsByCapability.containsKey("persistence")) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": persistence capability is required for " + step.getType());
            return;
        }
        referencedCapabilities.add("persistence");
        Set<String> operations = operationsByCapability.get("persistence");
        if (operations == null || !operations.contains("save")) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": persistence.save is required for " + step.getType());
        }
        validateCapabilityPolicy(
                "Flow " + flow.getName() + " step " + step.getName() + ": ",
                step.getCapabilityPolicy(),
                errors
        );
    }

    private static void validateMapStep(FlowAst flow, StepAst step, List<String> errors) {
        String inputRef = normalize(step.getInput());
        String outputRef = normalize(step.getOutput());
        if (inputRef.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": map/assign step must define input");
        }
        if (outputRef.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": map/assign step must define output/out");
        }
    }

    private static void validateEventStep(
            FlowAst flow,
            StepAst step,
            Set<String> eventNames,
            List<String> errors
    ) {
        String event = normalize(step.getEvent());
        if (event.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": event is required for event step");
            return;
        }

        if (!eventNames.contains(event)) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": references unknown event " + step.getEvent());
        }

        if ((step.getPayload() == null || step.getPayload().isBlank())
                && (step.getData() == null || step.getData().isEmpty())) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": event step must define payload reference or data mapping");
        }
    }

    /** F4 (docs/FINAL_OPEN_ITEMS_PLAN.md): generatedAction was one of the 12 canonical flowStep.type
     * values (DSL 2.0's 3 "sugar" kinds, alongside createConcept/updateConcept) but this switch never
     * had a case for it, so every authored model using it was rejected as "unsupported step type" --
     * despite ModelCompiler already compiling it into a CompiledCapabilityCall("GeneratedActionCapability",
     * ...) and the generator/runtime (TrustedActionKernelRunnerTemplate, GeneratedActionCapabilityAdapter)
     * already having full, tested support for executing one. JsonModelParser already guarantees
     * actionName is present and non-blank (throws during parsing otherwise, generatedAction.md), so
     * this check is a defensive belt-and-suspenders re-check, not new enforcement -- there is nothing
     * to cross-reference (unlike a capability step's operationsByCapability lookup): the named action
     * is a code-generation directive resolved by the generator at build time, not a model-declared
     * capability. */
    private static void validateGeneratedActionStep(FlowAst flow, StepAst step, List<String> errors) {
        String actionName = normalize(step.getGeneratedActionName());
        if (actionName.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": generatedAction step must define actionName");
        }
    }

    /** Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): callProcedure invokes a named
     * procedure synchronously from a flow -- the referenced name must resolve to a real declared
     * procedure, same cross-reference discipline as a capability step's operationsByCapability
     * lookup. {@code procedure} being non-blank is already enforced by the JSON Schema. */
    private static void validateCallProcedureStep(
            FlowAst flow,
            StepAst step,
            Set<String> knownProcedureNames,
            List<String> errors
    ) {
        String procedure = normalize(step.getProcedure());
        if (procedure.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": callProcedure step must define procedure");
            return;
        }
        if (!knownProcedureNames.contains(procedure)) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": references unknown procedure " + step.getProcedure());
        }
    }

    private static void validateReturnStep(FlowAst flow, StepAst step, List<String> errors) {
        String value = normalize(step.getReturnValue());
        if (value.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": return step must define value");
        }
    }

    private static void validateScheduleEventStep(
            FlowAst flow,
            StepAst step,
            Set<String> eventNames,
            List<String> errors
    ) {
        validateEventStep(flow, step, eventNames, errors);
        if (step.getDelaySeconds() == null) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": scheduleEvent step must define delaySeconds/delayMinutes/delayMs");
            return;
        }
        if (step.getDelaySeconds() < 0L) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": scheduleEvent step delaySeconds must be >= 0");
        }
    }

    private static void validateBranchStep(
            FlowAst flow,
            StepAst step,
            Map<String, Set<String>> operationsByCapability,
            Set<String> eventNames,
            Set<String> conceptInvariantRefs,
            Set<String> referencedCapabilities,
            Set<String> knownProcedureNames,
            Set<String> knownStepNames,
            List<String> errors
    ) {
        String condition = normalize(step.getCondition());
        if (condition.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": branch step must define condition");
        } else {
            // R4.2 (roadmap): KernelRunner.evaluateCondition now tries the full ComputedExpression
            // grammar (arithmetic/&&/||/>/<, not just ==/!=) before falling back to its legacy
            // matcher -- validate the same grammar here, mirroring
            // ConceptValidation.validateAccessExpression/FieldValueValidation's identical widening,
            // so a condition that can never parse is refused at author time instead of reaching a
            // running flow.
            try {
                ComputedExpression.validate(condition);
            } catch (ComputedExpression.ExpressionException syntaxError) {
                errors.add("Flow " + flow.getName() + " step " + step.getName()
                        + ": branch condition has invalid syntax: " + condition
                        + " (" + syntaxError.getMessage() + ")");
            }
        }
        if (step.getThenSteps().isEmpty()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": branch step must define non-empty then steps");
        }

        if (!step.getThenSteps().isEmpty()) {
            validateFlowSteps(flow, step.getThenSteps(), operationsByCapability, eventNames,
                    conceptInvariantRefs, referencedCapabilities, knownProcedureNames, knownStepNames, errors);
        }
        if (!step.getElseSteps().isEmpty()) {
            validateFlowSteps(flow, step.getElseSteps(), operationsByCapability, eventNames,
                    conceptInvariantRefs, referencedCapabilities, knownProcedureNames, knownStepNames, errors);
        }
    }

    private static void validateAwaitStep(
            FlowAst flow,
            StepAst step,
            Map<String, Set<String>> operationsByCapability,
            Set<String> eventNames,
            Set<String> conceptInvariantRefs,
            Set<String> referencedCapabilities,
            Set<String> knownProcedureNames,
            Set<String> knownStepNames,
            List<String> errors
    ) {
        String awaitEvent = normalize(step.getAwaitEvent());
        if (awaitEvent.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": await step must define awaitEvent");
            return;
        }

        if (!eventNames.contains(awaitEvent)) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": await step references unknown event " + step.getAwaitEvent());
        }

        for (Map.Entry<String, String> payloadMatch : step.getAwaitPayloadMatch().entrySet()) {
            String field = normalize(payloadMatch.getKey());
            String ref = normalize(payloadMatch.getValue());
            if (field.isBlank()) {
                errors.add("Flow " + flow.getName() + " step " + step.getName()
                        + ": await match payload field must be non-blank");
            }
            if (ref.isBlank()) {
                errors.add("Flow " + flow.getName() + " step " + step.getName()
                        + ": await match payload reference must be non-blank for field " + payloadMatch.getKey());
            }
        }

        // R2.5 (durable await timeouts): timeout is optional, but when present it must be a
        // positive number of seconds -- mirroring validateScheduleEventStep's delaySeconds check
        // (>= 0 there since an immediate scheduleEvent is meaningful; a ZERO-second await timeout
        // is not, since it would expire the instant the step first parks). onTimeout is only
        // meaningful paired with a declared timeout, and its steps get the same recursive
        // validation as onFailure/then/else -- an escalation branch is an ordinary step list, not
        // a special one.
        if (step.getTimeoutSeconds() != null && step.getTimeoutSeconds() <= 0L) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": await step timeout must be > 0 seconds");
        }
        if (step.getTimeoutSeconds() == null && !step.getOnTimeoutSteps().isEmpty()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": await step onTimeout requires timeout to be set");
        }
        if (!step.getOnTimeoutSteps().isEmpty()) {
            validateFlowSteps(flow, step.getOnTimeoutSteps(), operationsByCapability, eventNames,
                    conceptInvariantRefs, referencedCapabilities, knownProcedureNames, knownStepNames, errors);
        }
    }

    private static Map<String, Set<String>> resolveCapabilityOperations(ModelAst modelAst) {
        Map<String, Set<String>> operationsByCapability = new HashMap<>(BUILTIN_CAPABILITY_OPERATIONS);
        for (CapabilityAst capability : modelAst.getCapabilities()) {
            String capabilityKey = normalize(capability.getName());
            Set<String> operations = capability.getOperations().stream()
                    .map(CapabilityOperationAst::getName)
                    .map(SemanticValidator::normalize)
                    .collect(Collectors.toCollection(HashSet::new));
            if (operations.isEmpty()) {
                String capabilityType = normalize(capability.getType());
                if (!capabilityType.isBlank() && BUILTIN_CAPABILITY_OPERATIONS.containsKey(capabilityType)) {
                    operations.addAll(BUILTIN_CAPABILITY_OPERATIONS.get(capabilityType));
                }
            }
            operationsByCapability.put(capabilityKey, operations);

            String capabilityType = normalize(capability.getType());
            if (!capabilityType.isBlank()) {
                operationsByCapability.put(capabilityType, new HashSet<>(operations));
            }
        }
        return operationsByCapability;
    }

    private static Set<String> collectInvariantReferences(
            FlowAst flow,
            ConceptAst concept,
            EffectiveEntity effectiveConcept
    ) {
        Set<String> out = new HashSet<>();
        for (InvariantAst invariant : effectiveConcept.invariants()) {
            if (invariant.getName() != null && !invariant.getName().isBlank()) {
                out.add(normalize(invariant.getName()));
            }
            if ("unique".equalsIgnoreCase(invariant.getType()) && invariant.getFields().size() == 1) {
                out.add(normalize("unique(" + invariant.getFields().get(0) + ")"));
            }
            if ("expression".equalsIgnoreCase(invariant.getType())
                    && invariant.getExpression() != null
                    && !invariant.getExpression().isBlank()) {
                out.add(normalize(invariant.getExpression()));
            }
        }

        for (FieldAst field : effectiveConcept.fields()) {
            if (field.isRequired()) {
                out.add(normalize("required(" + field.getName() + ")"));
            }
        }
        if (flow != null && flow.getConcept() != null && !flow.getConcept().isBlank()) {
            out.add(normalize("scope:" + flow.getConcept()));
        }
        return out;
    }

}
