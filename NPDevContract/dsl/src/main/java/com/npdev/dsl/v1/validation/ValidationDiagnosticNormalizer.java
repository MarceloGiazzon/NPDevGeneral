package com.npdev.dsl.v1.validation;

import com.networknt.schema.ValidationMessage;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ValidationDiagnosticNormalizer {

    private static final String SEMANTIC_SOURCE_MODULE = "dsl:semantic-validator";
    private static final String STRUCTURAL_SOURCE_MODULE = "dsl:json-model-schema-validator";
    private static final Pattern CONCEPT_FIELD_PATTERN =
            Pattern.compile("^Concept\\s+([^\\s:]+)\\s+field\\s+([^\\s:]+)\\s*:\\s*(.+)$");
    private static final Pattern CONCEPT_INVARIANT_PATTERN =
            Pattern.compile("^Concept\\s+([^\\s:]+)\\s+invariant\\s+([^:]+)\\s*:\\s*(.+)$");
    private static final Pattern CONCEPT_PATTERN =
            Pattern.compile("^Concept\\s+([^\\s:]+)\\s*:\\s*(.+)$");
    private static final Pattern FLOW_PATTERN =
            Pattern.compile("^Flow\\s+([^\\s:]+)\\s*:\\s*(.+)$");
    private static final Pattern CAPABILITY_PATTERN =
            Pattern.compile("^Capability\\s+([^\\s:]+)(?:\\s+operation\\s+([^\\s:]+))?\\s*:\\s*(.+)$");
    private static final Pattern EVENT_PATTERN =
            Pattern.compile("^Event\\s+([^\\s:]+)\\s*:\\s*(.+)$");
    private static final Pattern DUPLICATE_CONCEPT_PATTERN =
            Pattern.compile("^Duplicate concept name:\\s*(.+)$");
    private static final Pattern DUPLICATE_CAPABILITY_PATTERN =
            Pattern.compile("^Duplicate capability name:\\s*(.+)$");
    private static final Pattern DUPLICATE_EVENT_PATTERN =
            Pattern.compile("^Duplicate event name:\\s*(.+)$");
    private static final Pattern BINDING_UNKNOWN_PATTERN =
            Pattern.compile("^Binding references unknown capability:\\s*(.+)$");
    private static final Pattern DUPLICATE_BINDING_PATTERN =
            Pattern.compile("^Duplicate binding for capability:\\s*(.+)$");

    private ValidationDiagnosticNormalizer() {
    }

    static ValidationDiagnostic semanticDiagnostic(String message, ValidationSeverity severity) {
        String safeMessage = canonicalizeConceptTerminology(message == null ? "" : message.trim());
        if (safeMessage.isBlank()) {
            return new ValidationDiagnostic(
                    ValidationLayer.SEMANTIC,
                    severity,
                    "semantic_validation_" + severity.getExternalName(),
                    "",
                    SEMANTIC_SOURCE_MODULE,
                    null,
                    null,
                    null,
                    "semantic",
                    null,
                    null,
                    "validation.semantic"
            );
        }

        Matcher conceptFieldMatcher = CONCEPT_FIELD_PATTERN.matcher(safeMessage);
        if (conceptFieldMatcher.matches()) {
            String concept = conceptFieldMatcher.group(1);
            String field = conceptFieldMatcher.group(2);
            String detail = conceptFieldMatcher.group(3);
            String code = semanticCode(detail, "concept_field_error");
            return new ValidationDiagnostic(
                    ValidationLayer.SEMANTIC,
                    severity,
                    code,
                    safeMessage,
                    SEMANTIC_SOURCE_MODULE,
                    "concepts[" + concept + "].fields[" + field + "]",
                    concept,
                    field,
                    "concepts",
                    null,
                    semanticSuggestedFix(code, concept, field, null),
                    "validation.semantic." + code
            );
        }

        Matcher conceptInvariantMatcher = CONCEPT_INVARIANT_PATTERN.matcher(safeMessage);
        if (conceptInvariantMatcher.matches()) {
            String concept = conceptInvariantMatcher.group(1);
            String ruleName = conceptInvariantMatcher.group(2).trim();
            String detail = conceptInvariantMatcher.group(3);
            String code = semanticCode(detail, "concept_invariant_error");
            return new ValidationDiagnostic(
                    ValidationLayer.SEMANTIC,
                    severity,
                    code,
                    safeMessage,
                    SEMANTIC_SOURCE_MODULE,
                    "concepts[" + concept + "].invariants[" + ruleName + "]",
                    concept,
                    null,
                    "concepts",
                    ruleName,
                    semanticSuggestedFix(code, concept, null, ruleName),
                    "validation.semantic." + code
            );
        }

        Matcher conceptMatcher = CONCEPT_PATTERN.matcher(safeMessage);
        if (conceptMatcher.matches()) {
            String concept = conceptMatcher.group(1);
            String detail = conceptMatcher.group(2);
            String ruleName = extractRuleName(detail);
            String code = semanticCode(detail, "concept_error");
            return new ValidationDiagnostic(
                    ValidationLayer.SEMANTIC,
                    severity,
                    code,
                    safeMessage,
                    SEMANTIC_SOURCE_MODULE,
                    "concepts[" + concept + "]",
                    concept,
                    null,
                    "concepts",
                    ruleName,
                    semanticSuggestedFix(code, concept, null, ruleName),
                    "validation.semantic." + code
            );
        }

        Matcher flowMatcher = FLOW_PATTERN.matcher(safeMessage);
        if (flowMatcher.matches()) {
            String flowName = flowMatcher.group(1);
            String detail = flowMatcher.group(2);
            String code = semanticCode(detail, "flow_error");
            return new ValidationDiagnostic(
                    ValidationLayer.SEMANTIC,
                    severity,
                    code,
                    safeMessage,
                    SEMANTIC_SOURCE_MODULE,
                    "flows[" + flowName + "]",
                    null,
                    null,
                    "flows",
                    flowName,
                    semanticSuggestedFix(code, null, null, flowName),
                    "validation.semantic." + code
            );
        }

        Matcher capabilityMatcher = CAPABILITY_PATTERN.matcher(safeMessage);
        if (capabilityMatcher.matches()) {
            String capability = capabilityMatcher.group(1);
            String operation = capabilityMatcher.group(2);
            String detail = capabilityMatcher.group(3);
            String code = semanticCode(detail, "capability_error");
            String path = operation == null
                    ? "capabilities[" + capability + "]"
                    : "capabilities[" + capability + "].operations[" + operation + "]";
            return new ValidationDiagnostic(
                    ValidationLayer.SEMANTIC,
                    severity,
                    code,
                    safeMessage,
                    SEMANTIC_SOURCE_MODULE,
                    path,
                    null,
                    operation,
                    "capabilities",
                    operation == null ? capability : operation,
                    semanticSuggestedFix(code, null, operation, capability),
                    "validation.semantic." + code
            );
        }

        Matcher eventMatcher = EVENT_PATTERN.matcher(safeMessage);
        if (eventMatcher.matches()) {
            String eventName = eventMatcher.group(1);
            String detail = eventMatcher.group(2);
            String code = semanticCode(detail, "event_error");
            return new ValidationDiagnostic(
                    ValidationLayer.SEMANTIC,
                    severity,
                    code,
                    safeMessage,
                    SEMANTIC_SOURCE_MODULE,
                    "events[" + eventName + "]",
                    null,
                    null,
                    "events",
                    eventName,
                    semanticSuggestedFix(code, null, null, eventName),
                    "validation.semantic." + code
            );
        }

        Matcher duplicateConceptMatcher = DUPLICATE_CONCEPT_PATTERN.matcher(safeMessage);
        if (duplicateConceptMatcher.matches()) {
            String concept = duplicateConceptMatcher.group(1).trim();
            return new ValidationDiagnostic(
                    ValidationLayer.SEMANTIC,
                    severity,
                    "duplicate_concept_name",
                    safeMessage,
                    SEMANTIC_SOURCE_MODULE,
                    "concepts[" + concept + "]",
                    concept,
                    null,
                    "concepts",
                    null,
                    "Rename one of the duplicate concepts so each concept name is unique.",
                    "validation.semantic.duplicate_concept_name"
            );
        }

        Matcher duplicateCapabilityMatcher = DUPLICATE_CAPABILITY_PATTERN.matcher(safeMessage);
        if (duplicateCapabilityMatcher.matches()) {
            String capability = duplicateCapabilityMatcher.group(1).trim();
            return new ValidationDiagnostic(
                    ValidationLayer.SEMANTIC,
                    severity,
                    "duplicate_capability_name",
                    safeMessage,
                    SEMANTIC_SOURCE_MODULE,
                    "capabilities[" + capability + "]",
                    null,
                    null,
                    "capabilities",
                    capability,
                    "Rename or merge the duplicate capability declaration.",
                    "validation.semantic.duplicate_capability_name"
            );
        }

        Matcher duplicateEventMatcher = DUPLICATE_EVENT_PATTERN.matcher(safeMessage);
        if (duplicateEventMatcher.matches()) {
            String event = duplicateEventMatcher.group(1).trim();
            return new ValidationDiagnostic(
                    ValidationLayer.SEMANTIC,
                    severity,
                    "duplicate_event_name",
                    safeMessage,
                    SEMANTIC_SOURCE_MODULE,
                    "events[" + event + "]",
                    null,
                    null,
                    "events",
                    event,
                    "Rename one of the duplicate events so event names stay unique.",
                    "validation.semantic.duplicate_event_name"
            );
        }

        Matcher bindingUnknownMatcher = BINDING_UNKNOWN_PATTERN.matcher(safeMessage);
        if (bindingUnknownMatcher.matches()) {
            String capability = bindingUnknownMatcher.group(1).trim();
            return new ValidationDiagnostic(
                    ValidationLayer.SEMANTIC,
                    severity,
                    "unknown_binding_capability",
                    safeMessage,
                    SEMANTIC_SOURCE_MODULE,
                    "bindings[capability=" + capability + "]",
                    null,
                    capability,
                    "bindings",
                    capability,
                    "Declare the capability before binding it, or fix the binding capability name.",
                    "validation.semantic.unknown_binding_capability"
            );
        }

        Matcher duplicateBindingMatcher = DUPLICATE_BINDING_PATTERN.matcher(safeMessage);
        if (duplicateBindingMatcher.matches()) {
            String capability = duplicateBindingMatcher.group(1).trim();
            return new ValidationDiagnostic(
                    ValidationLayer.SEMANTIC,
                    severity,
                    "duplicate_binding_capability",
                    safeMessage,
                    SEMANTIC_SOURCE_MODULE,
                    "bindings[capability=" + capability + "]",
                    null,
                    capability,
                    "bindings",
                    capability,
                    "Keep only one binding per capability in the model.",
                    "validation.semantic.duplicate_binding_capability"
            );
        }

        return new ValidationDiagnostic(
                ValidationLayer.SEMANTIC,
                severity,
                "semantic_validation_" + severity.getExternalName(),
                safeMessage,
                SEMANTIC_SOURCE_MODULE,
                null,
                null,
                null,
                "semantic",
                null,
                "Review the semantic validation message and align the model with the DSL rules.",
                "validation.semantic"
        );
    }

    static ValidationDiagnostic structuralDiagnostic(ValidationMessage message, String sourceLabel) {
        String instancePath = normalizeInstancePath(message.getInstanceLocation() == null ? "$" : message.getInstanceLocation().toString());
        String section = inferStructuralSection(instancePath);
        String concept = inferNamedNode(message, "name");
        String field = inferFieldName(message, instancePath);
        String codeBase = normalizeIdentifier(message.getType());
        if (codeBase.isBlank()) {
            codeBase = normalizeIdentifier(message.getCode());
        }
        if (codeBase.isBlank()) {
            codeBase = "json_schema_violation";
        } else if (!codeBase.startsWith("json_schema_")) {
            codeBase = "json_schema_" + codeBase;
        }
        String helpKey = "validation.structural." + codeBase;
        String suggestedFix = message.getProperty() == null || message.getProperty().isBlank()
                ? "Update the model so it satisfies the canonical schema at " + instancePath + "."
                : "Adjust the '" + message.getProperty() + "' property so it matches the canonical schema.";

        return new ValidationDiagnostic(
                ValidationLayer.STRUCTURAL,
                ValidationSeverity.ERROR,
                codeBase,
                message.getMessage(),
                STRUCTURAL_SOURCE_MODULE,
                instancePath,
                concept,
                field,
                section,
                null,
                suggestedFix,
                helpKey
        );
    }

    private static String inferNamedNode(ValidationMessage message, String propertyName) {
        if (message.getInstanceNode() == null || !message.getInstanceNode().isObject()) {
            return null;
        }
        if (!message.getInstanceNode().has(propertyName)) {
            return null;
        }
        String value = message.getInstanceNode().get(propertyName).asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeInstancePath(String value) {
        if (value == null || value.isBlank() || "$".equals(value)) {
            return "$";
        }
        if (value.startsWith("$")) {
            return value;
        }
        if (!value.startsWith("/")) {
            return "$." + value;
        }

        StringBuilder builder = new StringBuilder("$");
        for (String segment : value.split("/")) {
            if (segment.isBlank()) {
                continue;
            }
            String decoded = segment.replace("~1", "/").replace("~0", "~");
            if (decoded.matches("\\d+")) {
                builder.append('[').append(decoded).append(']');
            } else if (decoded.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                builder.append('.').append(decoded);
            } else {
                builder.append("['").append(decoded.replace("'", "\\'")).append("']");
            }
        }
        return builder.toString();
    }

    private static String inferFieldName(ValidationMessage message, String instancePath) {
        String namedNode = inferNamedNode(message, "name");
        if ("fields".equals(inferStructuralSection(instancePath))) {
            return namedNode;
        }
        return null;
    }

    private static String inferStructuralSection(String instancePath) {
        String safePath = instancePath == null ? "$" : instancePath;
        String lower = safePath.toLowerCase(Locale.ROOT);
        if (lower.contains("/entities") || lower.contains("/concepts")) {
            return "concepts";
        }
        if (lower.contains("/fields")) {
            return "fields";
        }
        if (lower.contains("/flows")) {
            return "flows";
        }
        if (lower.contains("/capabilities")) {
            return "capabilities";
        }
        if (lower.contains("/bindings")) {
            return "bindings";
        }
        if (lower.contains("/events")) {
            return "events";
        }
        if (lower.contains("/orchestrationrules")) {
            return "orchestrationRules";
        }
        return "root";
    }

    private static String semanticCode(String detail, String fallback) {
        String normalized = normalizeIdentifier(detail);
        if (normalized.isBlank()) {
            return fallback;
        }
        if (normalized.contains("unknown_type")) {
            return "unknown_field_type";
        }
        if (normalized.contains("reference_target_not_found")) {
            return "unknown_reference_target";
        }
        if (normalized.contains("must_have_exactly_1_id_field")) {
            return "invalid_id_field_count";
        }
        if (normalized.contains("duplicate_local_field_name")
                || normalized.contains("duplicate_field_name_in_inheritance")) {
            return "duplicate_field_name";
        }
        if (normalized.contains("duplicate_invariant_name")) {
            return "duplicate_invariant_name";
        }
        if (normalized.contains("enum_field_must_declare_enumvalues")) {
            return "missing_enum_values";
        }
        if (normalized.contains("duplicate_enum_value")) {
            return "duplicate_enum_value";
        }
        if (normalized.contains("reference_field_must_declare_ref")) {
            return "missing_reference_target";
        }
        if (normalized.contains("unsupported_expression_format")) {
            return "unsupported_expression_format";
        }
        if (normalized.contains("must_declare_fields")) {
            return "missing_required_fields";
        }
        if (normalized.contains("technology_neutral")) {
            return "technology_specific_term_not_allowed";
        }
        if (normalized.contains("duplicate_operation_name")) {
            return "duplicate_operation_name";
        }
        return fallback;
    }

    private static String semanticSuggestedFix(String code, String concept, String field, String ruleName) {
        return switch (code) {
            case "unknown_field_type" ->
                    "Use a supported DSL field type" + suffix(field) + " or add the type in a future DSL release.";
            case "unknown_reference_target" ->
                    "Point the reference at an existing concept" + suffix(field) + " or fix the target concept name.";
            case "invalid_id_field_count" ->
                    "Keep exactly one id field on concept" + suffix(concept) + ".";
            case "duplicate_field_name" ->
                    "Rename the duplicate field" + suffix(field) + " so it is unique within the effective concept shape.";
            case "duplicate_invariant_name" ->
                    "Rename invariant" + suffix(ruleName) + " so invariant names remain unique inside the concept.";
            case "missing_enum_values" ->
                    "Declare enumValues for the enum field" + suffix(field) + ".";
            case "duplicate_enum_value" ->
                    "Remove or rename duplicate enum values on field" + suffix(field) + ".";
            case "missing_reference_target" ->
                    "Add ref/reference for field" + suffix(field) + " or change the field type.";
            case "unsupported_expression_format" ->
                    "Rewrite the expression into the subset currently supported by the semantic validator.";
            case "missing_required_fields" ->
                    "Declare the required fields or rule inputs expected by the validator.";
            case "technology_specific_term_not_allowed" ->
                    "Replace implementation-specific naming with a technology-neutral semantic name.";
            case "duplicate_operation_name" ->
                    "Rename or merge the duplicate capability operation.";
            default ->
                    "Update the model so this semantic rule is satisfied.";
        };
    }

    private static String extractRuleName(String detail) {
        String lower = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (lower.contains("duplicate invariant name")) {
            int index = detail.lastIndexOf(' ');
            return index >= 0 ? detail.substring(index + 1).trim() : detail.trim();
        }
        return null;
    }

    private static String canonicalizeConceptTerminology(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replace("Entity ", "Concept ")
                .replace(" entity ", " concept ");
    }

    private static String suffix(String value) {
        return value == null || value.isBlank() ? "" : " '" + value + "'";
    }

    private static String normalizeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compact = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_");
        return compact.startsWith("_") ? compact.substring(1) : compact;
    }
}
