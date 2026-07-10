package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.CapabilityAst;
import com.npdev.dsl.v1.ast.CapabilityBindingAst;
import com.npdev.dsl.v1.ast.CapabilityOperationAst;
import com.npdev.dsl.v1.ast.DomainTypeAst;
import com.npdev.dsl.v1.ast.CapabilityPolicyAst;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.EventAst;
import com.npdev.dsl.v1.ast.EventPayloadAst;
import com.npdev.dsl.v1.ast.EnumOptionAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.InvariantAst;
import com.npdev.dsl.v1.ast.LifecycleAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.OrchestrationActionAst;
import com.npdev.dsl.v1.ast.OrchestrationAst;
import com.npdev.dsl.v1.ast.OrchestrationTriggerAst;
import com.npdev.dsl.v1.ast.GuidePageAst;
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

/**
 * Minimal semantic validator for MVP.
 */
public final class SemanticValidator {

    private static final String SUPPORTED_DSL_VERSION = ModelAst.DEFAULT_DSL_VERSION;
    private static final Pattern REFERENCE_TEMPLATE_FIELD_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");
    private static final Set<String> KNOWN_TYPES = Set.of(
            "string",
            "uuid",
            "int",
            "integer",
            "long",
            "boolean",
            "date",
            "datetime",
            "enum",
            "reference",
            "object",
            "array"
    );
    private static final Set<String> DOMAIN_BASE_TYPES = Set.of(
            "string",
            "uuid",
            "int",
            "integer",
            "long",
            "boolean",
            "date",
            "datetime"
    );
    private static final Set<String> BUILTIN_CAPABILITIES =
            Set.of("eventbus", "persistence", "invariantengine",
                    "persistencecapability", "messagingcapability", "emailcapability",
                    "fiscalcapability", "signaturecapability");
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
    private static final Set<String> FORBIDDEN_TECH_KEYWORDS = Set.of(
            "spring", "jpa", "hibernate", "kafka", "smtp", "rest", "soap"
    );
    private static final Pattern FIELD_MATCHES_PATTERN =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\.matches\\s*\\(.*\\)\\s*$");
    private static final Pattern FIELD_COMPARISON_PATTERN =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(==|!=|>=|<=|>|<)\\s*.+$");
    private static final Pattern FIELD_UNIQUE_BY_PATTERN =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\.uniqueBy\\s*\\(\\s*[A-Za-z_][A-Za-z0-9_]*\\s*\\)\\s*$");
    private static final Pattern VALUE_BEHAVIOR_IDENTIFIER_PATTERN =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> POLICY_CLASSIFICATIONS =
            Set.of("transient", "permanent", "contract");
    private static final Set<String> VALUE_BEHAVIOR_FUNCTIONS =
            Set.of("concat", "coalesce", "trim", "uppercase", "lowercase");
    private static final Set<String> INTERACTION_BOOLEAN_LITERALS =
            Set.of("true", "false", "null");
    private static final Set<String> RULE_PROFILE_NAMES =
            Set.of("always", "interactive", "interactiveonly", "headless", "headlessonly", "query", "beforecommit", "aftercommit");
    private static final Set<String> PROCEDURE_STEP_TYPES =
            Set.of("assign", "mapvalue", "map_value", "condition", "if", "loop", "foreach",
                    "conceptquery", "readconcept", "read_concept", "listconcepts", "list_concepts",
                    "runquery", "run_query", "conceptcreate", "conceptupdate", "saveconcept", "save_concept",
                    "conceptdelete", "deleteconcept", "delete_concept", "procedurecall", "callprocedure",
                    "call_procedure", "capabilitycall", "callcapability", "call_capability",
                    "eventpublish", "publishevent", "publish_event", "return");
    private static final Set<String> PROCEDURE_CONCEPT_STEP_TYPES =
            Set.of("conceptquery", "readconcept", "read_concept", "listconcepts", "list_concepts",
                    "runquery", "run_query", "conceptcreate", "conceptupdate", "saveconcept", "save_concept",
                    "conceptdelete", "deleteconcept", "delete_concept");
    private static final Set<String> PROCEDURE_QUERY_STEP_TYPES =
            Set.of("conceptquery", "runquery", "run_query");
    private static final Set<String> PROCEDURE_CALL_STEP_TYPES =
            Set.of("procedurecall", "callprocedure", "call_procedure");
    private static final Set<String> PROCEDURE_BRANCH_STEP_TYPES =
            Set.of("condition", "if");
    private static final Set<String> PROCEDURE_LOOP_STEP_TYPES =
            Set.of("foreach", "loop");
    private static final Set<String> PANEL_ACTION_BINDINGS =
            Set.of("conceptquery", "conceptmutation", "procedure", "flow");

    public List<String> validate(ModelAst modelAst) {
        return validateWithWarnings(modelAst).getErrors();
    }

    public List<String> validate(ModelAst modelAst, boolean allowUnboundFlowCapabilities) {
        return validateWithWarnings(modelAst, allowUnboundFlowCapabilities).getErrors();
    }

    public ValidationResult validateWithWarnings(ModelAst modelAst) {
        return validateWithWarnings(modelAst, false);
    }

    public ValidationResult validateWithWarnings(ModelAst modelAst, boolean allowUnboundFlowCapabilities) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>(modelAst.getParserWarnings());
        List<String> semanticWarnings = new ArrayList<>(modelAst.getParserWarnings());
        ResolvedModel resolvedModel;
        try {
            resolvedModel = new ModelResolver().resolve(modelAst);
        } catch (ModelResolutionException resolutionException) {
            errors.add(resolutionException.getMessage());
            List<ValidationDiagnostic> diagnostics = new ArrayList<>();
            diagnostics.add(ValidationDiagnosticNormalizer.semanticDiagnostic(
                    resolutionException.getMessage(),
                    ValidationSeverity.ERROR
            ));
            return new ValidationResult(errors, warnings, diagnostics);
        }
        ModelAst effectiveModel = resolvedModel.modelAst();
        validateDslVersion(effectiveModel, errors);
        Map<String, ConceptAst> entitiesByLower = indexEntities(effectiveModel.getConcepts(), errors);

        validateCapabilities(effectiveModel, errors);
        validateBindings(effectiveModel, errors);
        validateEvents(effectiveModel, errors);
        Map<String, DomainTypeAst> domainTypesByLower = validateDomainTypes(effectiveModel, errors);
        validateEntityLocalFields(effectiveModel, errors);
        validateInheritanceGraph(entitiesByLower, errors);
        validateTechnologyNeutrality(effectiveModel, errors);

        Map<String, EffectiveEntity> effectiveCache = new HashMap<>();
        for (ConceptAst e : effectiveModel.getConcepts()) {
            EffectiveEntity effective = resolveEffective(
                    e,
                    entitiesByLower,
                    effectiveCache,
                    new HashSet<>(),
                    errors
            );
            Set<String> fieldNames = effective.fields().stream()
                    .map(FieldAst::getName)
                    .map(SemanticValidator::normalize)
                    .collect(Collectors.toSet());

            long idCount = effective.fields().stream().filter(FieldAst::isId).count();
            if (idCount != 1) {
                errors.add("Entity " + e.getName() + ": must have exactly 1 id field, found " + idCount);
            }

            for (FieldAst f : effective.fields()) {
                String normalizedType = normalize(f.getType());
                if (!KNOWN_TYPES.contains(normalizedType)) {
                    errors.add("Entity " + e.getName() + " field " + f.getName() + ": unknown type " + f.getType());
                    continue;
                }

                String normalizedDomainType = normalize(f.getDomainType());
                DomainTypeAst resolvedDomainType = null;
                if (!normalizedDomainType.isBlank()) {
                    resolvedDomainType = domainTypesByLower.get(normalizedDomainType);
                    if (resolvedDomainType == null) {
                        errors.add("Entity " + e.getName() + " field " + f.getName()
                                + ": domain type not found: " + f.getDomainType());
                    } else if (!areCompatibleTypes(normalizedType, normalize(resolvedDomainType.getBaseType()))) {
                        errors.add("Entity " + e.getName() + " field " + f.getName()
                                + ": domain type " + resolvedDomainType.getName()
                                + " base type " + resolvedDomainType.getBaseType()
                                + " is incompatible with field type " + f.getType());
                    }
                }

                if ("object".equals(normalizedType)) {
                    validateObjectFieldSchema(e.getName(), f, errors);
                } else if ("array".equals(normalizedType)) {
                    validateArrayFieldSchema(e.getName(), f, errors);
                }

                if ("enum".equals(normalizedType)) {
                    List<String> enumValues = f.getEnumValues();
                    if (enumValues == null || enumValues.isEmpty()) {
                        errors.add("Entity " + e.getName() + " field " + f.getName()
                                + ": enum field must declare enumValues (or values)");
                    } else {
                        Set<String> normalizedValues = new HashSet<>();
                        int defaultCount = 0;
                        for (String enumValue : enumValues) {
                            String normalizedValue = normalize(enumValue);
                            if (normalizedValue.isBlank()) {
                                errors.add("Entity " + e.getName() + " field " + f.getName()
                                        + ": enum values must be non-blank");
                                continue;
                            }
                            if (!normalizedValues.add(normalizedValue)) {
                                errors.add("Entity " + e.getName() + " field " + f.getName()
                                        + ": duplicate enum value " + enumValue);
                            }
                        }
                        for (EnumOptionAst enumOption : f.getEnumOptions()) {
                            if (enumOption == null) {
                                continue;
                            }
                            if (enumOption.isDefaultValue()) {
                                defaultCount++;
                            }
                            if (enumOption.isDefaultValue() && enumOption.isDeprecated()) {
                                errors.add("Entity " + e.getName() + " field " + f.getName()
                                        + ": enum value " + enumOption.getValue()
                                        + " cannot be both default and deprecated");
                            }
                            if (enumOption.getOrder() != null && enumOption.getOrder() < 0) {
                                errors.add("Entity " + e.getName() + " field " + f.getName()
                                        + ": enum value " + enumOption.getValue()
                                        + " order must be >= 0");
                            }
                        }
                        if (defaultCount > 1) {
                            errors.add("Entity " + e.getName() + " field " + f.getName()
                                    + ": enum field may declare at most one default value");
                        }
                    }
                } else if (!f.getEnumValues().isEmpty()) {
                    errors.add("Entity " + e.getName() + " field " + f.getName()
                            + ": enumValues are only allowed when type is enum");
                }

                String connectable = normalize(f.getConnectable());
                if (!connectable.isBlank()) {
                    if (!"anchor".equals(connectable)) {
                        errors.add("Entity " + e.getName() + " field " + f.getName()
                                + ": connectable must be \"anchor\"");
                    } else if (!f.isId() && !f.isUnique()) {
                        errors.add("Entity " + e.getName() + " field " + f.getName()
                                + ": connectable anchor field must be unique (or the id field)");
                    }
                }

                if ("reference".equals(normalizedType)) {
                    String target = normalize(f.getReferenceTarget());
                    if (target.isBlank()) {
                        errors.add("Entity " + e.getName() + " field " + f.getName()
                                + ": reference field must declare ref (or reference)");
                    } else if (!entitiesByLower.containsKey(target)) {
                        errors.add("Entity " + e.getName() + " field " + f.getName()
                                + ": reference target not found: " + f.getReferenceTarget());
                    } else {
                        EffectiveEntity effectiveTarget = resolveEffective(
                                entitiesByLower.get(target),
                                entitiesByLower,
                                effectiveCache,
                                new HashSet<>(),
                                errors
                        );
                        validateReferenceSemantics(e.getName(), f, effectiveTarget, errors);
                        validateBondTruthEdge(e, f, entitiesByLower.get(target), semanticWarnings);
                        validateReferenceFieldHint(
                                e.getName(), f, effectiveTarget,
                                f.getUi() == null ? null : f.getUi().getImageField(),
                                "imageField", errors
                        );
                    }
                } else if ((f.getReferenceTarget() != null && !f.getReferenceTarget().isBlank())
                        || f.getReferenceSemantics() != null) {
                    validateScalarReferenceLookupMetadata(
                            e.getName(),
                            f,
                            normalizedType,
                            entitiesByLower,
                            effectiveCache,
                            errors
                    );
                }

                validateFieldWidgetCompatibility(e, f, normalizedType, errors);
                validateFieldValueBehavior(e.getName(), f, fieldNames, errors);
            }
            validateFieldValueBehaviorGraph(e.getName(), effective.fields(), fieldNames, errors);
            Set<String> invariantNames = new HashSet<>();

            for (InvariantAst inv : effective.invariants()) {
                if (inv.getName() != null && !inv.getName().isBlank()) {
                    String invariantKey = normalize(inv.getName());
                    if (!invariantNames.add(invariantKey)) {
                        errors.add("Entity " + e.getName() + ": duplicate invariant name " + inv.getName());
                    }
                }

                // Fields referenced must exist
                for (String fn : referencedFields(inv)) {
                    if (!fieldNames.contains(normalize(fn))) {
                        errors.add("Entity " + e.getName() + " invariant " + inv.getType()
                                + ": references unknown field " + fn);
                    }
                }

                // MVP: unique supports only single-field unique
                if ("unique".equalsIgnoreCase(inv.getType())) {
                    if (inv.getFields() == null || inv.getFields().isEmpty()) {
                        errors.add("Entity " + e.getName() + " invariant unique: must declare fields");
                    } else if (inv.getFields().size() != 1) {
                        errors.add("Entity " + e.getName()
                                + " invariant unique: compound unique (multiple fields) not supported yet: " + inv.getFields());
                    }
                } else if ("expression".equalsIgnoreCase(inv.getType())) {
                    String expression = inv.getExpression();
                    if (expression == null || expression.isBlank()) {
                        errors.add("Entity " + e.getName() + " invariant expression: expression must be non-blank");
                    } else if (!supportsExpressionFormat(expression)) {
                        errors.add("Entity " + e.getName()
                                + " invariant expression: unsupported expression format: " + expression);
                    }
                }
            }

            validateLifecycle(e, effective, errors);
        }

