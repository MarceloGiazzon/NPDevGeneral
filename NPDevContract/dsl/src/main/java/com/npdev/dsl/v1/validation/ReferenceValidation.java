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
import static com.npdev.dsl.v1.validation.ConceptValidation.resolveEffective;

/**
 * Semantic validation for reference-field / bond semantics: scalar reference lookup metadata,
 * {@code referenceSemantics} (onDelete, via/anchor, inlineCreate), the bond truth-edge warning,
 * and the display/search/preview/template field hints a reference or bond may declare.
 *
 * <p>Split out of {@code SemanticValidator} (T1.15) as a sub-boundary of the Concept section
 * (see {@link ConceptValidation}), which calls into this class for every reference-typed field.
 */
final class ReferenceValidation {

    private ReferenceValidation() {
    }

    private static final Pattern REFERENCE_TEMPLATE_FIELD_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    static void validateScalarReferenceLookupMetadata(
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
                    + ": ref/reference metadata on non-reference fields is supported only for scalar id fields"
                    + " -- suggestedFix: change this field's type to \"reference\" if it really points at "
                    + "another concept, or drop the ref/reference metadata if it does not");
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

    static void validateReferenceSemantics(
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
                        + " with unique=true and connectable:anchor): " + referenceSemantics.getVia()
                        + " -- suggestedFix: set unique:true and connectable:\"anchor\" on field '"
                        + referenceSemantics.getVia() + "' of the target concept, or point via at that "
                        + "concept's id field instead");
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
    static void validateBondTruthEdge(
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

    static void validateReferenceFieldHint(
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

}
