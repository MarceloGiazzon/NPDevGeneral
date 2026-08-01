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

import com.npdev.dsl.v1.validation.ConceptValidation.EffectiveEntity;

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
 *
 * <p>This class is the orchestration entry point only: it resolves the model, then delegates each
 * model section's checks to a dedicated collaborator and merges the resulting diagnostics. The
 * checks themselves live in (T1.15 split of what used to be one 4,244-line file):
 * {@link ConceptValidation} (+ {@link DomainTypeValidation}, {@link ReferenceValidation},
 * {@link FieldValueValidation}, {@link LifecycleValidation} sub-boundaries),
 * {@link FlowValidation} (+ {@link OrchestrationValidation}), {@link PanelValidation}
 * (+ {@link UxMetadataValidation}), {@link AggregateValidation}, {@link PackValidation},
 * {@link RoleValidation}, and {@link ExpressionValidation}. {@link #normalize} and
 * {@link #hasText} remain here as the shared string-normalization utilities every section uses.
 */
public final class SemanticValidator {

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
        ExpressionValidation.validateDslVersion(effectiveModel, errors);
        Map<String, ConceptAst> entitiesByLower = ConceptValidation.indexEntities(effectiveModel.getConcepts(), errors);
        ConceptValidation.validateTableNameCollisions(effectiveModel, errors);
        ConceptValidation.validateConceptRenamedFrom(effectiveModel, entitiesByLower, errors, semanticWarnings);

        ConceptValidation.validateCapabilities(effectiveModel, errors);
        ConceptValidation.validateBindings(effectiveModel, errors);
        PackValidation.validateExternalAiEgress(effectiveModel, errors);
        FlowValidation.validateEvents(effectiveModel, errors);
        Map<String, DomainTypeAst> domainTypesByLower = DomainTypeValidation.validateDomainTypes(effectiveModel, errors);
        ConceptValidation.validateEntityLocalFields(effectiveModel, errors);
        ConceptValidation.validateInheritanceGraph(entitiesByLower, errors);
        ExpressionValidation.validateTechnologyNeutrality(effectiveModel, errors);

        Map<String, EffectiveEntity> effectiveCache = new HashMap<>();
        ConceptValidation.validateConceptsAndFields(
                effectiveModel, entitiesByLower, domainTypesByLower, effectiveCache, errors, semanticWarnings);

        FlowValidation.validateFlows(effectiveModel, entitiesByLower, effectiveCache, allowUnboundFlowCapabilities, errors, semanticWarnings);
        OrchestrationValidation.validateOrchestrationRules(effectiveModel, errors);
        PackValidation.validateQueries(effectiveModel, entitiesByLower, errors);
        PackValidation.validateRuleProfiles(effectiveModel, entitiesByLower, errors);
        PackValidation.validateProcedures(effectiveModel, entitiesByLower, errors);
        PanelValidation.validatePanels(effectiveModel, entitiesByLower, errors);
        PanelValidation.validateGuidePages(effectiveModel, errors);
        AggregateValidation.validateAggregates(effectiveModel, entitiesByLower, errors);
        PanelValidation.validateAutoPanels(effectiveModel, entitiesByLower, errors, warnings);
        PanelValidation.validateSelectors(effectiveModel, entitiesByLower, errors);
        RoleValidation.validateRoles(effectiveModel, errors);
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
        List<ValidationDiagnostic> uxDiagnostics = UxMetadataValidation.validatePresentationMetadata(effectiveModel, entitiesByLower);
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

    /**
     * Shared string-normalization utility used by every validation section in this package
     * (package-private so {@code ConceptValidation}, {@code FlowValidation}, etc. can call it
     * directly, including via {@code SemanticValidator::normalize} method references).
     */
    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Shared non-blank-check utility used by every validation section in this package.
     */
    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