        validateFlows(effectiveModel, entitiesByLower, effectiveCache, allowUnboundFlowCapabilities, errors, semanticWarnings);
        validateOrchestrationRules(effectiveModel, errors);
        validateQueries(effectiveModel, entitiesByLower, errors);
        validateRuleProfiles(effectiveModel, entitiesByLower, errors);
        validateProcedures(effectiveModel, entitiesByLower, errors);
        validatePanels(effectiveModel, entitiesByLower, errors);
        validateGuidePages(effectiveModel, errors);
        errors = canonicalizeConceptTerminology(errors);
        semanticWarnings = canonicalizeConceptTerminology(semanticWarnings);
        for (String semanticWarning : semanticWarnings) {
            if (!warnings.contains(semanticWarning)) {
                warnings.add(semanticWarning);
            }
        }

        List<ValidationDiagnostic> diagnostics = new ArrayList<>();
        for (String error : errors) {
            diagnostics.add(ValidationDiagnosticNormalizer.semanticDiagnostic(error, ValidationSeverity.ERROR));
        }
        for (String warning : semanticWarnings) {
            diagnostics.add(ValidationDiagnosticNormalizer.semanticDiagnostic(warning, ValidationSeverity.WARNING));
        }
        List<ValidationDiagnostic> uxDiagnostics = validatePresentationMetadata(effectiveModel, entitiesByLower);
        diagnostics.addAll(uxDiagnostics);
        for (ValidationDiagnostic diagnostic : uxDiagnostics) {
            warnings.add(diagnostic.getMessage());
        }

