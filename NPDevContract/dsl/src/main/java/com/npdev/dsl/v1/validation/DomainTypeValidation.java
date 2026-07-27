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
import static com.npdev.dsl.v1.validation.FieldValueValidation.areCompatibleTypes;

/**
 * Semantic validation for {@code domainTypes}: base-type compatibility, validation-schema shape,
 * examples, and normalization rules. Mirrors {@code model.schema.json}'s own {@code domainTypes}
 * section, a top-level sibling of {@code concepts} (not nested under it).
 *
 * <p>Split out of {@code SemanticValidator} (T1.15) as a sub-boundary of the Concept section
 * (see {@link ConceptValidation}), which resolves each field's domain type against this class's
 * output.
 */
final class DomainTypeValidation {

    private DomainTypeValidation() {
    }

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

    static Map<String, DomainTypeAst> validateDomainTypes(ModelAst modelAst, List<String> errors) {
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

}
