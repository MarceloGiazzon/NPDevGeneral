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

import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;
import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.ExpressionValidation.validateParameterNames;
import static com.npdev.dsl.v1.validation.ConceptValidation.BUILTIN_CAPABILITIES;

/**
 * Semantic validation for queries, rule profiles, procedures (and their steps), external-AI
 * egress, and the shared capability-policy / capability-binding checks used by both procedures
 * and flows.
 *
 * <p>Split out of {@code SemanticValidator} (T1.15).
 */
final class PackValidation {

    private PackValidation() {
    }

    private static final Set<String> POLICY_CLASSIFICATIONS =
            Set.of("transient", "permanent", "contract");

    private static final Set<String> RULE_PROFILE_NAMES =
            Set.of("always", "interactive", "interactiveonly", "headless", "headlessonly", "query", "beforecommit", "aftercommit");

    private static final Set<String> PROCEDURE_STEP_TYPES =
            Set.of("assign", "mapvalue", "map_value", "condition", "if", "loop", "foreach",
                    "maplist", "map_list", "listtransform", "computevalue", "compute_value", "compute", "arithmetic",
                    "conceptquery", "readconcept", "read_concept", "listconcepts", "list_concepts",
                    "runquery", "run_query", "conceptcreate", "conceptupdate", "saveconcept", "save_concept",
                    "conceptdelete", "deleteconcept", "delete_concept", "procedurecall", "callprocedure",
                    "call_procedure", "capabilitycall", "callcapability", "call_capability",
                    "eventpublish", "publishevent", "publish_event", "patchconcept", "return");
    private static final Set<String> PROCEDURE_MAP_LIST_STEP_TYPES =
            Set.of("maplist", "map_list", "listtransform");
    private static final Set<String> PROCEDURE_COMPUTE_VALUE_STEP_TYPES =
            Set.of("computevalue", "compute_value", "compute", "arithmetic");
    private static final Set<String> PROCEDURE_COMPUTE_VALUE_OPERATORS =
            Set.of("add", "subtract");
    private static final Set<String> PROCEDURE_CONCEPT_STEP_TYPES =
            Set.of("conceptquery", "readconcept", "read_concept", "listconcepts", "list_concepts",
                    "runquery", "run_query", "conceptcreate", "conceptupdate", "saveconcept", "save_concept",
                    "conceptdelete", "deleteconcept", "delete_concept", "patchconcept");
    private static final Set<String> PROCEDURE_PATCH_STEP_TYPES =
            Set.of("patchconcept");
    private static final Set<String> PROCEDURE_QUERY_STEP_TYPES =
            Set.of("conceptquery", "runquery", "run_query");
    private static final Set<String> PROCEDURE_CALL_STEP_TYPES =
            Set.of("procedurecall", "callprocedure", "call_procedure");
    private static final Set<String> PROCEDURE_CAPABILITY_CALL_STEP_TYPES =
            Set.of("capabilitycall", "callcapability", "call_capability");
    private static final Set<String> PROCEDURE_BRANCH_STEP_TYPES =
            Set.of("condition", "if");
    private static final Set<String> PROCEDURE_LOOP_STEP_TYPES =
            Set.of("foreach", "loop", "maplist", "map_list", "listtransform");

    static void validateQueries(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Set<String> queryNames = new HashSet<>();
        for (QueryAst query : modelAst.getQueries()) {
            if (!queryNames.add(normalize(query.name()))) {
                errors.add("Query " + query.name() + ": duplicate query name");
            }
            if (!entitiesByLower.containsKey(normalize(query.concept()))) {
                errors.add("Query " + query.name() + ": concept not found: " + query.concept());
            }
            validateParameterNames("Query " + query.name(), query.parameters(), errors);
        }
    }

    static void validateRuleProfiles(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Set<String> names = new HashSet<>();
        Set<String> knownTargets = new HashSet<>(entitiesByLower.keySet());
        for (QueryAst query : modelAst.getQueries()) {
            knownTargets.add(normalize(query.name()));
        }
        for (ProcedureAst procedure : modelAst.getProcedures()) {
            knownTargets.add(normalize(procedure.name()));
        }
        for (PanelAst panel : modelAst.getPanels()) {
            knownTargets.add(normalize(panel.name()));
        }

        for (RuleProfileAst profile : modelAst.getRuleProfiles()) {
            String name = normalize(profile.name());
            if (!names.add(name)) {
                errors.add("RuleProfile " + profile.name() + ": duplicate rule profile name");
            }
            if (!RULE_PROFILE_NAMES.contains(name)) {
                errors.add("RuleProfile " + profile.name() + ": unsupported rule profile name");
            }
            for (String target : profile.appliesTo()) {
                if (!knownTargets.contains(normalize(target))) {
                    errors.add("RuleProfile " + profile.name() + ": appliesTo target not found: " + target);
                }
            }
        }
    }