        return new ValidationResult(errors, warnings, diagnostics);
    }

    private static List<String> canonicalizeConceptTerminology(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<String> canonical = new ArrayList<>(messages.size());
        for (String message : messages) {
            canonical.add(canonicalizeConceptTerminology(message));
        }
        return canonical;
    }

    private static String canonicalizeConceptTerminology(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return message
                .replace("Entity ", "Concept ")
                .replace(" entity ", " concept ");
    }

    private static void validateQueries(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
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

    private static void validateRuleProfiles(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
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

    private static void validateProcedures(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Set<String> procedureNames = modelAst.getProcedures().stream()
                .map(ProcedureAst::name)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Set<String> queryNames = modelAst.getQueries().stream()
                .map(QueryAst::name)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
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
            if (PROCEDURE_BRANCH_STEP_TYPES.contains(type) && !hasText(step.condition())) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": condition is required");
            }
            if (PROCEDURE_LOOP_STEP_TYPES.contains(type) && !hasText(step.items())) {
                errors.add("Procedure " + procedureName + " step " + stepPath + ": forEach requires items");
            }
            validateProcedureSteps(procedureName, stepPath + ".then", step.thenSteps(), entitiesByLower, queryNames, procedureNames, errors);
            validateProcedureSteps(procedureName, stepPath + ".else", step.elseSteps(), entitiesByLower, queryNames, procedureNames, errors);
            validateProcedureSteps(procedureName, stepPath + ".steps", step.steps(), entitiesByLower, queryNames, procedureNames, errors);
            index++;
        }
    }

    private static void validatePanels(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Set<String> queryNames = modelAst.getQueries().stream()
                .map(QueryAst::name)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Set<String> procedureNames = modelAst.getProcedures().stream()
                .map(ProcedureAst::name)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Set<String> flowNames = modelAst.getFlows().stream()
                .map(FlowAst::getName)
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        Set<String> panelNames = new HashSet<>();
        Set<String> panelRoutes = new HashSet<>();

        for (PanelAst panel : modelAst.getPanels()) {
            if (!panelNames.add(normalize(panel.name()))) {
                errors.add("Panel " + panel.name() + ": duplicate panel name");
            }
            if (!panelRoutes.add(normalize(panel.route()))) {
                errors.add("Panel " + panel.name() + ": duplicate panel route " + panel.route());
            }
            for (PanelDataSourceAst dataSource : panel.dataSources()) {
                if (hasText(dataSource.concept()) && !entitiesByLower.containsKey(normalize(dataSource.concept()))) {
                    errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                            + ": concept not found: " + dataSource.concept());
                }
                if (hasText(dataSource.query()) && !queryNames.contains(normalize(dataSource.query()))) {
                    errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                            + ": query not found: " + dataSource.query());
                }
                if (hasText(dataSource.procedure()) && !procedureNames.contains(normalize(dataSource.procedure()))) {
                    errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                            + ": procedure not found: " + dataSource.procedure());
                }
                if (hasText(dataSource.parentDataSource())) {
                    if (normalize(dataSource.parentDataSource()).equals(normalize(dataSource.name()))) {
                        errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                                + ": cannot declare itself as parentDataSource");
                    }
                    Optional<PanelDataSourceAst> parent = panel.dataSources().stream()
                            .filter(candidate -> normalize(candidate.name()).equals(normalize(dataSource.parentDataSource())))
                            .findFirst();
                    if (parent.isEmpty()) {
                        errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                                + ": parentDataSource not found among sibling dataSources: " + dataSource.parentDataSource());
                    } else if (hasText(parent.get().parentDataSource())) {
                        errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                                + ": nesting is limited to one level (parentDataSource " + dataSource.parentDataSource()
                                + " is itself a child dataSource)");
                    }
                    if (!hasText(dataSource.childField())) {
                        errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                                + ": childField is required when parentDataSource is declared");
                    }
                    if (hasText(dataSource.procedure())) {
                        errors.add("Panel " + panel.name() + " dataSource " + dataSource.name()
                                + ": a child dataSource (parentDataSource declared) must be concept/query-bound, not procedure-bound");
                    }
                }
            }
            for (PanelActionAst action : panel.actions()) {
                String binding = normalize(action.binding());
                if (!PANEL_ACTION_BINDINGS.contains(binding)) {
                    errors.add("Panel " + panel.name() + " action " + action.name()
                            + ": unsupported action binding " + action.binding());
                    continue;
                }
                if ((binding.equals("conceptquery") || binding.equals("conceptmutation"))
                        && !entitiesByLower.containsKey(normalize(action.concept()))) {
                    errors.add("Panel " + panel.name() + " action " + action.name()
                            + ": concept not found: " + action.concept());
                }
                if (binding.equals("conceptmutation") && !Set.of("create", "update", "delete").contains(normalize(action.operation()))) {
                    errors.add("Panel " + panel.name() + " action " + action.name()
                            + ": conceptMutation operation must be create, update, or delete");
                }
                if (binding.equals("procedure") && !procedureNames.contains(normalize(action.procedure()))) {
                    errors.add("Panel " + panel.name() + " action " + action.name()
                            + ": procedure not found: " + action.procedure());
                }
                if (binding.equals("flow") && !flowNames.contains(normalize(action.flow()))) {
                    errors.add("Panel " + panel.name() + " action " + action.name()
                            + ": flow not found: " + action.flow());
                }
            }
        }
    }

    private static void validateGuidePages(ModelAst modelAst, List<String> errors) {
        Set<String> guidePageNames = new HashSet<>();
        boolean sawDefault = false;
        for (GuidePageAst guidePage : modelAst.getGuidePages()) {
            if (!guidePageNames.add(normalize(guidePage.name()))) {
                errors.add("GuidePage " + guidePage.name() + ": duplicate guide page name");
            }
            if (guidePage.isDefault()) {
                if (sawDefault) {
                    errors.add("GuidePage " + guidePage.name() + ": more than one guide page marked default");
                }
                sawDefault = true;
            }
            for (GuidePageGadgetAst gadget : guidePage.gadgets()) {
                if (!hasText(gadget.name())) {
                    errors.add("GuidePage " + guidePage.name() + ": gadget is missing a name");
                }
                if (!hasText(gadget.type())) {
                    errors.add("GuidePage " + guidePage.name() + " gadget " + gadget.name() + ": gadget is missing a type");
                }
            }
        }

        Set<String> knownGuidePageNames = new HashSet<>(guidePageNames);
        for (String builtinName : GuidePageDefaults.BUILTIN_NAMES) {
            knownGuidePageNames.add(normalize(builtinName));
        }
        for (PanelAst panel : modelAst.getPanels()) {
            if (hasText(panel.guidePage()) && !knownGuidePageNames.contains(normalize(panel.guidePage()))) {
                errors.add("Panel " + panel.name() + ": guidePage not found: " + panel.guidePage());
            }
        }
    }

    private static void validateParameterNames(String owner, List<ProcedureParameterAst> parameters, List<String> errors) {
        Set<String> names = new HashSet<>();
        for (ProcedureParameterAst parameter : parameters) {
            if (!names.add(normalize(parameter.name()))) {
                errors.add(owner + ": duplicate parameter name " + parameter.name());
            }
            if (!hasText(parameter.type()) && parameter.schema() == null) {
                errors.add(owner + " parameter " + parameter.name() + ": type or schema is required");
            }
        }
    }

    private static List<ValidationDiagnostic> validatePresentationMetadata(
            ModelAst modelAst,
            Map<String, ConceptAst> entitiesByLower
    ) {
        List<ValidationDiagnostic> diagnostics = new ArrayList<>();
        for (ConceptAst entity : modelAst.getConcepts()) {
            PresentationMetadataAst conceptUi = entity.getUi();
            if (conceptUi == null || normalize(conceptUi.getLabel()).isBlank()) {
                diagnostics.add(uxDiagnostic(
                        "missing_concept_label",
                        "Concept " + entity.getName() + ": presentation metadata should declare a user-visible label",
                        "concepts[" + entity.getName() + "]",
                        entity.getName(),
                        null,
                        "concepts",
                        "Add ui.label so the concept renders with a stable human-facing name."
                ));
            }

            Map<Integer, String> fieldOrderOwners = new LinkedHashMap<>();
            Map<Integer, String> listColumnOwners = new LinkedHashMap<>();
            Map<String, String> layoutSlotOwners = new LinkedHashMap<>();
            Set<String> fieldNames = entity.getFields().stream()
                    .map(FieldAst::getName)
                    .map(SemanticValidator::normalize)
                    .collect(Collectors.toSet());
            validateConceptLayoutMetadata(diagnostics, entity, conceptUi, fieldNames);
            for (FieldAst field : entity.getFields()) {
                if (field.isId()) {
                    continue;
                }
                PresentationMetadataAst fieldUi = field.getUi();
                String fieldLabel = fieldUi == null ? null : fieldUi.getLabel();
                if (normalize(fieldLabel).isBlank()) {
                    diagnostics.add(uxDiagnostic(
                            "missing_field_label",
                            "Entity " + entity.getName() + " field " + field.getName()
                                    + ": presentation metadata should declare a user-visible label",
                            "concepts[" + entity.getName() + "].fields[" + field.getName() + "]",
                            entity.getName(),
                            field.getName(),
                            "fields",
                            "Add ui.label so guided editors and generated forms have a stable field title."
                    ));
                }
                if (fieldUi != null && fieldUi.getOrder() != null) {
                    if (fieldUi.getOrder() < 0) {
                        diagnostics.add(uxDiagnostic(
                                "invalid_field_order",
                                "Entity " + entity.getName() + " field " + field.getName()
                                        + ": presentation order must be >= 0",
                                "concepts[" + entity.getName() + "].fields[" + field.getName() + "]",
                                entity.getName(),
                                field.getName(),
                                "fields",
                                "Use a non-negative ui.order value so field ordering remains deterministic."
                        ));
                    } else {
                        String priorOwner = fieldOrderOwners.putIfAbsent(fieldUi.getOrder(), field.getName());
                        if (priorOwner != null) {
                            diagnostics.add(uxDiagnostic(
                                    "duplicate_field_order",
                                    "Entity " + entity.getName() + " field " + field.getName()
                                            + ": ui.order " + fieldUi.getOrder() + " is already used by " + priorOwner,
                                    "concepts[" + entity.getName() + "].fields[" + field.getName() + "]",
                                    entity.getName(),
                                    field.getName(),
                                    "fields",
                                    "Assign unique ui.order values within the concept so field rendering order is unambiguous."
                            ));
                        }
                    }
                }

                validateInteractionExpression(
                        diagnostics,
                        entity.getName(),
                        field.getName(),
                        "visibleWhen",
                        fieldUi == null ? null : fieldUi.getVisibleWhen(),
                        fieldNames
                );
                validateInteractionExpression(
                        diagnostics,
                        entity.getName(),
                        field.getName(),
                        "enabledWhen",
                        fieldUi == null ? null : fieldUi.getEnabledWhen(),
                        fieldNames
                );
                validateInteractionExpression(
                        diagnostics,
                        entity.getName(),
                        field.getName(),
                        "readonlyWhen",
                        fieldUi == null ? null : fieldUi.getReadonlyWhen(),
                        fieldNames
                );
                validateInteractionExpression(
                        diagnostics,
                        entity.getName(),
                        field.getName(),
                        "requiredWhen",
                        fieldUi == null ? null : fieldUi.getRequiredWhen(),
                        fieldNames
                );

                validateInteractionPickerMetadata(diagnostics, entity, field, fieldUi, entitiesByLower);
                validateFieldLayoutMetadata(
                        diagnostics,
                        entity,
                        field,
                        fieldUi,
                        listColumnOwners,
                        layoutSlotOwners
                );
            }
        }
        return List.copyOf(diagnostics);
    }

    private static void validateConceptLayoutMetadata(
            List<ValidationDiagnostic> diagnostics,
            ConceptAst entity,
            PresentationMetadataAst conceptUi,
            Set<String> fieldNames
    ) {
        if (conceptUi == null) {
            return;
        }
        validateDisplayMode(
                diagnostics,
                entity.getName(),
                null,
                "concepts[" + entity.getName() + "].ui.displayMode",
                conceptUi.getDisplayMode()
        );
        Integer formColumns = conceptUi.getFormColumns();
        if (formColumns != null && (formColumns < 1 || formColumns > 4)) {
            diagnostics.add(uxDiagnostic(
                    "invalid_form_columns",
                    "Concept " + entity.getName() + ": ui.formColumns must be between 1 and 4",
                    "concepts[" + entity.getName() + "].ui.formColumns",
                    entity.getName(),
                    null,
                    "concepts",
                    "Use ui.formColumns between 1 and 4 so form grids stay deterministic."
            ));
        }
        validateLayoutFieldReference(
                diagnostics,
                entity.getName(),
                "defaultSort",
                conceptUi.getDefaultSort(),
                fieldNames
        );
        validateLayoutFieldReference(
                diagnostics,
                entity.getName(),
                "defaultGroup",
                conceptUi.getDefaultGroup(),
                fieldNames
        );
    }

    private static void validateFieldLayoutMetadata(
            List<ValidationDiagnostic> diagnostics,
            ConceptAst entity,
            FieldAst field,
            PresentationMetadataAst fieldUi,
            Map<Integer, String> listColumnOwners,
            Map<String, String> layoutSlotOwners
    ) {
        if (fieldUi == null) {
            return;
        }
        String entityName = entity.getName();
        String fieldName = field.getName();
        String basePath = "concepts[" + entityName + "].fields[" + fieldName + "].ui";
        validateDisplayMode(diagnostics, entityName, fieldName, basePath + ".displayMode", fieldUi.getDisplayMode());

        if (fieldUi.getColumn() != null && fieldUi.getColumn() < 1) {
            diagnostics.add(uxDiagnostic(
                    "invalid_layout_column",
                    "Entity " + entityName + " field " + fieldName + ": ui.column must be >= 1",
                    basePath + ".column",
                    entityName,
                    fieldName,
                    "fields",
                    "Use ui.column values starting at 1 when placing fields into grid columns."
            ));
        }
        if (fieldUi.getColumnSpan() != null && fieldUi.getColumnSpan() < 1) {
            diagnostics.add(uxDiagnostic(
                    "invalid_layout_column_span",
                    "Entity " + entityName + " field " + fieldName + ": ui.columnSpan must be >= 1",
                    basePath + ".columnSpan",
                    entityName,
                    fieldName,
                    "fields",
                    "Use ui.columnSpan values starting at 1 so grid spans remain valid."
            ));
        }
        if (fieldUi.getColumnSpan() != null && fieldUi.getColumn() == null) {
            diagnostics.add(uxDiagnostic(
                    "incomplete_layout_slot",
                    "Entity " + entityName + " field " + fieldName + ": ui.columnSpan requires ui.column",
                    basePath + ".columnSpan",
                    entityName,
                    fieldName,
                    "fields",
                    "Set ui.column when using ui.columnSpan so the field has a deterministic grid anchor."
            ));
        }
        if (hasText(fieldUi.getWidth())) {
            String normalizedWidth = normalize(fieldUi.getWidth());
            if (!Set.of("xs", "sm", "md", "lg", "xl", "full", "auto").contains(normalizedWidth)) {
                diagnostics.add(uxDiagnostic(
                        "invalid_width_hint",
                        "Entity " + entityName + " field " + fieldName + ": ui.width must be one of xs, sm, md, lg, xl, full, auto",
                        basePath + ".width",
                        entityName,
                        fieldName,
                        "fields",
                        "Use a supported ui.width hint so responsive layout behavior stays predictable."
                ));
            }
        }
        if (hasText(fieldUi.getWidget())) {
            String normalizedType = normalize(field.getType());
            FieldWidgetDefaults.Compatibility compatibility =
                    FieldWidgetDefaults.classify(toFieldShape(field, normalizedType), fieldUi.getWidget());
            if (compatibility == FieldWidgetDefaults.Compatibility.DISCOURAGED) {
                diagnostics.add(uxDiagnostic(
                        "discouraged_widget",
                        "Entity " + entityName + " field " + fieldName + ": ui.widget \""
                                + fieldUi.getWidget().trim() + "\" is unusual for type " + field.getType()
                                + " and may render without the effect you expect",
                        basePath + ".widget",
                        entityName,
                        fieldName,
                        "fields",
                        "Double-check this widget/type combination, or switch to a widget better suited to this field's type."
                ));
            }
        }
        if (fieldUi.getListColumnOrder() != null) {
            if (fieldUi.getListColumnOrder() < 0) {
                diagnostics.add(uxDiagnostic(
                        "invalid_list_column_order",
                        "Entity " + entityName + " field " + fieldName + ": ui.listColumnOrder must be >= 0",
                        basePath + ".listColumnOrder",
                        entityName,
                        fieldName,
                        "fields",
                        "Use a non-negative ui.listColumnOrder so table column ordering is deterministic."
                ));
            } else {
                String priorOwner = listColumnOwners.putIfAbsent(fieldUi.getListColumnOrder(), fieldName);
                if (priorOwner != null) {
                    diagnostics.add(uxDiagnostic(
                            "duplicate_list_column_order",
                            "Entity " + entityName + " field " + fieldName
                                    + ": ui.listColumnOrder " + fieldUi.getListColumnOrder() + " is already used by " + priorOwner,
                            basePath + ".listColumnOrder",
                            entityName,
                            fieldName,
                            "fields",
                            "Assign unique ui.listColumnOrder values within the concept so list/table layouts remain stable."
                    ));
                }
            }
        }
        if (fieldUi.getColumn() != null && fieldUi.getOrder() != null && fieldUi.getOrder() >= 0) {
            String layoutSlot = normalize(fieldUi.getTab())
                    + "|" + normalize(fieldUi.getSection())
                    + "|" + fieldUi.getColumn()
                    + "|" + fieldUi.getOrder();
            String priorOwner = layoutSlotOwners.putIfAbsent(layoutSlot, fieldName);
            if (priorOwner != null) {
                diagnostics.add(uxDiagnostic(
                        "layout_slot_conflict",
                        "Entity " + entityName + " field " + fieldName
                                + ": ui column/order slot conflicts with " + priorOwner,
                        basePath,
                        entityName,
                        fieldName,
                        "fields",
                        "Use distinct tab/section/column/order combinations so preview layouts can place fields deterministically."
                ));
            }
        }
    }

    private static void validateInteractionExpression(
            List<ValidationDiagnostic> diagnostics,
            String entityName,
            String fieldName,
            String ruleName,
            String expression,
            Set<String> fieldNames
    ) {
        if (!hasText(expression)) {
            return;
        }
        InteractionExpressionAnalysis analysis = analyzeInteractionExpression(expression);
        String path = "concepts[" + entityName + "].fields[" + fieldName + "].ui." + ruleName;
        if (!analysis.valid()) {
            diagnostics.add(new ValidationDiagnostic(
                    ValidationLayer.UX_METADATA,
                    ValidationSeverity.WARNING,
                    "invalid_interaction_condition",
                    "Entity " + entityName + " field " + fieldName + ": " + ruleName
                            + " is invalid: " + analysis.error(),
                    "dsl:ux-metadata-validator",
                    path,
                    entityName,
                    fieldName,
                    "fields",
                    ruleName,
                    "Use a boolean condition expression with valid operators, balanced parentheses, and known field references.",
                    "validation.ux-metadata.invalid_interaction_condition"
            ));
            return;
        }
        for (String reference : analysis.references()) {
            if (!fieldNames.contains(normalize(reference))) {
                diagnostics.add(new ValidationDiagnostic(
                        ValidationLayer.UX_METADATA,
                        ValidationSeverity.WARNING,
                        "unknown_interaction_field_ref",
                        "Entity " + entityName + " field " + fieldName + ": " + ruleName
                                + " references unknown field " + reference,
                        "dsl:ux-metadata-validator",
                        path,
                        entityName,
                        fieldName,
                        "fields",
                        ruleName,
                        "Reference fields from the same concept when declaring interaction conditions.",
                        "validation.ux-metadata.unknown_interaction_field_ref"
                ));
            }
        }
    }

    private static void validateDisplayMode(
            List<ValidationDiagnostic> diagnostics,
            String entityName,
            String fieldName,
            String path,
            String displayMode
    ) {
        if (!hasText(displayMode)) {
            return;
        }
        String normalized = normalize(displayMode);
        if (Set.of("standard", "compact", "advanced", "summary").contains(normalized)) {
            return;
        }
        diagnostics.add(new ValidationDiagnostic(
                ValidationLayer.UX_METADATA,
                ValidationSeverity.WARNING,
                "invalid_display_mode",
                (fieldName == null
                        ? "Concept " + entityName
                        : "Entity " + entityName + " field " + fieldName)
                        + ": displayMode must be one of standard, compact, advanced, summary",
                "dsl:ux-metadata-validator",
                path,
                entityName,
                fieldName,
                fieldName == null ? "concepts" : "fields",
                "displayMode",
                "Use a supported displayMode value so preview surfaces can switch layout density deterministically.",
                "validation.ux-metadata.invalid_display_mode"
        ));
    }

    private static void validateLayoutFieldReference(
            List<ValidationDiagnostic> diagnostics,
            String entityName,
            String ruleName,
            String rawReference,
            Set<String> fieldNames
    ) {
        if (!hasText(rawReference)) {
            return;
        }
        String fieldRef = normalizeLayoutFieldReference(rawReference);
        if (fieldRef.isBlank() || !fieldNames.contains(fieldRef)) {
            diagnostics.add(new ValidationDiagnostic(
                    ValidationLayer.UX_METADATA,
                    ValidationSeverity.WARNING,
                    "unknown_layout_field_ref",
                    "Concept " + entityName + ": " + ruleName + " references unknown field " + rawReference,
                    "dsl:ux-metadata-validator",
                    "concepts[" + entityName + "].ui." + ruleName,
                    entityName,
                    null,
                    "concepts",
                    ruleName,
                    "Use a field from the same concept when declaring defaultSort or defaultGroup.",
                    "validation.ux-metadata.unknown_layout_field_ref"
            ));
        }
    }

    private static String normalizeLayoutFieldReference(String value) {
        if (!hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("-")) {
            trimmed = trimmed.substring(1).trim();
        }
        String normalized = normalize(trimmed);
        if (normalized.endsWith(" asc")) {
            normalized = normalize(trimmed.substring(0, trimmed.length() - 4));
        } else if (normalized.endsWith(" desc")) {
            normalized = normalize(trimmed.substring(0, trimmed.length() - 5));
        }
        return normalized;
    }

    private static void validateInteractionPickerMetadata(
            List<ValidationDiagnostic> diagnostics,
            ConceptAst entity,
            FieldAst field,
            PresentationMetadataAst fieldUi,
            Map<String, ConceptAst> entitiesByLower
    ) {
        if (fieldUi == null) {
            return;
        }
        boolean hasPickerMetadata = hasText(fieldUi.getPickerType())
                || fieldUi.getAllowInlineCreate() != null
                || !fieldUi.getSearchFields().isEmpty()
                || hasText(fieldUi.getFilterPreset());
        if (!hasPickerMetadata) {
            return;
        }

        String normalizedType = normalize(field.getType());
        String path = "concepts[" + entity.getName() + "].fields[" + field.getName() + "].ui";
        boolean referenceField = "reference".equals(normalizedType);
        if (!"reference".equals(normalizedType)) {
            diagnostics.add(new ValidationDiagnostic(
                    ValidationLayer.UX_METADATA,
                    ValidationSeverity.WARNING,
                    "interaction_metadata_not_supported",
                    "Entity " + entity.getName() + " field " + field.getName()
                            + ": picker interaction metadata is only supported on reference fields",
                    "dsl:ux-metadata-validator",
                    path,
                    entity.getName(),
                    field.getName(),
                    "fields",
                    null,
                    "Move pickerType/searchFields/allowInlineCreate/filterPreset to a reference field or remove them.",
                    "validation.ux-metadata.interaction_metadata_not_supported"
            ));
        }

        ConceptAst targetEntity = referenceField
                ? entitiesByLower.get(normalize(field.getReferenceTarget()))
                : null;
        Set<String> seen = new HashSet<>();
        for (String searchField : fieldUi.getSearchFields()) {
            String normalizedSearchField = normalize(searchField);
            if (normalizedSearchField.isBlank()) {
                diagnostics.add(uxDiagnostic(
                        "blank_interaction_search_field",
                        "Entity " + entity.getName() + " field " + field.getName()
                                + ": ui.searchFields entries must be non-blank",
                        path,
                        entity.getName(),
                        field.getName(),
                        "fields",
                        "Keep ui.searchFields as a non-empty list of concrete target field names."
                ));
                continue;
            }
            if (!seen.add(normalizedSearchField)) {
                diagnostics.add(uxDiagnostic(
                        "duplicate_interaction_search_field",
                        "Entity " + entity.getName() + " field " + field.getName()
                                + ": ui.searchFields contains duplicate field " + searchField,
                        path,
                        entity.getName(),
                        field.getName(),
                        "fields",
                        "List each ui.searchFields entry once so picker behavior remains deterministic."
                ));
                continue;
            }
            if (referenceField && targetEntity != null && targetEntity.getFields().stream()
                    .noneMatch(candidate -> normalizedSearchField.equals(normalize(candidate.getName())))) {
                diagnostics.add(uxDiagnostic(
                        "unknown_interaction_search_field",
                        "Entity " + entity.getName() + " field " + field.getName()
                                + ": ui.searchFields references unknown target field " + searchField,
                        path,
                        entity.getName(),
                        field.getName(),
                        "fields",
                        "Use target concept field names when overriding ui.searchFields on a reference field."
                ));
            }
        }
    }

    private static void validateFieldWidgetCompatibility(
            ConceptAst entity,
            FieldAst field,
            String normalizedType,
            List<String> errors
    ) {
        PresentationMetadataAst ui = field.getUi();
        String widget = ui == null ? null : ui.getWidget();
        if (!hasText(widget)) {
            return;
        }
        FieldWidgetDefaults.Compatibility compatibility =
                FieldWidgetDefaults.classify(toFieldShape(field, normalizedType), widget);
        if (compatibility == FieldWidgetDefaults.Compatibility.UNKNOWN_WIDGET) {
            errors.add("Entity " + entity.getName() + " field " + field.getName()
                    + ": unknown ui.widget \"" + widget.trim() + "\" (supported: "
                    + String.join(", ", new TreeSet<>(FieldWidgetDefaults.SUPPORTED_WIDGETS)) + ")");
        } else if (compatibility == FieldWidgetDefaults.Compatibility.INCOMPATIBLE) {
            errors.add("Entity " + entity.getName() + " field " + field.getName()
                    + ": ui.widget \"" + widget.trim() + "\" is incompatible with type " + field.getType());
        }
    }

    private static FieldWidgetDefaults.FieldShape toFieldShape(FieldAst field, String normalizedType) {
        boolean isReference = "reference".equals(normalizedType)
                || (field.getReferenceTarget() != null && !field.getReferenceTarget().isBlank())
                || field.getReferenceSemantics() != null;
        boolean isMultiReference = field.getReferenceSemantics() != null && field.getReferenceSemantics().isMultiple();
        boolean hasEnumValues = !field.getEnumValues().isEmpty();
        boolean isClosedEnumArray = "array".equals(normalizedType)
                && field.getSchema() != null
                && field.getSchema().getItems() != null
                && "enum".equals(normalize(field.getSchema().getItems().getType()))
                && field.getSchema().getItems().getEnumValues() != null
                && !field.getSchema().getItems().getEnumValues().isEmpty();
        boolean hasAnyEnumOptionIcon = field.getEnumOptions().stream()
                .anyMatch(option -> option != null && hasText(option.getIconHint()));
        PresentationMetadataAst ui = field.getUi();
        boolean hasImageFieldHint = ui != null && hasText(ui.getImageField());
        boolean hasCustomWidgetRef = ui != null && hasText(ui.getCustomWidgetRef());
        return new FieldWidgetDefaults.FieldShape(
                normalizedType,
                isReference,
                isMultiReference,
                hasEnumValues,
                isClosedEnumArray,
                hasAnyEnumOptionIcon,
                hasImageFieldHint,
                hasCustomWidgetRef
        );
    }

    private static ValidationDiagnostic uxDiagnostic(
            String code,
            String message,
            String path,
            String concept,
            String field,
            String section,
            String suggestedFix
    ) {
        return new ValidationDiagnostic(
                ValidationLayer.UX_METADATA,
                ValidationSeverity.WARNING,
                code,
                message,
                "dsl:ux-metadata-validator",
                path,
                concept,
                field,
                section,
                null,
                suggestedFix,
                "validation.ux-metadata." + code
        );
    }

    private static Map<String, ConceptAst> indexEntities(Collection<ConceptAst> entities, List<String> errors) {
        Map<String, ConceptAst> byLower = new LinkedHashMap<>();
        for (ConceptAst entity : entities) {
            String key = normalize(entity.getName());
            if (byLower.containsKey(key)) {
                errors.add("Duplicate concept name: " + entity.getName());
            } else {
                byLower.put(key, entity);
            }
        }
        return byLower;
    }

    private static void validateEntityLocalFields(ModelAst modelAst, List<String> errors) {
        for (ConceptAst e : modelAst.getConcepts()) {
            Set<String> localFieldNames = new HashSet<>();
            for (FieldAst f : e.getFields()) {
                String fieldKey = normalize(f.getName());
                if (!localFieldNames.add(fieldKey)) {
                    errors.add("Entity " + e.getName() + ": duplicate local field name " + f.getName());
                }
            }
        }
    }

    private static Map<String, DomainTypeAst> validateDomainTypes(ModelAst modelAst, List<String> errors) {
        Map<String, DomainTypeAst> byLower = new LinkedHashMap<>();
        for (DomainTypeAst domainType : modelAst.getDomainTypes()) {
            if (domainType == null) {
                continue;
            }
            String key = normalize(domainType.getName());
            if (key.isBlank()) {
                errors.add("Domain type name must be non-blank");
                continue;
            }
            if (byLower.containsKey(key)) {
                errors.add("Duplicate domain type name: " + domainType.getName());
                continue;
            }
            String baseType = normalize(domainType.getBaseType());
            if (!DOMAIN_BASE_TYPES.contains(baseType)) {
                errors.add("Domain type " + domainType.getName() + ": unsupported base type " + domainType.getBaseType());
            }
            validateDomainTypeValidationSchema(domainType, errors);
            validateDomainTypeExamples(domainType, errors);
            validateDomainTypeNormalization(domainType, errors);
            byLower.put(key, domainType);
        }
        return byLower;
    }

    private static void validateDomainTypeValidationSchema(DomainTypeAst domainType, List<String> errors) {
        SchemaAst validationSchema = domainType.getValidationSchema();
        if (validationSchema == null) {
            return;
        }
        String validationType = normalize(validationSchema.getType());
        String baseType = normalize(domainType.getBaseType());
        if (!validationType.isBlank() && !areCompatibleTypes(validationType, baseType)) {
            errors.add("Domain type " + domainType.getName()
                    + ": validation schema type " + validationSchema.getType()
                    + " is incompatible with base type " + domainType.getBaseType());
        }
        if (!validationSchema.getProperties().isEmpty() || validationSchema.getItems() != null) {
            errors.add("Domain type " + domainType.getName()
                    + ": validation schema must be scalar-oriented in this DSL version");
        }
    }

    private static void validateDomainTypeExamples(DomainTypeAst domainType, List<String> errors) {
        Set<String> examples = new HashSet<>();
        for (String example : domainType.getExamples()) {
            String normalized = normalize(example);
            if (normalized.isBlank()) {
                errors.add("Domain type " + domainType.getName() + ": examples must be non-blank");
                continue;
            }
            if (!examples.add(normalized)) {
                errors.add("Domain type " + domainType.getName() + ": duplicate example " + example);
            }
        }
    }

    private static void validateDomainTypeNormalization(DomainTypeAst domainType, List<String> errors) {
        Set<String> rules = new HashSet<>();
        for (String rule : domainType.getNormalizationRules()) {
            String normalized = normalize(rule);
            if (normalized.isBlank()) {
                errors.add("Domain type " + domainType.getName() + ": normalization rules must be non-blank");
                continue;
            }
            if (!rules.add(normalized)) {
                errors.add("Domain type " + domainType.getName() + ": duplicate normalization rule " + rule);
            }
        }
    }

    private static void validateScalarReferenceLookupMetadata(
            String entityName,
            FieldAst field,
            String normalizedType,
            Map<String, ConceptAst> entitiesByLower,
            Map<String, EffectiveEntity> effectiveCache,
            List<String> errors
    ) {
        if (field.isId()) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": reference lookup metadata is not allowed on id fields");
            return;
        }

        if (!isScalarLookupReferenceType(normalizedType)) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": ref/reference metadata on non-reference fields is supported only for scalar id fields");
            return;
        }

        String target = normalize(field.getReferenceTarget());
        if (target.isBlank()) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": reference lookup metadata must declare ref (or reference.target)");
            return;
        }

        ConceptAst targetConceptAst = entitiesByLower.get(target);
        if (targetConceptAst == null) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": reference target not found: " + field.getReferenceTarget());
            return;
        }

        EffectiveEntity effectiveTarget = resolveEffective(
                targetConceptAst,
                entitiesByLower,
                effectiveCache,
                new HashSet<>(),
                errors
        );
        validateScalarReferenceTargetId(entityName, field, normalizedType, effectiveTarget, errors);
        validateReferenceSemantics(entityName, field, effectiveTarget, errors);
        validateReferenceFieldHint(
                entityName, field, effectiveTarget,
                field.getUi() == null ? null : field.getUi().getImageField(),
                "imageField", errors
        );
    }

    private static boolean isScalarLookupReferenceType(String normalizedType) {
        return "uuid".equals(normalizedType)
                || "string".equals(normalizedType)
                || "integer".equals(normalizedType)
                || "long".equals(normalizedType);
    }

    private static void validateScalarReferenceTargetId(
            String entityName,
            FieldAst field,
            String normalizedType,
            EffectiveEntity targetEntity,
            List<String> errors
    ) {
        List<FieldAst> targetIdFields = targetEntity.fields().stream()
                .filter(FieldAst::isId)
                .toList();

        if (targetIdFields.size() != 1) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": scalar reference target " + field.getReferenceTarget()
                    + " must declare exactly one id field");
            return;
        }

        FieldAst targetId = targetIdFields.get(0);
        String targetIdType = normalize(targetId.getType());
        if (!normalizedType.equals(targetIdType)) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": scalar reference type " + normalizedType
                    + " must match target id type " + field.getReferenceTarget() + "." + targetId.getName()
                    + " (" + targetIdType + ")");
        }
    }

    private static void validateReferenceSemantics(
            String entityName,
            FieldAst field,
            EffectiveEntity targetEntity,
            List<String> errors
    ) {
        ReferenceSemanticsAst referenceSemantics = field.getReferenceSemantics();
        if (referenceSemantics == null) {
            return;
        }
        String inlineCreatePolicy = normalize(referenceSemantics.getInlineCreatePolicy());
        if (!inlineCreatePolicy.isBlank()
                && !"allow".equals(inlineCreatePolicy)
                && !"deny".equals(inlineCreatePolicy)) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": inlineCreate must be allow or deny");
        }

        String onDelete = normalize(referenceSemantics.getOnDelete());
        if (!onDelete.isBlank()
                && !"restrict".equals(onDelete)
                && !"cascade".equals(onDelete)
                && !"nullify".equals(onDelete)) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": reference onDelete must be one of restrict, cascade, nullify");
        }
        // nullify => SET NULL, which cannot apply to a NOT NULL column. A required port and an
        // N:M port (whose junction target column is part of a NOT NULL composite PK) both forbid it.
        if ("nullify".equals(onDelete)) {
            if (field.isRequired()) {
                errors.add("Entity " + entityName + " field " + field.getName()
                        + ": reference onDelete=nullify is invalid on a required field (SET NULL cannot apply to a NOT NULL column)");
            }
            if (referenceSemantics.isMultiple()) {
                errors.add("Entity " + entityName + " field " + field.getName()
                        + ": reference onDelete=nullify is invalid on a multiple (N:M) bond (the junction key cannot be null)");
            }
        }

        String via = normalize(referenceSemantics.getVia());
        if (!via.isBlank()) {
            FieldAst anchor = targetEntity.fields().stream()
                    .filter(candidate -> via.equals(normalize(candidate.getName())))
                    .findFirst()
                    .orElse(null);
            if (anchor == null) {
                errors.add("Entity " + entityName + " field " + field.getName()
                        + ": reference via anchor not found on target "
                        + field.getReferenceTarget() + ": " + referenceSemantics.getVia());
            } else if (!isConnectableAnchor(anchor)) {
                errors.add("Entity " + entityName + " field " + field.getName()
                        + ": reference via must target a connectable anchor (the id field, or a non-id field"
                        + " with unique=true and connectable:anchor): " + referenceSemantics.getVia());
            }
        }

        validateReferenceFieldHint(entityName, field, targetEntity, referenceSemantics.getDisplayField(), "displayField", errors);
        validateReferenceFieldHintList(entityName, field, targetEntity, referenceSemantics.getSearchFields(), "searchFields", errors);
        validateReferenceFieldHintList(entityName, field, targetEntity, referenceSemantics.getPreviewFields(), "previewFields", errors);
        validateReferenceFieldHintList(entityName, field, targetEntity, referenceSemantics.getPickerColumns(), "pickerColumns", errors);
        validateReferenceTemplate(entityName, field, targetEntity, referenceSemantics.getDisplayTemplate(), "displayTemplate", errors);
        validateReferenceTemplate(entityName, field, targetEntity, referenceSemantics.getPreviewCardTemplate(), "previewCardTemplate", errors);
    }

    /**
     * Bond truth integrity ("no upward edges"): a bond may not point at a concept whose
     * truth level is below the source's. Surfaced as a warning so it never blocks creation
     * (truth is restrictive only at release); a release gate can later elevate it to an error.
     */
    private static void validateBondTruthEdge(
            ConceptAst source,
            FieldAst port,
            ConceptAst target,
            List<String> warnings
    ) {
        if (source == null || target == null) {
            return;
        }
        TruthLevel sourceTruth = source.getTruthLevel();
        TruthLevel targetTruth = target.getTruthLevel();
        if (sourceTruth == null || targetTruth == null) {
            return;
        }
        if (targetTruth.rank() < sourceTruth.rank()) {
            warnings.add("Entity " + source.getName() + " field " + port.getName()
                    + ": bond points at lower-truth concept " + target.getName()
                    + " (" + sourceTruth.code() + " -> " + targetTruth.code()
                    + "); a concept may not depend on a less-true concept (no upward truth edges)");
        }
    }

    private static boolean isConnectableAnchor(FieldAst field) {
        return field.isId()
                || (field.isUnique() && "anchor".equals(normalize(field.getConnectable())));
    }

    private static void validateReferenceFieldHint(
            String entityName,
            FieldAst field,
            EffectiveEntity targetEntity,
            String targetFieldName,
            String hintName,
            List<String> errors
    ) {
        String normalized = normalize(targetFieldName);
        if (normalized.isBlank()) {
            return;
        }
        boolean exists = targetEntity.fields().stream()
                .anyMatch(candidate -> normalized.equals(normalize(candidate.getName())));
        if (!exists) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": reference " + hintName + " not found on target "
                    + field.getReferenceTarget() + ": " + targetFieldName);
        }
    }

    private static void validateReferenceFieldHintList(
            String entityName,
            FieldAst field,
            EffectiveEntity targetEntity,
            List<String> fieldHints,
            String hintName,
            List<String> errors
    ) {
        Set<String> seen = new HashSet<>();
        for (String fieldHint : fieldHints) {
            String normalized = normalize(fieldHint);
            if (normalized.isBlank()) {
                errors.add("Entity " + entityName + " field " + field.getName()
                        + ": reference " + hintName + " entries must be non-blank");
                continue;
            }
            if (!seen.add(normalized)) {
                errors.add("Entity " + entityName + " field " + field.getName()
                        + ": reference " + hintName + " contains duplicate field " + fieldHint);
                continue;
            }
            validateReferenceFieldHint(entityName, field, targetEntity, fieldHint, hintName, errors);
        }
    }

    private static void validateReferenceTemplate(
            String entityName,
            FieldAst field,
            EffectiveEntity targetEntity,
            String template,
            String templateName,
            List<String> errors
    ) {
        String normalizedTemplate = normalize(template);
        if (normalizedTemplate.isBlank()) {
            return;
        }
        Set<String> referencedFields = extractTemplateFieldRefs(template);
        for (String referencedField : referencedFields) {
            boolean exists = targetEntity.fields().stream()
                    .anyMatch(candidate -> normalize(candidate.getName()).equals(normalize(referencedField)));
            if (!exists) {
                errors.add("Entity " + entityName + " field " + field.getName()
                        + ": reference " + templateName + " references unknown target field "
                        + referencedField);
            }
        }
    }

    private static void validateInheritanceGraph(Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Set<String> globallyVisited = new HashSet<>();

        for (ConceptAst entity : entitiesByLower.values()) {
            String entityKey = normalize(entity.getName());

            String parentName = entity.getExtendsName();
            if (parentName != null && !parentName.isBlank()) {
                ConceptAst parent = entitiesByLower.get(normalize(parentName));
                if (parent == null) {
                    errors.add("Entity " + entity.getName() + ": extends unknown base " + parentName);
                } else if (normalize(parentName).equals(entityKey)) {
                    errors.add("Entity " + entity.getName() + ": cannot extend itself");
                }
            }

            detectInheritanceCycle(entity, entitiesByLower, globallyVisited, new HashSet<>(), errors);
        }
    }

    private static void detectInheritanceCycle(
            ConceptAst current,
            Map<String, ConceptAst> entitiesByLower,
            Set<String> globallyVisited,
            Set<String> stack,
            List<String> errors
    ) {
        String key = normalize(current.getName());
        if (globallyVisited.contains(key)) return;
        if (!stack.add(key)) {
            errors.add("Inheritance cycle detected involving entity " + current.getName());
            return;
        }

        String parentName = current.getExtendsName();
        if (parentName != null && !parentName.isBlank()) {
            ConceptAst parent = entitiesByLower.get(normalize(parentName));
            if (parent != null) {
                detectInheritanceCycle(parent, entitiesByLower, globallyVisited, stack, errors);
            }
        }

        stack.remove(key);
        globallyVisited.add(key);
    }

    private static EffectiveEntity resolveEffective(
            ConceptAst entity,
            Map<String, ConceptAst> entitiesByLower,
            Map<String, EffectiveEntity> cache,
            Set<String> stack,
            List<String> errors
    ) {
        String key = normalize(entity.getName());
        EffectiveEntity cached = cache.get(key);
        if (cached != null) return cached;

        if (!stack.add(key)) {
            errors.add("Inheritance cycle detected involving entity " + entity.getName());
            return new EffectiveEntity(List.of(), List.of());
        }

        LinkedHashMap<String, FieldAst> fieldsByLower = new LinkedHashMap<>();
        List<InvariantAst> invariants = new ArrayList<>();

        String parentName = entity.getExtendsName();
        if (parentName != null && !parentName.isBlank()) {
            ConceptAst parent = entitiesByLower.get(normalize(parentName));
            if (parent != null) {
                EffectiveEntity parentEffective = resolveEffective(parent, entitiesByLower, cache, stack, errors);
                for (FieldAst parentField : parentEffective.fields()) {
                    fieldsByLower.put(normalize(parentField.getName()), parentField);
                }
                invariants.addAll(parentEffective.invariants());
            }
        }

        for (FieldAst field : entity.getFields()) {
            String fieldKey = normalize(field.getName());
            FieldAst parentField = fieldsByLower.get(fieldKey);
            if (parentField != null) {
                errors.add("Entity " + entity.getName() + ": duplicate field name in inheritance: "
                        + field.getName() + " (already declared in base concept)");
                continue;
            }
            fieldsByLower.put(fieldKey, field);
        }

        invariants.addAll(entity.getInvariants());

        EffectiveEntity effective = new EffectiveEntity(new ArrayList<>(fieldsByLower.values()), invariants);
        cache.put(key, effective);
        stack.remove(key);
        return effective;
    }

    private static void validateCapabilities(ModelAst modelAst, List<String> errors) {
        Set<String> names = new HashSet<>();
        for (CapabilityAst capability : modelAst.getCapabilities()) {
            if (!names.add(normalize(capability.getName()))) {
                errors.add("Duplicate capability name: " + capability.getName());
            }
            String type = normalize(capability.getType());
            if (!type.isBlank() && FORBIDDEN_TECH_KEYWORDS.stream().anyMatch(type::contains)) {
                errors.add("Capability type must be technology-neutral: " + capability.getType());
            }

            Set<String> opNames = new HashSet<>();
            for (CapabilityOperationAst operation : capability.getOperations()) {
                String opName = normalize(operation.getName());
                if (opName.isBlank()) {
                    errors.add("Capability " + capability.getName() + ": operation name must be non-blank");
                } else if (!opNames.add(opName)) {
                    errors.add("Capability " + capability.getName()
                            + ": duplicate operation name " + operation.getName());
                }
                validateCapabilityPolicy(
                        "Capability " + capability.getName() + " operation " + operation.getName() + ": ",
                        operation.getExecutionPolicy(),
                        errors
                );
            }
        }
    }

    private static void validateBindings(ModelAst modelAst, List<String> errors) {
        Set<String> declaredCapabilities = new HashSet<>(BUILTIN_CAPABILITIES);
        for (CapabilityAst capability : modelAst.getCapabilities()) {
            declaredCapabilities.add(normalize(capability.getName()));
        }

        Set<String> boundCapabilities = new HashSet<>();
        for (CapabilityBindingAst binding : modelAst.getBindings()) {
            String capabilityKey = normalize(binding.getCapability());
            if (!boundCapabilities.add(capabilityKey)) {
                errors.add("Duplicate binding for capability: " + binding.getCapability());
            }
            if (!declaredCapabilities.contains(capabilityKey)) {
                errors.add("Binding references unknown capability: " + binding.getCapability());
            }
        }
    }

    private static void validateEvents(ModelAst modelAst, List<String> errors) {
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

    private static void validateFlows(
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

        for (FlowAst flow : modelAst.getFlows()) {
            String flowKey = normalize(flow.getName());
            if (!flowNames.add(flowKey)) {
                errors.add("Duplicate flow name: " + flow.getName());
            }

            if (flow.getSteps().isEmpty()) {
                errors.add("Flow " + flow.getName() + ": steps must not be empty");
            }

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
                    new HashSet<>(),
                    errors
            );
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
            if (hasPersistenceSemantics(step.getThenSteps()) || hasPersistenceSemantics(step.getElseSteps())) {
                return true;
            }
        }
        return false;
    }

    private static void validateFlowSteps(
            FlowAst flow,
            List<StepAst> steps,
            Map<String, Set<String>> operationsByCapability,
            Set<String> eventNames,
            Set<String> conceptInvariantRefs,
            Set<String> referencedCapabilities,
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
                        knownStepNames,
                        errors
                );
                case "await" -> validateAwaitStep(flow, step, eventNames, errors);
                default -> errors.add("Flow " + flow.getName() + " step " + step.getName()
                        + ": unsupported step type " + step.getType());
            }
        }
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
                    + ": invariant step must reference invariants or define scope");
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
            Set<String> knownStepNames,
            List<String> errors
    ) {
        String condition = normalize(step.getCondition());
        if (condition.isBlank()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": branch step must define condition");
        }
        if (step.getThenSteps().isEmpty()) {
            errors.add("Flow " + flow.getName() + " step " + step.getName()
                    + ": branch step must define non-empty then steps");
        }

        if (!step.getThenSteps().isEmpty()) {
            validateFlowSteps(flow, step.getThenSteps(), operationsByCapability, eventNames,
                    conceptInvariantRefs, referencedCapabilities, knownStepNames, errors);
        }
        if (!step.getElseSteps().isEmpty()) {
            validateFlowSteps(flow, step.getElseSteps(), operationsByCapability, eventNames,
                    conceptInvariantRefs, referencedCapabilities, knownStepNames, errors);
        }
    }

    private static void validateAwaitStep(
            FlowAst flow,
            StepAst step,
            Set<String> eventNames,
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
    }

    private static void validateCapabilityPolicy(
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

    private static void validateReferencedCapabilityBindings(
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

    private static void validateObjectFieldSchema(String entityName, FieldAst field, List<String> errors) {
        SchemaAst schema = field.getSchema();
        if (schema == null || !"object".equals(normalize(schema.getType()))) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": object field must declare object schema with properties");
            return;
        }
        validateNestedSchema(entityName, field.getName(), field.getName(), schema, errors);
    }

    private static void validateArrayFieldSchema(String entityName, FieldAst field, List<String> errors) {
        SchemaAst schema = field.getSchema();
        if (schema == null || !"array".equals(normalize(schema.getType()))) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": array field must declare array schema with items");
            return;
        }
        validateNestedSchema(entityName, field.getName(), field.getName(), schema, errors);
    }

    private static void validateNestedSchema(
            String entityName,
            String fieldName,
            String schemaPath,
            SchemaAst schema,
            List<String> errors
    ) {
        if (schema == null) {
            return;
        }
        if (!fieldName.equals(schemaPath)
                && (hasText(schema.getDefaultExpression()) || hasText(schema.getDerivedExpression()))) {
            errors.add("Entity " + entityName + " field " + fieldName
                    + ": nested schema at " + schemaPath
                    + " cannot declare defaultExpression/derivedExpression yet");
        }
        String normalizedType = normalize(schema.getType());
        if ("object".equals(normalizedType)) {
            if (schema.getProperties().isEmpty()) {
                errors.add("Entity " + entityName + " field " + fieldName
                        + ": object schema at " + schemaPath + " must declare at least one property");
                return;
            }
            Set<String> propertyNames = new HashSet<>();
            for (String propertyName : schema.getProperties().keySet()) {
                propertyNames.add(normalize(propertyName));
            }
            for (String requiredField : schema.getRequired()) {
                if (!propertyNames.contains(normalize(requiredField))) {
                    errors.add("Entity " + entityName + " field " + fieldName
                            + ": object schema at " + schemaPath + " marks missing required property " + requiredField);
                }
            }
            for (Map.Entry<String, SchemaAst> property : schema.getProperties().entrySet()) {
                validateNestedSchema(entityName, fieldName, schemaPath + "." + property.getKey(), property.getValue(), errors);
            }
            return;
        }
        if ("array".equals(normalizedType)) {
            if (schema.getItems() == null) {
                errors.add("Entity " + entityName + " field " + fieldName
                        + ": array schema at " + schemaPath + " must declare items schema");
                return;
            }
            if (schema.getMinItems() != null && schema.getMinItems() < 0) {
                errors.add("Entity " + entityName + " field " + fieldName
                        + ": array schema at " + schemaPath + " minItems must be >= 0");
            }
            if (schema.getMaxItems() != null && schema.getMaxItems() < 0) {
                errors.add("Entity " + entityName + " field " + fieldName
                        + ": array schema at " + schemaPath + " maxItems must be >= 0");
            }
            if (schema.getMinItems() != null && schema.getMaxItems() != null && schema.getMaxItems() < schema.getMinItems()) {
                errors.add("Entity " + entityName + " field " + fieldName
                        + ": array schema at " + schemaPath + " maxItems must be >= minItems");
            }
            String duplicationPolicy = normalize(schema.getDuplicationPolicy());
            if (!duplicationPolicy.isBlank() && !"allow".equals(duplicationPolicy) && !"deny".equals(duplicationPolicy)) {
                errors.add("Entity " + entityName + " field " + fieldName
                        + ": array schema at " + schemaPath + " duplicationPolicy must be allow or deny");
            }
            if (schema.getItemIdentityField() != null && !schema.getItemIdentityField().isBlank()) {
                SchemaAst items = schema.getItems();
                if (!"object".equals(normalize(items.getType()))) {
                    errors.add("Entity " + entityName + " field " + fieldName
                            + ": array schema at " + schemaPath + " itemIdentityField requires object items");
                } else if (items.getProperties().keySet().stream().noneMatch(name -> normalize(name).equals(normalize(schema.getItemIdentityField())))) {
                    errors.add("Entity " + entityName + " field " + fieldName
                            + ": array schema at " + schemaPath + " itemIdentityField not found: " + schema.getItemIdentityField());
                }
            }
            validateNestedSchema(entityName, fieldName, schemaPath + "[]", schema.getItems(), errors);
        }
    }

    private static boolean areCompatibleTypes(String left, String right) {
        return normalizeComparableType(left).equals(normalizeComparableType(right));
    }

    private static String normalizeComparableType(String value) {
        String normalized = normalize(value);
        return switch (normalized) {
            case "int" -> "integer";
            default -> normalized;
        };
    }

    private static void validateLifecycle(ConceptAst entity, EffectiveEntity effective, List<String> errors) {
        LifecycleAst lifecycle = entity.getLifecycle();
        if (lifecycle == null) {
            return;
        }

        String conceptName = entity.getName();
        String statusFieldName = lifecycle.getStatusField() == null || lifecycle.getStatusField().isBlank()
                ? "status"
                : lifecycle.getStatusField().trim();

        Map<String, FieldAst> fieldsByName = new HashMap<>();
        for (FieldAst field : effective.fields()) {
            fieldsByName.put(normalize(field.getName()), field);
        }

        FieldAst statusField = fieldsByName.get(normalize(statusFieldName));
        if (statusField == null) {
            errors.add("Entity " + conceptName + " lifecycle: statusField '" + statusFieldName + "' not found");
            return;
        }
        if (!"enum".equals(normalize(statusField.getType()))) {
            errors.add("Entity " + conceptName + " lifecycle: statusField '" + statusFieldName
                    + "' must be enum");
            return;
        }

        Set<String> statusValues = statusField.getEnumValues().stream()
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        if (statusValues.isEmpty()) {
            errors.add("Entity " + conceptName + " lifecycle: statusField '" + statusFieldName
                    + "' must declare enumValues");
            return;
        }

        Set<String> declaredStates = new LinkedHashSet<>();
        int initialStateCount = 0;
        for (StateMachineStateAst state : lifecycle.getStates()) {
            if (state == null) {
                continue;
            }
            String value = state.getValue() == null ? "" : state.getValue().trim();
            if (value.isBlank()) {
                errors.add("Entity " + conceptName + " lifecycle: state value must be non-blank");
                continue;
            }
            String valueKey = normalize(value);
            if (!statusValues.contains(valueKey)) {
                errors.add("Entity " + conceptName + " lifecycle: state '" + value
                        + "' is not a valid value of status field '" + statusFieldName + "'");
            }
            if (!declaredStates.add(valueKey)) {
                errors.add("Entity " + conceptName + " lifecycle: duplicate state '" + value + "'");
            }
            if (state.getLabel() != null && state.getLabel().isBlank()) {
                errors.add("Entity " + conceptName + " lifecycle state '" + value + "': label must be non-blank");
            }
            if (state.isInitial()) {
                initialStateCount++;
            }
            for (Map.Entry<String, String> metadataEntry : state.getMetadata().entrySet()) {
                String key = metadataEntry.getKey() == null ? "" : metadataEntry.getKey().trim();
                if (key.isBlank()) {
                    errors.add("Entity " + conceptName + " lifecycle state '" + value + "': metadata keys must be non-blank");
                }
            }
        }
        if (!lifecycle.getStates().isEmpty() && initialStateCount != 1) {
            errors.add("Entity " + conceptName + " lifecycle: states must declare exactly one initial state");
        }

        if (lifecycle.getTransitions().isEmpty()) {
            errors.add("Entity " + conceptName + " lifecycle: transitions must declare at least one transition");
            return;
        }

        Set<String> transitionPairs = new HashSet<>();
        for (StateTransitionAst transition : lifecycle.getTransitions()) {
            if (transition == null) {
                continue;
            }
            String from = transition.getFrom() == null ? "" : transition.getFrom().trim();
            String to = transition.getTo() == null ? "" : transition.getTo().trim();
            if (from.isBlank() || to.isBlank()) {
                errors.add("Entity " + conceptName + " lifecycle: transition requires non-blank from/to");
                continue;
            }
            String fromKey = normalize(from);
            String toKey = normalize(to);
            if (!statusValues.contains(fromKey)) {
                errors.add("Entity " + conceptName + " lifecycle: transition from '" + from
                        + "' is not a valid value of status field '" + statusFieldName + "'");
            }
            if (!statusValues.contains(toKey)) {
                errors.add("Entity " + conceptName + " lifecycle: transition to '" + to
                        + "' is not a valid value of status field '" + statusFieldName + "'");
            }
            String pairKey = fromKey + "->" + toKey;
            if (!transitionPairs.add(pairKey)) {
                errors.add("Entity " + conceptName + " lifecycle: duplicate transition " + from + " -> " + to);
            }
            if (!declaredStates.isEmpty()) {
                if (!declaredStates.contains(fromKey)) {
                    errors.add("Entity " + conceptName + " lifecycle: transition from '" + from
                            + "' is not declared in lifecycle.states");
                }
                if (!declaredStates.contains(toKey)) {
                    errors.add("Entity " + conceptName + " lifecycle: transition to '" + to
                            + "' is not declared in lifecycle.states");
                }
            }
            if (transition.getEvent() != null && transition.getEvent().isBlank()) {
                errors.add("Entity " + conceptName + " lifecycle transition " + from + " -> " + to
                        + ": event must be non-blank when provided");
            }
            if (transition.getGuard() != null) {
                if (transition.getGuard().isBlank()) {
                    errors.add("Entity " + conceptName + " lifecycle transition " + from + " -> " + to
                            + ": guard must be non-blank when provided");
                } else if (!isSupportedLifecycleGuard(transition.getGuard())) {
                    errors.add("Entity " + conceptName + " lifecycle transition " + from + " -> " + to
                            + ": guard uses an unsupported expression format");
                }
            }
            if (transition.getActionLabel() != null && transition.getActionLabel().isBlank()) {
                errors.add("Entity " + conceptName + " lifecycle transition " + from + " -> " + to
                        + ": actionLabel must be non-blank when provided");
            }
            for (Map.Entry<String, String> metadataEntry : transition.getMetadata().entrySet()) {
                String key = metadataEntry.getKey() == null ? "" : metadataEntry.getKey().trim();
                if (key.isBlank()) {
                    errors.add("Entity " + conceptName + " lifecycle transition " + from + " -> " + to
                            + ": metadata keys must be non-blank");
                }
            }
            for (String requiredField : transition.getRequiredPayload()) {
                if (!fieldsByName.containsKey(normalize(requiredField))) {
                    errors.add("Entity " + conceptName + " lifecycle transition " + from + " -> " + to
                            + ": requiredPayload references unknown field " + requiredField);
                }
            }
        }
    }

    private static boolean isSupportedLifecycleGuard(String expression) {
        String trimmed = expression == null ? "" : expression.trim();
        if (trimmed.isBlank()) {
            return false;
        }
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return true;
        }
        int notEqualsIndex = trimmed.indexOf("!=");
        if (notEqualsIndex >= 0) {
            return isSupportedLifecycleGuardToken(trimmed.substring(0, notEqualsIndex))
                    && isSupportedLifecycleGuardToken(trimmed.substring(notEqualsIndex + 2));
        }
        int equalsIndex = trimmed.indexOf("==");
        if (equalsIndex >= 0) {
            return isSupportedLifecycleGuardToken(trimmed.substring(0, equalsIndex))
                    && isSupportedLifecycleGuardToken(trimmed.substring(equalsIndex + 2));
        }
        return isSupportedLifecycleGuardToken(trimmed);
    }

    private static boolean isSupportedLifecycleGuardToken(String token) {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.isBlank()) {
            return false;
        }
        if ("null".equalsIgnoreCase(trimmed)
                || "true".equalsIgnoreCase(trimmed)
                || "false".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.length() >= 2;
        }
        if (trimmed.matches("-?\\d+(\\.\\d+)?")) {
            return true;
        }
        return trimmed.matches("(\\$payload|\\$current|\\$next)(\\.[A-Za-z_][A-Za-z0-9_.]*)?")
                || trimmed.matches("[A-Za-z_][A-Za-z0-9_.]*");
    }

    private static void validateOrchestrationRules(ModelAst modelAst, List<String> errors) {
        if (modelAst.getOrchestrationRules().isEmpty()) {
            return;
        }

        Map<String, ConceptAst> entitiesByName = modelAst.getConcepts().stream()
                .collect(Collectors.toMap(
                        entity -> normalize(entity.getName()),
                        entity -> entity,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, EventAst> eventsByName = modelAst.getEvents().stream()
                .collect(Collectors.toMap(
                        event -> normalize(event.getName()),
                        event -> event,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, CapabilityAst> capabilitiesByName = modelAst.getCapabilities().stream()
                .collect(Collectors.toMap(
                        capability -> normalize(capability.getName()),
                        capability -> capability,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        Set<String> orchestrationNames = new HashSet<>();
        for (OrchestrationAst orchestration : modelAst.getOrchestrationRules()) {
            if (orchestration == null) {
                continue;
            }
            String name = orchestration.getName();
            String nameKey = normalize(name);
            if (nameKey.isBlank()) {
                errors.add("Orchestration rule name must be non-blank");
                continue;
            }
            if (!orchestrationNames.add(nameKey)) {
                errors.add("Duplicate orchestration rule name: " + name);
            }

            OrchestrationTriggerAst trigger = orchestration.getTrigger();
            if (trigger == null) {
                errors.add("Orchestration " + name + ": trigger is required");
                continue;
            }
            String triggerType = normalize(trigger.getType());
            if (!"event".equals(triggerType)) {
                errors.add("Orchestration " + name + ": trigger type must be 'event'");
            }
            String triggerEvent = normalize(trigger.getEvent());
            if (triggerEvent.isBlank()) {
                errors.add("Orchestration " + name + ": trigger event is required");
            } else if (!eventsByName.containsKey(triggerEvent)) {
                errors.add("Orchestration " + name + ": trigger event not found: " + trigger.getEvent());
            }

            Set<String> eventPayloadFields = Set.of();
            if (!triggerEvent.isBlank()) {
                EventAst triggerEventAst = eventsByName.get(triggerEvent);
                if (triggerEventAst != null) {
                    eventPayloadFields = triggerEventAst.getPayloadFields().stream()
                            .map(EventPayloadAst::getName)
                            .map(SemanticValidator::normalize)
                            .collect(Collectors.toSet());
                }
            }
            validateOrchestrationCondition(name, orchestration.getCondition(), eventPayloadFields, errors);

            List<OrchestrationActionAst> actionSequence = orchestration.getActions().isEmpty()
                    ? (orchestration.getAction() == null ? List.of() : List.of(orchestration.getAction()))
                    : orchestration.getActions();
            if (actionSequence.isEmpty()) {
                errors.add("Orchestration " + name + ": at least one action is required");
                continue;
            }
            for (int actionIndex = 0; actionIndex < actionSequence.size(); actionIndex++) {
                OrchestrationActionAst action = actionSequence.get(actionIndex);
                if (action == null) {
                    errors.add("Orchestration " + name + ": actions[" + actionIndex + "] is null");
                    continue;
                }
                String actionLabel = actionSequence.size() == 1 && orchestration.getAction() != null
                        ? "action"
                        : "actions[" + actionIndex + "]";
                String actionType = normalize(action.getType());
                if (action.getMap().isEmpty()) {
                    errors.add("Orchestration " + name + ": " + actionLabel + " map must not be empty");
                    continue;
                }

                Set<String> allowedTargetKeys = null;
                if ("create".equals(actionType)) {
                    String conceptKey = normalize(action.getConcept());
                    if (conceptKey.isBlank()) {
                        errors.add("Orchestration " + name + ": " + actionLabel + " concept is required");
                        continue;
                    }
                    ConceptAst targetConcept = entitiesByName.get(conceptKey);
                    if (targetConcept == null) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " concept not found: " + action.getConcept());
                        continue;
                    }
                    allowedTargetKeys = targetConcept.getFields().stream()
                            .map(FieldAst::getName)
                            .map(SemanticValidator::normalize)
                            .collect(Collectors.toSet());
                } else if ("callcapability".equals(actionType)) {
                    String capabilityKey = normalize(action.getCapability());
                    if (capabilityKey.isBlank()) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " capability is required for callCapability");
                        continue;
                    }
                    CapabilityAst capability = capabilitiesByName.get(capabilityKey);
                    if (capability == null) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " capability not found: " + action.getCapability());
                        continue;
                    }
                    String operationKey = normalize(action.getOperation());
                    if (operationKey.isBlank()) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " operation is required for callCapability");
                        continue;
                    }
                    CapabilityOperationAst matchedOperation = capability.getOperations().stream()
                            .filter(operation -> operation != null && normalize(operation.getName()).equals(operationKey))
                            .findFirst()
                            .orElse(null);
                    if (matchedOperation == null) {
                        errors.add("Orchestration " + name + ": " + actionLabel + " operation '"
                                + action.getOperation() + "' not found in capability " + capability.getName());
                    } else if (matchedOperation.getInput() != null && !matchedOperation.getInput().isEmpty()) {
                        allowedTargetKeys = matchedOperation.getInput().stream()
                                .map(SemanticValidator::normalize)
                                .collect(Collectors.toSet());
                    }
                } else if ("scheduleevent".equals(actionType)) {
                    String scheduledEventKey = normalize(action.getEvent());
                    if (scheduledEventKey.isBlank()) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " event is required for scheduleEvent");
                        continue;
                    }
                    if (!eventsByName.containsKey(scheduledEventKey)) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " schedule event not found: " + action.getEvent());
                    }
                    Long delaySeconds = action.getDelaySeconds();
                    if (delaySeconds == null) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " delaySeconds is required for scheduleEvent");
                    } else if (delaySeconds < 0) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " delaySeconds must be >= 0 for scheduleEvent");
                    }
                } else {
                    errors.add("Orchestration " + name + ": unsupported " + actionLabel + " type '"
                            + action.getType() + "'. Allowed: create, callCapability, scheduleEvent");
                    continue;
                }

                for (Map.Entry<String, String> mapping : action.getMap().entrySet()) {
                    String targetField = mapping.getKey();
                    String targetFieldKey = normalize(targetField);
                    if (targetFieldKey.isBlank()) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " map target field must be non-blank");
                        continue;
                    }
                    if (allowedTargetKeys != null
                            && !allowedTargetKeys.isEmpty()
                            && !allowedTargetKeys.contains(targetFieldKey)) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " map references unknown target field " + targetField);
                    }

                    String source = mapping.getValue();
                    if (source == null || source.isBlank()) {
                        errors.add("Orchestration " + name + ": " + actionLabel + " map source for field "
                                + targetField + " must be non-blank");
                        continue;
                    }
                    String trimmed = source.trim();
                    if (trimmed.startsWith("$event.")) {
                        String path = trimmed.substring("$event.".length()).trim();
                        if (path.isBlank()) {
                            errors.add("Orchestration " + name + ": " + actionLabel + " map source for field "
                                    + targetField + " has invalid event path");
                            continue;
                        }
                        String rootField = normalize(path.split("\\.")[0]);
                        if (!eventPayloadFields.isEmpty() && !eventPayloadFields.contains(rootField)) {
                            errors.add("Orchestration " + name + ": " + actionLabel + " map source '" + source
                                    + "' references unknown event payload field " + rootField);
                        }
                    }
                }
            }
        }
    }

    private static void validateOrchestrationCondition(
            String orchestrationName,
            String rawCondition,
            Set<String> eventPayloadFields,
            List<String> errors
    ) {
        if (rawCondition == null || rawCondition.isBlank()) {
            return;
        }
        String condition = rawCondition.trim();
        if ("true".equalsIgnoreCase(condition) || "false".equalsIgnoreCase(condition)) {
            return;
        }

        int eqIndex = condition.indexOf("==");
        int neIndex = condition.indexOf("!=");
        boolean hasEq = eqIndex >= 0;
        boolean hasNe = neIndex >= 0;
        if (hasEq == hasNe) {
            errors.add("Orchestration " + orchestrationName
                    + ": condition must use exactly one comparison operator (== or !=)");
            return;
        }

        int opIndex = hasEq ? eqIndex : neIndex;
        String left = condition.substring(0, opIndex).trim();
        String right = condition.substring(opIndex + 2).trim();
        if (left.isBlank() || right.isBlank()) {
            errors.add("Orchestration " + orchestrationName
                    + ": condition has invalid comparison syntax");
            return;
        }

        boolean leftIsEventRef = validateConditionOperand(
                orchestrationName,
                left,
                eventPayloadFields,
                errors
        );
        boolean rightIsEventRef = validateConditionOperand(
                orchestrationName,
                right,
                eventPayloadFields,
                errors
        );
        if (!leftIsEventRef && !rightIsEventRef) {
            errors.add("Orchestration " + orchestrationName
                    + ": condition must reference at least one event payload field");
        }
    }

    private static boolean validateConditionOperand(
            String orchestrationName,
            String operand,
            Set<String> eventPayloadFields,
            List<String> errors
    ) {
        if (operand == null || operand.isBlank()) {
            return false;
        }
        String token = operand.trim();
        if (isConditionLiteral(token)) {
            return false;
        }
        if (token.startsWith("$event.")) {
            String path = token.substring("$event.".length()).trim();
            if (path.isBlank()) {
                errors.add("Orchestration " + orchestrationName
                        + ": condition references an invalid event payload path");
                return false;
            }
            String rootField = normalize(path.split("\\.")[0]);
            if (!eventPayloadFields.isEmpty() && !eventPayloadFields.contains(rootField)) {
                errors.add("Orchestration " + orchestrationName + ": condition references unknown event payload field "
                        + rootField);
            }
            return true;
        }

        String rootField = normalize(token.split("\\.")[0]);
        if (rootField.matches("[a-z_][a-z0-9_]*")) {
            if (!eventPayloadFields.isEmpty() && !eventPayloadFields.contains(rootField)) {
                errors.add("Orchestration " + orchestrationName + ": condition references unknown event payload field "
                        + rootField);
                return false;
            }
            return true;
        }

        errors.add("Orchestration " + orchestrationName
                + ": unsupported condition operand '" + operand + "'");
        return false;
    }

    private static boolean isConditionLiteral(String token) {
        if (token == null) {
            return false;
        }
        String trimmed = token.trim();
        if (trimmed.isBlank()) {
            return false;
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.length() >= 2;
        }
        if ("null".equalsIgnoreCase(trimmed)
                || "true".equalsIgnoreCase(trimmed)
                || "false".equalsIgnoreCase(trimmed)) {
            return true;
        }
        return trimmed.matches("-?\\d+(\\.\\d+)?");
    }

    private static void validateTechnologyNeutrality(ModelAst modelAst, List<String> errors) {
        for (ConceptAst entity : modelAst.getConcepts()) {
            validateNameAgainstForbiddenKeywords("Entity", entity.getName(), errors);
            for (FieldAst field : entity.getFields()) {
                validateNameAgainstForbiddenKeywords("Field", field.getName(), errors);
            }
        }

        for (CapabilityAst capability : modelAst.getCapabilities()) {
            validateNameAgainstForbiddenKeywords("Capability", capability.getName(), errors);
            validateNameAgainstForbiddenKeywords("Capability type", capability.getType(), errors);
            for (CapabilityOperationAst operation : capability.getOperations()) {
                validateNameAgainstForbiddenKeywords("Capability operation", operation.getName(), errors);
            }
        }

        for (EventAst event : modelAst.getEvents()) {
            validateNameAgainstForbiddenKeywords("Event", event.getName(), errors);
        }

        for (FlowAst flow : modelAst.getFlows()) {
            validateNameAgainstForbiddenKeywords("Flow", flow.getName(), errors);
            validateFlowStepTechnologyNeutrality(flow.getSteps(), errors);
        }
    }

    private static void validateFlowStepTechnologyNeutrality(List<StepAst> steps, List<String> errors) {
        for (StepAst step : steps) {
            validateNameAgainstForbiddenKeywords("Flow step", step.getName(), errors);
            validateNameAgainstForbiddenKeywords("Capability reference", step.getCapability(), errors);
            validateNameAgainstForbiddenKeywords("Event reference", step.getEvent(), errors);
            validateNameAgainstForbiddenKeywords("Await event reference", step.getAwaitEvent(), errors);

            if (!step.getThenSteps().isEmpty()) {
                validateFlowStepTechnologyNeutrality(step.getThenSteps(), errors);
            }
            if (!step.getElseSteps().isEmpty()) {
                validateFlowStepTechnologyNeutrality(step.getElseSteps(), errors);
            }
        }
    }

    private static void validateNameAgainstForbiddenKeywords(String label, String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = normalize(value);
        for (String keyword : FORBIDDEN_TECH_KEYWORDS) {
            if (normalized.contains(keyword)) {
                errors.add(label + " name must be technology-neutral; found forbidden keyword '" + keyword
                        + "' in '" + value + "'");
                return;
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void validateDslVersion(ModelAst modelAst, List<String> errors) {
        String dslVersion = modelAst.getDslVersion();
        if (dslVersion == null || dslVersion.isBlank()) {
            errors.add("Model dslVersion is required and must be " + SUPPORTED_DSL_VERSION);
            return;
        }
        if (!SUPPORTED_DSL_VERSION.equals(dslVersion.trim())) {
            errors.add("Unsupported dslVersion " + dslVersion + "; supported value is " + SUPPORTED_DSL_VERSION);
        }
    }

    private static List<String> referencedFields(InvariantAst inv) {
        if ("expression".equalsIgnoreCase(inv.getType())) {
            List<String> fromInv = inv.getFields();
            if (fromInv != null && !fromInv.isEmpty()) {
                return fromInv;
            }
            String expression = inv.getExpression();
            if (expression == null || expression.isBlank()) return List.of();

            Matcher matchesMatcher = FIELD_MATCHES_PATTERN.matcher(expression);
            if (matchesMatcher.matches()) return List.of(matchesMatcher.group(1));

            Matcher comparisonMatcher = FIELD_COMPARISON_PATTERN.matcher(expression);
            if (comparisonMatcher.matches()) return List.of(comparisonMatcher.group(1));

            Matcher uniqueByMatcher = FIELD_UNIQUE_BY_PATTERN.matcher(expression);
            if (uniqueByMatcher.matches()) return List.of(uniqueByMatcher.group(1));

            return List.of();
        }
        return inv.getFields();
    }

    private static void validateFieldValueBehavior(
            String entityName,
            FieldAst field,
            Set<String> fieldNames,
            List<String> errors
    ) {
        SchemaAst schema = field.getSchema();
        if (schema == null) {
            return;
        }

        String defaultExpression = schema.getDefaultExpression();
        String derivedExpression = schema.getDerivedExpression();
        if (!hasText(defaultExpression) && !hasText(derivedExpression)) {
            return;
        }

        String normalizedType = normalize(field.getType());
        if ("object".equals(normalizedType) || "array".equals(normalizedType)) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": defaults/derived expressions are only supported on scalar, enum, and reference fields");
        }
        if (field.isId() && (hasText(defaultExpression) || hasText(derivedExpression))) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": id fields cannot declare defaultExpression or derivedExpression");
        }
        if (schema.getDefaultValue() != null && hasText(defaultExpression)) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": default and defaultExpression are mutually exclusive");
        }
        if (hasText(derivedExpression) && (schema.getDefaultValue() != null || hasText(defaultExpression))) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": derivedExpression cannot be combined with default/defaultExpression");
        }

        validateValueBehaviorExpression(entityName, field.getName(), "defaultExpression", defaultExpression, fieldNames, errors);
        validateValueBehaviorExpression(entityName, field.getName(), "derivedExpression", derivedExpression, fieldNames, errors);
    }

    private static void validateFieldValueBehaviorGraph(
            String entityName,
            List<FieldAst> fields,
            Set<String> fieldNames,
            List<String> errors
    ) {
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        for (FieldAst field : fields) {
            if (field == null || field.getSchema() == null) {
                continue;
            }
            List<String> refs = new ArrayList<>();
            refs.addAll(extractValueBehaviorRefs(field.getSchema().getDefaultExpression(), fieldNames));
            refs.addAll(extractValueBehaviorRefs(field.getSchema().getDerivedExpression(), fieldNames));
            if (!refs.isEmpty()) {
                dependencies.put(normalize(field.getName()), List.copyOf(refs));
            }
        }

        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String fieldName : dependencies.keySet()) {
            detectValueBehaviorCycle(entityName, fieldName, dependencies, visiting, visited, errors);
        }
    }

    private static void detectValueBehaviorCycle(
            String entityName,
            String fieldName,
            Map<String, List<String>> dependencies,
            Set<String> visiting,
            Set<String> visited,
            List<String> errors
    ) {
        if (visited.contains(fieldName)) {
            return;
        }
        if (!visiting.add(fieldName)) {
            errors.add("Entity " + entityName + " field " + fieldName
                    + ": value-behavior dependency cycle detected");
            return;
        }
        for (String ref : dependencies.getOrDefault(fieldName, List.of())) {
            if (!dependencies.containsKey(ref)) {
                continue;
            }
            detectValueBehaviorCycle(entityName, ref, dependencies, visiting, visited, errors);
        }
        visiting.remove(fieldName);
        visited.add(fieldName);
    }

    private static void validateValueBehaviorExpression(
            String entityName,
            String fieldName,
            String kind,
            String expression,
            Set<String> fieldNames,
            List<String> errors
    ) {
        if (!hasText(expression)) {
            return;
        }
        ValueExpressionAnalysis analysis = analyzeValueBehaviorExpression(expression);
        if (!analysis.valid()) {
            errors.add("Entity " + entityName + " field " + fieldName + ": "
                    + kind + " is invalid: " + analysis.error());
            return;
        }
        for (String ref : analysis.references()) {
            String normalizedRef = normalize(ref);
            if (!fieldNames.contains(normalizedRef)) {
                errors.add("Entity " + entityName + " field " + fieldName + ": "
                        + kind + " references unknown field " + ref);
            } else if (normalizedRef.equals(normalize(fieldName))) {
                errors.add("Entity " + entityName + " field " + fieldName + ": "
                        + kind + " cannot reference itself");
            }
        }
    }

    private static List<String> extractValueBehaviorRefs(String expression, Set<String> fieldNames) {
        if (!hasText(expression)) {
            return List.of();
        }
        ValueExpressionAnalysis analysis = analyzeValueBehaviorExpression(expression);
        if (!analysis.valid()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (String ref : analysis.references()) {
            if (fieldNames.contains(normalize(ref))) {
                refs.add(normalize(ref));
            }
        }
        return refs;
    }

    private static ValueExpressionAnalysis analyzeValueBehaviorExpression(String expression) {
        String trimmed = expression == null ? "" : expression.trim();
        if (trimmed.isBlank()) {
            return new ValueExpressionAnalysis(false, List.of(), "expression must be non-blank");
        }
        if (isValueLiteral(trimmed)) {
            return new ValueExpressionAnalysis(true, List.of(), null);
        }
        if (VALUE_BEHAVIOR_IDENTIFIER_PATTERN.matcher(trimmed).matches()) {
            return new ValueExpressionAnalysis(true, List.of(trimmed), null);
        }
        int openParen = trimmed.indexOf('(');
        if (openParen <= 0 || !trimmed.endsWith(")") || !isBalancedValueExpression(trimmed)) {
            return new ValueExpressionAnalysis(false, List.of(), "unsupported syntax: " + trimmed);
        }

        String functionName = trimmed.substring(0, openParen).trim();
        if (!VALUE_BEHAVIOR_FUNCTIONS.contains(normalize(functionName))) {
            return new ValueExpressionAnalysis(false, List.of(), "unsupported function: " + functionName);
        }

        String argsBody = trimmed.substring(openParen + 1, trimmed.length() - 1);
        List<String> args = splitTopLevelArguments(argsBody);
        if (args == null) {
            return new ValueExpressionAnalysis(false, List.of(), "malformed function arguments");
        }
        if (("trim".equalsIgnoreCase(functionName)
                || "uppercase".equalsIgnoreCase(functionName)
                || "lowercase".equalsIgnoreCase(functionName))
                && args.size() != 1) {
            return new ValueExpressionAnalysis(false, List.of(), functionName + " requires exactly one argument");
        }
        if (("concat".equalsIgnoreCase(functionName) || "coalesce".equalsIgnoreCase(functionName)) && args.isEmpty()) {
            return new ValueExpressionAnalysis(false, List.of(), functionName + " requires at least one argument");
        }

        List<String> references = new ArrayList<>();
        for (String arg : args) {
            ValueExpressionAnalysis nested = analyzeValueBehaviorExpression(arg);
            if (!nested.valid()) {
                return nested;
            }
            references.addAll(nested.references());
        }
        return new ValueExpressionAnalysis(true, List.copyOf(references), null);
    }

    private static boolean isValueLiteral(String expression) {
        String trimmed = expression == null ? "" : expression.trim();
        if (trimmed.isBlank()) {
            return false;
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.length() >= 2;
        }
        if ("null".equalsIgnoreCase(trimmed)
                || "true".equalsIgnoreCase(trimmed)
                || "false".equalsIgnoreCase(trimmed)) {
            return true;
        }
        return trimmed.matches("-?\\d+(\\.\\d+)?");
    }

    private static boolean isBalancedValueExpression(String expression) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (current == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (inSingle || inDouble) {
                continue;
            }
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0 && !inSingle && !inDouble;
    }

    private static List<String> splitTopLevelArguments(String argsBody) {
        List<String> args = new ArrayList<>();
        if (argsBody == null) {
            return args;
        }
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int index = 0; index < argsBody.length(); index++) {
            char currentChar = argsBody.charAt(index);
            if (currentChar == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(currentChar);
                continue;
            }
            if (currentChar == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(currentChar);
                continue;
            }
            if (!inSingle && !inDouble) {
                if (currentChar == '(') {
                    depth++;
                } else if (currentChar == ')') {
                    depth--;
                    if (depth < 0) {
                        return null;
                    }
                } else if (currentChar == ',' && depth == 0) {
                    String candidate = current.toString().trim();
                    if (candidate.isEmpty()) {
                        return null;
                    }
                    args.add(candidate);
                    current.setLength(0);
                    continue;
                }
            }
            current.append(currentChar);
        }
        if (depth != 0 || inSingle || inDouble) {
            return null;
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            args.add(tail);
        }
        return args;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean supportsExpressionFormat(String expression) {
        return expression != null && !expression.isBlank();
    }

    private static Set<String> extractTemplateFieldRefs(String template) {
        if (!hasText(template)) {
            return Set.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        Matcher matcher = REFERENCE_TEMPLATE_FIELD_PATTERN.matcher(template);
        while (matcher.find()) {
            refs.add(matcher.group(1));
        }
        return refs;
    }

    private record EffectiveEntity(List<FieldAst> fields, List<InvariantAst> invariants) {
        private EffectiveEntity {
            fields = fields == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(fields));
            invariants = invariants == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(invariants));
        }
    }

    private record ValueExpressionAnalysis(boolean valid, List<String> references, String error) {
        private ValueExpressionAnalysis {
            references = references == null ? List.of() : List.copyOf(references);
        }
    }

    private static InteractionExpressionAnalysis analyzeInteractionExpression(String expression) {
        List<InteractionToken> tokens = tokenizeInteractionExpression(expression);
        if (tokens == null || tokens.isEmpty()) {
            return new InteractionExpressionAnalysis(false, List.of(), "expression must be non-blank");
        }
        InteractionExpressionParser parser = new InteractionExpressionParser(tokens);
        return parser.parse();
    }

    private static List<InteractionToken> tokenizeInteractionExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        List<InteractionToken> tokens = new ArrayList<>();
        int index = 0;
        while (index < expression.length()) {
            char current = expression.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '(') {
                tokens.add(new InteractionToken(InteractionTokenType.LPAREN, "("));
                index++;
                continue;
            }
            if (current == ')') {
                tokens.add(new InteractionToken(InteractionTokenType.RPAREN, ")"));
                index++;
                continue;
            }
            if (current == '&' && index + 1 < expression.length() && expression.charAt(index + 1) == '&') {
                tokens.add(new InteractionToken(InteractionTokenType.AND, "&&"));
                index += 2;
                continue;
            }
            if (current == '|' && index + 1 < expression.length() && expression.charAt(index + 1) == '|') {
                tokens.add(new InteractionToken(InteractionTokenType.OR, "||"));
                index += 2;
                continue;
            }
            if (current == '!' && index + 1 < expression.length() && expression.charAt(index + 1) == '=') {
                tokens.add(new InteractionToken(InteractionTokenType.NE, "!="));
                index += 2;
                continue;
            }
            if (current == '!') {
                tokens.add(new InteractionToken(InteractionTokenType.NOT, "!"));
                index++;
                continue;
            }
            if (current == '=' && index + 1 < expression.length() && expression.charAt(index + 1) == '=') {
                tokens.add(new InteractionToken(InteractionTokenType.EQ, "=="));
                index += 2;
                continue;
            }
            if (current == '>' && index + 1 < expression.length() && expression.charAt(index + 1) == '=') {
                tokens.add(new InteractionToken(InteractionTokenType.GE, ">="));
                index += 2;
                continue;
            }
            if (current == '<' && index + 1 < expression.length() && expression.charAt(index + 1) == '=') {
                tokens.add(new InteractionToken(InteractionTokenType.LE, "<="));
                index += 2;
                continue;
            }
            if (current == '>') {
                tokens.add(new InteractionToken(InteractionTokenType.GT, ">"));
                index++;
                continue;
            }
            if (current == '<') {
                tokens.add(new InteractionToken(InteractionTokenType.LT, "<"));
                index++;
                continue;
            }
            if (current == '\'' || current == '"') {
                int end = readQuotedLiteral(expression, index);
                if (end < 0) {
                    return null;
                }
                tokens.add(new InteractionToken(
                        InteractionTokenType.STRING,
                        expression.substring(index, end + 1)
                ));
                index = end + 1;
                continue;
            }
            if (Character.isDigit(current)) {
                int end = index + 1;
                while (end < expression.length()
                        && (Character.isDigit(expression.charAt(end)) || expression.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(new InteractionToken(InteractionTokenType.NUMBER, expression.substring(index, end)));
                index = end;
                continue;
            }
            if (Character.isLetter(current) || current == '_') {
                int end = index + 1;
                while (end < expression.length()
                        && (Character.isLetterOrDigit(expression.charAt(end)) || expression.charAt(end) == '_')) {
                    end++;
                }
                String token = expression.substring(index, end);
                String normalized = normalize(token);
                if (INTERACTION_BOOLEAN_LITERALS.contains(normalized)) {
                    tokens.add(new InteractionToken(InteractionTokenType.LITERAL, token));
                } else {
                    tokens.add(new InteractionToken(InteractionTokenType.IDENT, token));
                }
                index = end;
                continue;
            }
            return null;
        }
        return tokens;
    }

    private static int readQuotedLiteral(String expression, int start) {
        char quote = expression.charAt(start);
        for (int index = start + 1; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == quote && expression.charAt(index - 1) != '\\') {
                return index;
            }
        }
        return -1;
    }

    private record InteractionExpressionAnalysis(boolean valid, List<String> references, String error) {
        private InteractionExpressionAnalysis {
            references = references == null ? List.of() : List.copyOf(new ArrayList<>(references));
        }
    }

    private record InteractionToken(InteractionTokenType type, String text) {
    }

    private enum InteractionTokenType {
        IDENT,
        STRING,
        NUMBER,
        LITERAL,
        LPAREN,
        RPAREN,
        AND,
        OR,
        NOT,
        EQ,
        NE,
        GT,
        GE,
        LT,
        LE
    }

    private static final class InteractionExpressionParser {
        private final List<InteractionToken> tokens;
        private final LinkedHashSet<String> references = new LinkedHashSet<>();
        private int index;
        private String error;

        private InteractionExpressionParser(List<InteractionToken> tokens) {
            this.tokens = tokens == null ? List.of() : tokens;
        }

        private InteractionExpressionAnalysis parse() {
            if (tokens.isEmpty()) {
                return new InteractionExpressionAnalysis(false, List.of(), "expression must be non-blank");
            }
            parseOrExpression();
            if (error == null && index < tokens.size()) {
                error = "unexpected token " + tokens.get(index).text();
            }
            return new InteractionExpressionAnalysis(error == null, List.copyOf(references), error);
        }

        private void parseOrExpression() {
            parseAndExpression();
            while (error == null && match(InteractionTokenType.OR)) {
                parseAndExpression();
            }
        }

        private void parseAndExpression() {
            parseUnaryExpression();
            while (error == null && match(InteractionTokenType.AND)) {
                parseUnaryExpression();
            }
        }

        private void parseUnaryExpression() {
            if (match(InteractionTokenType.NOT)) {
                parseUnaryExpression();
                return;
            }
            parseComparisonExpression();
        }

        private void parseComparisonExpression() {
            parsePrimaryExpression();
            if (error != null) {
                return;
            }
            if (match(InteractionTokenType.EQ)
                    || match(InteractionTokenType.NE)
                    || match(InteractionTokenType.GT)
                    || match(InteractionTokenType.GE)
                    || match(InteractionTokenType.LT)
                    || match(InteractionTokenType.LE)) {
                parsePrimaryExpression();
            }
        }

        private void parsePrimaryExpression() {
            if (match(InteractionTokenType.LPAREN)) {
                parseOrExpression();
                if (!match(InteractionTokenType.RPAREN) && error == null) {
                    error = "missing closing parenthesis";
                }
                return;
            }
            InteractionToken token = current();
            if (token == null) {
                error = "unexpected end of expression";
                return;
            }
            if (token.type() == InteractionTokenType.IDENT) {
                references.add(token.text());
                index++;
                return;
            }
            if (token.type() == InteractionTokenType.STRING
                    || token.type() == InteractionTokenType.NUMBER
                    || token.type() == InteractionTokenType.LITERAL) {
                index++;
                return;
            }
            error = "unexpected token " + token.text();
        }

        private boolean match(InteractionTokenType type) {
            InteractionToken token = current();
            if (token == null || token.type() != type) {
                return false;
            }
            index++;
            return true;
        }

        private InteractionToken current() {
            if (index >= tokens.size()) {
                return null;
            }
            return tokens.get(index);
        }
    }
}
