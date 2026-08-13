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
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
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
import static com.npdev.dsl.v1.validation.ExpressionValidation.FORBIDDEN_TECH_KEYWORDS;
import static com.npdev.dsl.v1.validation.PackValidation.validateCapabilityPolicy;
import static com.npdev.dsl.v1.validation.FieldValueValidation.validateObjectFieldSchema;
import static com.npdev.dsl.v1.validation.FieldValueValidation.validateArrayFieldSchema;
import static com.npdev.dsl.v1.validation.FieldValueValidation.validateDecimalFieldSchema;
import static com.npdev.dsl.v1.validation.FieldValueValidation.validateFieldValueBehavior;
import static com.npdev.dsl.v1.validation.FieldValueValidation.validateFieldValueBehaviorGraph;
import static com.npdev.dsl.v1.validation.FieldValueValidation.areCompatibleTypes;
import static com.npdev.dsl.v1.validation.ReferenceValidation.validateScalarReferenceLookupMetadata;
import static com.npdev.dsl.v1.validation.ReferenceValidation.validateReferenceSemantics;
import static com.npdev.dsl.v1.validation.ReferenceValidation.validateBondTruthEdge;
import static com.npdev.dsl.v1.validation.ReferenceValidation.validateReferenceFieldHint;
import static com.npdev.dsl.v1.validation.LifecycleValidation.validateLifecycle;

/**
 * Semantic validation for concept/field/entity structure: field type + domain-type compatibility,
 * inheritance, lifecycle state machines (delegated to {@link LifecycleValidation}), access rules,
 * invariants, reference/bond semantics (delegated to {@link ReferenceValidation}), and field
 * value-behavior expressions (delegated to {@link FieldValueValidation}).
 *
 * <p>Split out of {@code SemanticValidator} (T1.15); see that class for the orchestration entry
 * point. This class also owns the small shared infrastructure ({@link EffectiveEntity},
 * {@link #resolveEffective}, {@link #indexEntities}, {@link #KNOWN_TYPES},
 * {@link #BUILTIN_CAPABILITIES}, {@link #toFieldShape}) used by more than one section.
 */
final class ConceptValidation {

    private ConceptValidation() {
    }

    static final Set<String> KNOWN_TYPES = Set.of(
            "string",
            "uuid",
            "int",
            "integer",
            "long",
            "decimal",
            "boolean",
            "date",
            "datetime",
            "enum",
            "reference",
            "object",
            "array",
            "file"
    );

    static final Set<String> BUILTIN_CAPABILITIES =
            Set.of("eventbus", "persistence", "invariantengine",
                    "persistencecapability", "messagingcapability", "emailcapability",
                    "fiscalcapability", "signaturecapability");

    private static final Pattern FIELD_MATCHES_PATTERN =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\.matches\\s*\\(.*\\)\\s*$");
    private static final Pattern FIELD_COMPARISON_PATTERN =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(==|!=|>=|<=|>|<)\\s*.+$");
    private static final Pattern FIELD_UNIQUE_BY_PATTERN =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\.uniqueBy\\s*\\(\\s*[A-Za-z_][A-Za-z0-9_]*\\s*\\)\\s*$");