    static void validateProcedures(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Set<String> procedureNames = modelAst.getProcedures().stream()
                .map(ProcedureAst::name)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Set<String> queryNames = modelAst.getQueries().stream()
                .map(QueryAst::name)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Map<String, CapabilityAst> capabilitiesByLower = new HashMap<>();
        for (CapabilityAst capability : modelAst.getCapabilities()) {
            capabilitiesByLower.put(normalize(capability.getName()), capability);
        }
        Set<String> seen = new HashSet<>();
        for (ProcedureAst procedure : modelAst.getProcedures()) {
            String procedureName = procedure.name();
            if (!seen.add(normalize(procedureName))) {
                errors.add("Procedure " + procedureName + ": duplicate procedure name");
            }
            validateParameterNames("Procedure " + procedureName, procedure.parameters(), errors);
            if (procedure.steps().isEmpty()) {
                errors.add("Procedure " + procedureName + ": steps must not be empty");
            }
            validateProcedureSteps(
                    procedureName,
                    "procedures[" + procedureName + "].steps",
                    procedure.steps(),
                    entitiesByLower,
                    queryNames,
                    procedureNames,
                    capabilitiesByLower,
                    errors
            );
        }
    }

    private static void validateProcedureSteps(
            String procedureName,
            String path,
            List<ProcedureStepAst> steps,
            Map<String, ConceptAst> entitiesByLower,
            Set<String> queryNames,
            Set<String> procedureNames,
            Map<String, CapabilityAst> capabilitiesByLower,
            List<String> errors
    ) {
        int index = 0;
        for (ProcedureStepAst step : steps) {
            String stepPath = path + "[" + index + "]";
            String type = normalize(step.type());
            if (!PROCEDURE_STEP_TYPES.contains(type)) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": unsupported step type " + step.type());
            }
            if (PROCEDURE_CONCEPT_STEP_TYPES.contains(type)
                    && !entitiesByLower.containsKey(normalize(step.concept()))) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": concept not found: " + step.concept());
            }
            if (PROCEDURE_QUERY_STEP_TYPES.contains(type)
                    && hasText(step.query())
                    && !queryNames.contains(normalize(step.query()))) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": query not found: " + step.query());
            }
            if (PROCEDURE_CALL_STEP_TYPES.contains(type)
                    && !procedureNames.contains(normalize(step.procedure()))) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": procedure not found: " + step.procedure());
            }
            if (PROCEDURE_CAPABILITY_CALL_STEP_TYPES.contains(type)) {
                validateProcedureCapabilityCall(procedureName, stepPath, step, capabilitiesByLower, errors);
            }
            if (PROCEDURE_PATCH_STEP_TYPES.contains(type)) {
                validateProcedurePatchConcept(procedureName, stepPath, step, entitiesByLower, errors);
            }
            if (PROCEDURE_MAP_LIST_STEP_TYPES.contains(type)) {
                validateProcedureMapList(procedureName, stepPath, step, errors);
            }
            if (PROCEDURE_COMPUTE_VALUE_STEP_TYPES.contains(type)) {
                validateProcedureComputeValue(procedureName, stepPath, step, errors);
            }
            if (PROCEDURE_BRANCH_STEP_TYPES.contains(type) && !hasText(step.condition())) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": condition is required");
            }
            if (PROCEDURE_LOOP_STEP_TYPES.contains(type) && !hasText(step.items())) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": " + step.type() + " requires items");
            }
            validateProcedureSteps(procedureName, stepPath + ".then", step.thenSteps(), entitiesByLower, queryNames, procedureNames, capabilitiesByLower, errors);
            validateProcedureSteps(procedureName, stepPath + ".else", step.elseSteps(), entitiesByLower, queryNames, procedureNames, capabilitiesByLower, errors);
            validateProcedureSteps(procedureName, stepPath + ".steps", step.steps(), entitiesByLower, queryNames, procedureNames, capabilitiesByLower, errors);
            index++;
        }
    }

    /**
     * LIFT-QUERY-P3: a {@code callCapability} procedure step references a declared capability +
     * operation, and its arg count matches that operation's declared {@code input} arity -- the
     * dispatcher itself matches by name+arity only (no type-checking, per LIFT-QUERY-P2's
     * research), so this is the only place a mismatched arity gets caught before runtime.
     */
    private static void validateProcedureCapabilityCall(
            String procedureName,
            String stepPath,
            ProcedureStepAst step,
            Map<String, CapabilityAst> capabilitiesByLower,
            List<String> errors
    ) {
        if (!hasText(step.capability())) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": capability is required for callCapability");
            return;
        }
        CapabilityAst capability = capabilitiesByLower.get(normalize(step.capability()));
        if (capability == null) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": capability not found: " + step.capability());
            return;
        }
        if (!hasText(step.operation())) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": operation is required for callCapability");
            return;
        }
        Optional<CapabilityOperationAst> operation = capability.getOperations().stream()
                .filter(op -> normalize(op.getName()).equals(normalize(step.operation())))
                .findFirst();
        if (operation.isEmpty()) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": capability " + step.capability()
                    + " has no operation named " + step.operation());
            return;
        }
        // Arity is declared one of two ways depending on authoring style: the legacy plain-array
        // `input: ["a","b"]` shorthand (CapabilityOperationAst.getInput()), or the schemaObject
        // form (`input: {type: object, properties: {a: {...}, b: {...}}}`) that's the only shape
        // the JSON Schema's capabilityOperation.input actually accepts today. The bare-string
        // `"operations": ["save", "unique"]` shorthand declares neither -- no contract to check
        // arity against, so it's skipped rather than flagged (consistent with treating an
        // underspecified operation as accepting anything, its existing behavior everywhere else).
        Integer declaredArity = null;
        if (!operation.get().getInput().isEmpty()) {
            declaredArity = operation.get().getInput().size();
        } else if (operation.get().getInputSchema() != null && !operation.get().getInputSchema().getProperties().isEmpty()) {
            declaredArity = operation.get().getInputSchema().getProperties().size();
        }
        if (declaredArity == null) {
            return;
        }
        int actualArity = step.args() == null ? 0 : step.args().size();
        if (!declaredArity.equals(actualArity)) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": capability " + step.capability() + "."
                    + step.operation() + " expects " + declaredArity + " arg(s) but this call supplies " + actualArity);
        }
    }

    /**
     * Move 4 (docs/MOVE4_CROSS_RECORD_WRITE_PLAN.md): a {@code patchConcept} step names a real
     * concept and id (already checked generically by {@code PROCEDURE_CONCEPT_STEP_TYPES}), plus a
     * non-empty {@code set} whose every key is a declared field of that concept -- catching a typo'd
     * field name at author time (the REG-71 class of bug) instead of at runtime.
     */
    private static void validateProcedurePatchConcept(
            String procedureName,
            String stepPath,
            ProcedureStepAst step,
            Map<String, ConceptAst> entitiesByLower,
            List<String> errors
    ) {
        if (!hasText(step.id())) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": id is required for patchConcept");
        }
        if (step.set() == null || step.set().isEmpty()) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": set is required for patchConcept and must not be empty");
            return;
        }
        ConceptAst concept = entitiesByLower.get(normalize(step.concept()));
        if (concept == null) {
            return; // already reported by the generic PROCEDURE_CONCEPT_STEP_TYPES check
        }
        Set<String> declaredFields = concept.getFields().stream()
                .map(FieldAst::getName)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        for (String field : step.set().keySet()) {
            if (!declaredFields.contains(normalize(field))) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": set names a field not declared on "
                        + step.concept() + ": " + field);
            }
        }
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3A / Gap 6): a {@code mapList} step needs a
     * non-empty {@code select} (the per-item field map, resolved via the same convention as
     * {@code patchConcept}'s {@code set}) and a {@code target} naming the output list -- unlike
     * {@code patchConcept}, there is no concept to check field names against, since the produced
     * list is not itself a persisted record.
     */
    private static void validateProcedureMapList(
            String procedureName,
            String stepPath,
            ProcedureStepAst step,
            List<String> errors
    ) {
        if (step.select() == null || step.select().isEmpty()) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": select is required for mapList and must not be empty");
        }
        if (!hasText(step.target())) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": target is required for mapList (names the output list)");
        }
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, final item / REG-78): {@code computeValue} needs a
     * known operator ("add"/"subtract" -- the minimum REG-78 named, matching {@code
     * DefaultProcedureExecutor}'s own switch), both operands present (a literal or a {@code $ref},
     * either is fine -- {@code null} is not), and a target to write the result to.
     */
    private static void validateProcedureComputeValue(
            String procedureName,
            String stepPath,
            ProcedureStepAst step,
            List<String> errors
    ) {
        String operator = step.operation() == null ? "" : step.operation().trim().toLowerCase(java.util.Locale.ROOT);
        if (!PROCEDURE_COMPUTE_VALUE_OPERATORS.contains(operator)) {
            errors.add("Procedure " + procedureName + " step " + stepPath
                    + ": computeValue requires operation to be one of " + PROCEDURE_COMPUTE_VALUE_OPERATORS
                    + ", got: " + step.operation());
        }
        if (step.left() == null) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": left is required for computeValue");
        }
        if (step.right() == null) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": right is required for computeValue");
        }
        if (!hasText(step.target())) {
            errors.add("Procedure " + procedureName + " step " + stepPath + ": target is required for computeValue (names where the result is written)");
        }
    }

    static void validateCapabilityPolicy(
            String prefix,
            CapabilityPolicyAst policy,
            List<String> errors
    ) {
        if (policy == null) {
            return;
        }
        if (policy.getRetryCount() != null && policy.getRetryCount() < 1) {
            errors.add(prefix + "policy.retryCount must be >= 1");
        }
        if (policy.getRetryDelayMs() != null && policy.getRetryDelayMs() < 0) {
            errors.add(prefix + "policy.retryDelayMs must be >= 0");
        }
        if (policy.getTimeoutMs() != null && policy.getTimeoutMs() < 0) {
            errors.add(prefix + "policy.timeoutMs must be >= 0");
        }
        if (policy.getCircuitOpenAfterFailures() != null && policy.getCircuitOpenAfterFailures() < 1) {
            errors.add(prefix + "policy.circuitOpenAfterFailures must be >= 1");
        }
        if (policy.getCircuitOpenMs() != null && policy.getCircuitOpenMs() < 0) {
            errors.add(prefix + "policy.circuitOpenMs must be >= 0");
        }
        if (policy.getBulkheadMaxConcurrent() != null && policy.getBulkheadMaxConcurrent() < 1) {
            errors.add(prefix + "policy.bulkheadMaxConcurrent must be >= 1");
        }
        String idempotencyKeyField = normalize(policy.getIdempotencyKeyField());
        if (policy.getIdempotencyKeyField() != null && idempotencyKeyField.isBlank()) {
            errors.add(prefix + "policy.idempotencyKeyField must be non-blank when provided");
        }
        String classification = normalize(policy.getFailureClassification());
        if (!classification.isBlank() && !POLICY_CLASSIFICATIONS.contains(classification)) {
            errors.add(prefix + "policy.failureClassification must be one of TRANSIENT, PERMANENT, CONTRACT");
        }
    }

    static void validateReferencedCapabilityBindings(
            ModelAst modelAst,
            Set<String> referencedCapabilities,
            List<String> errors
    ) {
        if (referencedCapabilities == null || referencedCapabilities.isEmpty()) {
            return;
        }
        Set<String> boundCapabilities = modelAst.getBindings().stream()
                .map(CapabilityBindingAst::getCapability)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        for (String capability : referencedCapabilities) {
            if (BUILTIN_CAPABILITIES.contains(capability)) {
                continue;
            }
            if (!boundCapabilities.contains(capability)) {
                errors.add("Flow references capability without binding: " + capability);
            }
        }
    }

    /**
     * ADR-0009: egress must not be enabled with no vendor configured. The model-level analogue of
     * {@code ExternalAiCapabilityContract}'s fail-closed runtime default (a contract with no adapter
     * opted in denies) -- an author who sets {@code egress} to anything but {@code denied} without
     * naming at least one vendor in {@code externalAi.vendors} is caught here, at author time,
     * instead of only discovering the gap when {@code external-ai-http} has no vendor profile to
     * resolve against at runtime.
     */
    static void validateExternalAiEgress(ModelAst modelAst, List<String> errors) {
        ExternalAiAst externalAi = modelAst.getExternalAi();
        if (externalAi == null || "denied".equalsIgnoreCase(externalAi.getEgress())) {
            return;
        }
        if (externalAi.getVendors().isEmpty()) {
            errors.add("externalAi.egress is '" + externalAi.getEgress() + "' but no vendors are declared -- "
                    + "egress requires at least one vendor in externalAi.vendors (see ADR-0009 D1).");
        }
    }

}
