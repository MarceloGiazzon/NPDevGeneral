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

import com.npdev.dsl.v1.validation.ExpressionValidation.InteractionExpressionAnalysis;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;
import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.ConceptValidation.toFieldShape;
import static com.npdev.dsl.v1.validation.ExpressionValidation.analyzeInteractionExpression;

/**
 * Semantic validation for the {@code ui} presentation metadata declared on concepts and fields:
 * labels, layout (order/column/width/displayMode), interaction expressions
 * (visibleWhen/enabledWhen/readonlyWhen/requiredWhen), and reference-picker metadata.
 *
 * <p>Split out of {@code SemanticValidator} (T1.15) as a sub-boundary of the Panel/UX section
 * (see {@link PanelValidation}) -- these checks emit {@code ValidationLayer.UX_METADATA}
 * diagnostics (warnings) rather than hard errors.
 */
final class UxMetadataValidation {

    private UxMetadataValidation() {
    }

    static List<ValidationDiagnostic> validatePresentationMetadata(
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

}