    static void validateConceptsAndFields(
            ModelAst effectiveModel,
            Map<String, ConceptAst> entitiesByLower,
            Map<String, DomainTypeAst> domainTypesByLower,
            Map<String, EffectiveEntity> effectiveCache,
            List<String> errors,
            List<String> semanticWarnings
    ) {
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

            Map<String, String> renamedFromSeen = new HashMap<>();
            for (FieldAst f : effective.fields()) {
                validateRenamedFrom(e, f, fieldNames, renamedFromSeen, errors, semanticWarnings);
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
                } else if ("decimal".equals(normalizedType)) {
                    validateDecimalFieldSchema(e.getName(), f, errors);
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

                // Fields referenced must exist. Dotted paths (cliente.tipo) check their
                // root segment only — nested-field existence isn't modeled here.
                for (String fn : referencedFields(inv)) {
                    String rootSegment = fn.contains(".") ? fn.substring(0, fn.indexOf('.')) : fn;
                    if (!fieldNames.contains(normalize(rootSegment))) {
                        errors.add("Entity " + e.getName() + " invariant " + inv.getType()
                                + ": references unknown field " + fn);
                    }
                }

                // LIFT-UNIQUE-P1: unique supports one or more fields (compound unique).
                if ("unique".equalsIgnoreCase(inv.getType())) {
                    if (inv.getFields() == null || inv.getFields().isEmpty()) {
                        errors.add("Entity " + e.getName() + " invariant unique: must declare fields");
                    } else {
                        Set<String> seen = new HashSet<>();
                        for (String fn : inv.getFields()) {
                            if (fn != null && !seen.add(normalize(fn))) {
                                errors.add("Entity " + e.getName()
                                        + " invariant unique: duplicate field '" + fn + "' in " + inv.getFields());
                            }
                        }
                    }
                } else if ("expression".equalsIgnoreCase(inv.getType())) {
                    String expression = inv.getExpression();
                    if (expression == null || expression.isBlank()) {
                        errors.add("Entity " + e.getName() + " invariant expression: expression must be non-blank");
                    } else if (!supportsExpressionFormat(expression)) {
                        errors.add("Entity " + e.getName()
                                + " invariant expression: unsupported expression format: " + expression);
                    } else {
                        validateInvariantExpressionShape(e.getName(), expression, errors);
                    }
                }
            }

            validateAccessRules(e.getName(), e.getAccess(), fieldNames, errors);
            validateLifecycle(e, effective, effectiveModel.getAutoPanels(), effectiveModel.getAggregates(), errors);
        }
    }

    /**
     * REG-98 (MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 0.1, fix shape (a)): two differently-named
     * concepts can derive the SAME physical table, because {@link SqlIdentifierSupport#toSnake}
     * sanitizes by REPLACEMENT -- every non-alphanumeric character becomes {@code '_'}, then runs
     * of {@code '_'} collapse -- so {@code OrderLine}, {@code Order Line} and {@code Order-Line} all
     * derive {@code order_lines}. Concept NAMES are already checked for duplicates
     * ({@link #indexEntities}); the physical names they compile to were not, so two concepts could
     * silently share one table -- each seeing the other's rows as its own, with no error anywhere.
     *
     * <p>Deliberately mirrors {@link SqlIdentifierSupport#toSnakePlural} exactly -- the same call
     * {@code ModelCompiler} makes to derive a concept's real table name -- rather than reimplementing
     * the sanitization, so this check can never drift from what actually gets compiled (one grammar,
     * not two dialects).
     *
     * <p><b>S8 Wave 4 fix (found while building the physicallyIsolate collision cases, ADR-0011
     * D4): this check previously hashed {@code concept.getName()} DIRECTLY</b> -- for a
     * context-qualified name ({@code contextName::Concept}) that is a DIFFERENT string than what
     * {@code ModelCompiler#tableNameSource} actually compiles to (which strips a non-isolating
     * context's qualifier before pluralizing), so two DIFFERENT contexts declaring the SAME bare
     * concept name compiled to the SAME real table (D4 v1's whole scenario) went undetected -- the
     * exact "two concepts silently share one table" hazard this check's own javadoc, above, says it
     * exists to catch. Now runs every concept name through {@link
     * SqlIdentifierSupport#physicalTableNameSource} first, the SAME resolution {@code
     * ModelCompiler#physicalTableNameSource} performs, so this check and the real compiled name can
     * never drift apart again. This is also I3's own collision matrix (Wave 4, {@code
     * S8_DEFERRED_FIVE_PLAN.md}): two non-isolating contexts (or one isolating, one not) sharing a
     * concept name still collide here exactly as before; two BOTH-isolating contexts no longer do,
     * since their compiled table names now genuinely differ ({@code context_concepts}).
     *
     * <p>PK-2: two pack-derived concepts sharing a bare name now collide (or not) based on their
     * PHYSICAL qualifier ({@code realPackId_v<major>}), not the importing app's chosen alias --
     * matching exactly what {@code ModelCompiler} actually compiles.
     */
    static void validateTableNameCollisions(ModelAst effectiveModel, List<String> errors) {
        Map<String, Boolean> contextPhysicallyIsolateByName = new LinkedHashMap<>();
        for (com.npdev.dsl.v1.ast.ContextAst context : effectiveModel.getContexts()) {
            contextPhysicallyIsolateByName.put(context.name(), context.physicallyIsolate());
        }
        Map<String, String> physicalQualifierByConceptName = effectiveModel.getPhysicalQualifierByConceptName();
        Map<String, String> conceptNameByTableName = new LinkedHashMap<>();
        for (ConceptAst concept : effectiveModel.getConcepts()) {
            String tableNameSource = SqlIdentifierSupport.physicalTableNameSource(
                    concept.getName(), physicalQualifierByConceptName.get(concept.getName()),
                    contextPhysicallyIsolateByName);
            String tableName = SqlIdentifierSupport.toSnakePlural(tableNameSource);
            String firstConceptName = conceptNameByTableName.putIfAbsent(tableName, concept.getName());
            if (firstConceptName != null && !normalize(firstConceptName).equals(normalize(concept.getName()))) {
                errors.add("Concepts " + firstConceptName + " and " + concept.getName()
                        + ": both derive the same physical table name \"" + tableName
                        + "\" -- rename one of them, or declare physicallyIsolate on their context(s), "
                        + "so their data is not silently merged (REG-98)");
            }
        }
    }

    /**
     * LNCH-1 P2 §2.3: the same three {@code renamedFrom} hygiene rules as
     * {@link #validateRenamedFrom}, at the CONCEPT level instead of the field level (checked once
     * per model, across ALL concepts, since concept names -- unlike field names -- aren't scoped
     * to a single containing entity):
     * <ol>
     *   <li>{@code renamedFrom} equal to the concept's own current name is almost certainly a
     *       leftover/copy-paste marker, not a real rename declaration -- warning, not an error.</li>
     *   <li>{@code renamedFrom} naming a concept that still currently exists is ambiguous: is it
     *       declaring a rename, or accidentally referencing a real concept? -- error. Because
     *       {@code entitiesByLower} indexes every concept in the model (not just the entity being
     *       checked), this single check also covers "renamedFrom must not equal ANY OTHER
     *       concept's current name" -- verified, not a separate rule to duplicate.</li>
     *   <li>Two different concepts declaring the same {@code renamedFrom} value is ambiguous: which
     *       one is the actual rename target for the live database's old table? -- error.</li>
     * </ol>
     * An unmatched {@code renamedFrom} (no current concept carries that name, and no other concept
     * also claims it) is valid and unremarkable here -- it is a silent no-op at runtime (a fresh
     * install, or a rename that already ran).
     */
    static void validateConceptRenamedFrom(
            ModelAst effectiveModel,
            Map<String, ConceptAst> entitiesByLower,
            List<String> errors,
            List<String> semanticWarnings
    ) {
        Map<String, String> renamedFromSeen = new HashMap<>();
        for (ConceptAst concept : effectiveModel.getConcepts()) {
            String renamedFrom = concept.getRenamedFrom();
            if (renamedFrom == null || renamedFrom.isBlank()) {
                continue;
            }
            String normalizedRenamedFrom = normalize(renamedFrom);
            String normalizedOwnName = normalize(concept.getName());

            if (normalizedRenamedFrom.equals(normalizedOwnName)) {
                semanticWarnings.add("Concept " + concept.getName()
                        + ": renamedFrom equals the concept's own name; this has no effect and is likely a leftover marker");
            } else if (entitiesByLower.containsKey(normalizedRenamedFrom)) {
                errors.add("Concept " + concept.getName()
                        + ": renamedFrom " + renamedFrom + " names a concept that still exists "
                        + "(ambiguous: is this a rename or a reference to a real concept?)");
            }

            String firstConceptName = renamedFromSeen.putIfAbsent(normalizedRenamedFrom, concept.getName());
            if (firstConceptName != null && !normalize(firstConceptName).equals(normalizedOwnName)) {
                errors.add("Concepts " + firstConceptName + " and " + concept.getName()
                        + ": both declare renamedFrom " + renamedFrom
                        + " (ambiguous: which one is the actual rename target?)");
            }
        }
    }

    /**
     * LNCH-1 §2.1 hygiene rules for the {@code renamedFrom} marker (checked once per field, for
     * every field regardless of type validity):
     * <ol>
     *   <li>{@code renamedFrom} equal to the field's own current name is almost certainly a
     *       leftover/copy-paste marker, not a real rename declaration -- warning, not an error.</li>
     *   <li>{@code renamedFrom} naming a field that still exists (as a CURRENT field name) in the
     *       same concept is ambiguous: is it declaring a rename, or accidentally referencing a real
     *       field? -- error.</li>
     *   <li>Two different fields in the same concept declaring the same {@code renamedFrom} value
     *       is ambiguous: which one is the actual rename target for the live database's old
     *       column? -- error.</li>
     * </ol>
     * An unmatched {@code renamedFrom} (no current field carries that name, and no other field
     * also claims it) is valid and unremarkable here -- it is a silent no-op at runtime (a fresh
     * install, or a rename that already ran).
     */
    private static void validateRenamedFrom(
            ConceptAst entity,
            FieldAst field,
            Set<String> currentFieldNames,
            Map<String, String> renamedFromSeen,
            List<String> errors,
            List<String> semanticWarnings
    ) {
        String renamedFrom = field.getRenamedFrom();
        if (renamedFrom == null || renamedFrom.isBlank()) {
            return;
        }
        String normalizedRenamedFrom = normalize(renamedFrom);
        String normalizedOwnName = normalize(field.getName());

        if (normalizedRenamedFrom.equals(normalizedOwnName)) {
            semanticWarnings.add("Entity " + entity.getName() + " field " + field.getName()
                    + ": renamedFrom equals the field's own name; this has no effect and is likely a leftover marker");
        } else if (currentFieldNames.contains(normalizedRenamedFrom)) {
            errors.add("Entity " + entity.getName() + " field " + field.getName()
                    + ": renamedFrom " + renamedFrom + " names a field that still exists in this concept "
                    + "(ambiguous: is this a rename or a reference to a real field?)");
        }

        String firstFieldName = renamedFromSeen.putIfAbsent(normalizedRenamedFrom, field.getName());
        if (firstFieldName != null && !normalize(firstFieldName).equals(normalizedOwnName)) {
            errors.add("Entity " + entity.getName() + " fields " + firstFieldName + " and " + field.getName()
                    + ": both declare renamedFrom " + renamedFrom
                    + " (ambiguous: which one is the actual rename target?)");
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

    static FieldWidgetDefaults.FieldShape toFieldShape(FieldAst field, String normalizedType) {
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

    static Map<String, ConceptAst> indexEntities(Collection<ConceptAst> entities, List<String> errors) {
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

    static void validateEntityLocalFields(ModelAst modelAst, List<String> errors) {
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


    static void validateInheritanceGraph(Map<String, ConceptAst> entitiesByLower, List<String> errors) {
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

    static EffectiveEntity resolveEffective(
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

    static void validateCapabilities(ModelAst modelAst, List<String> errors) {
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

    static void validateBindings(ModelAst modelAst, List<String> errors) {
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

    private static List<String> referencedFields(InvariantAst inv) {
        if ("expression".equalsIgnoreCase(inv.getType())) {
            List<String> fromInv = inv.getFields();
            if (fromInv != null && !fromInv.isEmpty()) {
                return fromInv;
            }
            String expression = inv.getExpression();
            if (expression == null || expression.isBlank()) return List.of();

            // LIFT-EXPR-P3: prefer the unified ComputedExpression grammar (covers parens/!/
            // arithmetic/dotted paths, so compound expressions get real field checking instead
            // of being silently skipped). Falls back to the legacy single-shape regexes for
            // CEL-specific syntax ComputedExpression doesn't parse (matches/uniqueBy/etc).
            try {
                return List.copyOf(ComputedExpression.referencedFields(expression));
            } catch (ComputedExpression.ExpressionException ignored) {
                // fall through to legacy extraction
            }

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

    /**
     * LIFT-EXPR-P3: static boolean-shape check for the ComputedExpression-parseable subset of
     * invariant expressions (comparisons/&&/||/!/parens/arithmetic). Expressions using
     * CEL-specific syntax (regex .matches(), .uniqueBy(), .all()/.exists() quantifiers,
     * conflicts()/scope.exists()) don't parse here and are left to runtime validation
     * (CelInvariantEngine), which remains their source of truth.
     */
    private static void validateInvariantExpressionShape(String entityName, String expression, List<String> errors) {
        try {
            if (!ComputedExpression.isBooleanShaped(expression)) {
                errors.add("Entity " + entityName
                        + " invariant expression: expression must evaluate to a boolean: " + expression);
            }
        } catch (ComputedExpression.ExpressionException ignored) {
            // CEL-specific syntax; no static shape check available for it yet.
        }
    }

    /**
     * LNCH-13: compile-time checks for a concept's declarative row-level access rules
     * (access: { read, write }) -- both must parse, both must be boolean-shaped, and every
     * referenced field must either be a real field on this concept or a {@code $user.*}
     * pseudo-variable (the current actor context, always considered valid here since its shape
     * is fixed by the platform, not the model).
     */
    private static void validateAccessRules(
            String entityName,
            ConceptAccessAst access,
            Set<String> fieldNames,
            List<String> errors
    ) {
        if (access == null) {
            return;
        }
        validateAccessExpression(entityName, "read", access.getRead(), fieldNames, errors);
        validateAccessExpression(entityName, "write", access.getWrite(), fieldNames, errors);
    }

    private static void validateAccessExpression(
            String entityName,
            String label,
            String expression,
            Set<String> fieldNames,
            List<String> errors
    ) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        try {
            ComputedExpression.validate(expression);
        } catch (ComputedExpression.ExpressionException syntaxError) {
            errors.add("Entity " + entityName + " access." + label
                    + ": syntax error in expression: " + expression + " (" + syntaxError.getMessage() + ")");
            return;
        }
        if (!ComputedExpression.isBooleanShaped(expression)) {
            errors.add("Entity " + entityName + " access." + label
                    + ": expression must evaluate to a boolean: " + expression);
        }
        for (String referenced : ComputedExpression.referencedFields(expression)) {
            if (referenced.startsWith("$prop.")) {
                // RC-A4 hard rule (Move 14 Phase C item C1): a securityRelevant OR ordinary property
                // is runtime-mutable through the generated admin surface (RC-A5) -- an ordinary
                // property is even mutable by a non-admin user for their own scope (settableAt +
                // PropertyResolverController#authorizeWrite). If $prop.* could appear in access.read/
                // access.write, an authorization rule would become runtime-mutable by exactly the
                // actor it is supposed to be gating -- bypassing the authoring contract's rule A9
                // (every permission-shaped delta must reach the Owner through AuthoringDiffGate).
                // Refused unconditionally, not just for securityRelevant properties: even a
                // non-security-relevant property's cascade value is not something an access
                // expression may ever depend on.
                errors.add("Entity " + entityName + " access." + label
                        + ": '" + referenced + "' is forbidden here -- $prop.* may never appear inside "
                        + "access.read/access.write (a runtime-mutable property must never affect an "
                        + "authorization decision, RC-A4's hard rule / authoring contract rule A9)");
                continue;
            }
            if (referenced.startsWith("$")) {
                continue;
            }
            String rootSegment = referenced.contains(".") ? referenced.substring(0, referenced.indexOf('.')) : referenced;
            if (!fieldNames.contains(normalize(rootSegment))) {
                errors.add("Entity " + entityName + " access." + label
                        + ": references unknown field " + referenced);
            }
        }
    }

    private static boolean supportsExpressionFormat(String expression) {
        return expression != null && !expression.isBlank();
    }

    record EffectiveEntity(List<FieldAst> fields, List<InvariantAst> invariants) {
        EffectiveEntity {
            fields = fields == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(fields));
            invariants = invariants == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(invariants));
        }
    }

}
