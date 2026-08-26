package com.npdev.dsl.v1.validation;

import com.networknt.schema.ValidationMessage;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    // REG-135 (docs/ACCEPTED_BOUNDARIES.md): a small number of "unknown property" structural
    // violations are hitting a NAMED, accepted boundary rather than an ordinary typo -- selectorRef
    // is B16's own descoped mechanism (0 Java, 0 schema, never built; field.picker replaced it).
    // json_schema_additionalproperties is otherwise a GENERIC code shared by every unrecognized
    // property, so this is the one place that distinguishes "you tried the old descoped thing"
    // from "you have a typo" -- see ledger/boundaries/B16.yml's own codeLinked note.
    private static final Map<String, String> DESCOPED_PROPERTY_BOUNDARY_IDS = Map.of(
            "selectorRef", "B16"
    );

    // B1, B13, B19: boundary-prefixed diagnostic codes. The validator embeds `B1:<code>:` at the
    // start of the message string; this map extracts the boundary id and strips the prefix so
    // downstream patterns (CONCEPT_PATTERN, etc.) still match the rest of the message.
    private static final Map<String, String> BOUNDARY_PREFIX_IDS = Map.of(
            "B1:", "B1",
            "B13:", "B13",
            "B19:", "B19"
    );

    /**
     * R1.4: the ONE text that means "this diagnostic shipped bare". Every ERROR diagnostic must
     * carry an actionable fix, so this string is a failure signal, not a fallback anyone should be
     * content with -- {@code DiagnosticSuggestedFixCoverageTest} fails the build when any message
     * template in this package still lands here. It stays reachable only so a genuinely
     * unclassifiable message (a resolver exception text, say) still produces a non-null field.
     */
    static final String UNCLASSIFIED_SUGGESTED_FIX =
            "Review the semantic validation message and align the model with the DSL rules.";

    /**
     * R1.4: a validator that knows the fix better than any grammar could writes it into its own
     * message as {@code ... -- suggestedFix: <text>}, and this lifts that text into the
     * {@code suggestedFix} FIELD. {@link PropertyValidation} started this convention (two sites,
     * 2026-08) but nothing ever read the marker, so the text sat in the message while the field
     * got a generic derivation.
     *
     * <p>The marker is deliberately LEFT IN the message rather than stripped: {@code
     * SemanticValidator.validate()} returns bare {@code List<String>} and most callers (the
     * generator, the plain-text CLI path, ~30 validation tests) only ever see those strings -- for
     * them, stripping the marker would delete the fix instead of relocating it.
     */
    private static final Pattern SITE_SUGGESTED_FIX =
            Pattern.compile("--\\s*suggestedFix:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * R1.4: {@code helpKey} normally carries a documentation lookup key ({@code
     * validation.semantic.<code>}). Where a real {@code knowledge/cards/*.json} card documents THIS
     * diagnostic class, the card's id is used instead so {@code npdev_search_fix} can hand back the
     * precedent fix. Card ids are kebab-case and doc keys are dotted, so a consumer can tell them
     * apart without a flag.
     *
     * <p>Only cards that actually exist and actually describe a DSL-validation diagnostic are wired
     * -- measured 2026-08-18: of the 16 cards in {@code knowledge/cards/}, 6 are {@code error-fix}
     * and only these 3 have a signature a {@code SemanticValidator} message can match. Pointing
     * {@code helpKey} at an id with no card behind it would be worse than leaving the doc key.
     */
    private static final Map<String, String> KNOWLEDGE_CARD_SIGNATURES = Map.of(
            "panel:concept not found", "fix-panel-unknown-entity",
            "lifecycle:is not declared in lifecycle.states", "fix-workflow-invalid-transition",
            "ui.widget:is incompatible with type", "widget-datatype-compatibility"
    );

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

        // Extract boundary prefix (B1:, B13:) if present, stripping it so downstream patterns match
        String boundaryId = null;
        for (Map.Entry<String, String> entry : BOUNDARY_PREFIX_IDS.entrySet()) {
            if (safeMessage.startsWith(entry.getKey())) {
                boundaryId = entry.getValue();
                safeMessage = safeMessage.substring(entry.getKey().length());
                break;
            }
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
                    suggestedFixFor(safeMessage, code, concept, field, null),
                    helpKeyFor(safeMessage, "validation.semantic." + code),
                    boundaryId
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
                    suggestedFixFor(safeMessage, code, concept, null, ruleName),
                    helpKeyFor(safeMessage, "validation.semantic." + code),
                    boundaryId
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
                    suggestedFixFor(safeMessage, code, concept, null, ruleName),
                    helpKeyFor(safeMessage, "validation.semantic." + code),
                    boundaryId
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
                    suggestedFixFor(safeMessage, code, null, null, flowName),
                    helpKeyFor(safeMessage, "validation.semantic." + code),
                    boundaryId
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
                    suggestedFixFor(safeMessage, code, null, operation, capability),
                    helpKeyFor(safeMessage, "validation.semantic." + code),
                    boundaryId
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
                    suggestedFixFor(safeMessage, code, null, null, eventName),
                    helpKeyFor(safeMessage, "validation.semantic." + code),
                    boundaryId
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
                    "validation.semantic.duplicate_concept_name",
                    boundaryId
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
                    "validation.semantic.duplicate_capability_name",
                    boundaryId
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
                    "validation.semantic.duplicate_event_name",
                    boundaryId
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
                    "validation.semantic.unknown_binding_capability",
                    boundaryId
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
                    "validation.semantic.duplicate_binding_capability",
                    boundaryId
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
                // R1.4: this branch takes every message shape the patterns above do not recognize
                // -- the panel/pack/aggregate/orchestration families, which are 218 of this
                // package's 362 error sites. It used to hand all of them one generic sentence.
                suggestedFixFor(safeMessage, "semantic_validation_" + severity.getExternalName(),
                        null, null, null),
                helpKeyFor(safeMessage, "validation.semantic"),
                boundaryId
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
        String boundaryId = message.getProperty() == null
                ? null
                : DESCOPED_PROPERTY_BOUNDARY_IDS.get(message.getProperty());

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
                helpKey,
                boundaryId
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

    /**
     * R1.4 (roadmap item "suggestedFix + helpKey on every ERROR diagnostic"): the single entry
     * point every diagnostic branch uses, in strict precedence order:
     *
     * <ol>
     *   <li>an explicit {@code -- suggestedFix: ...} marker the emitting validator wrote (it knows
     *       the fix better than any grammar can infer it);</li>
     *   <li>the per-code table below, for the classified message families;</li>
     *   <li>{@link #deriveSuggestedFix}, which reads the message's own grammar and quotes the
     *       offending token back to the author.</li>
     * </ol>
     *
     * <p>Why a grammar and not 362 hand-written strings: the validators in this package emit
     * messages in a small, extremely regular set of shapes ({@code <what> not found: <name>},
     * {@code <what> is required}, {@code duplicate <what> <name>}, {@code must be one of ...}).
     * Measured 2026-08-18 over all 362 {@code errors.add} sites, nine shapes cover them; writing
     * the imperative once per shape and substituting the real token beats 362 near-duplicates that
     * drift apart.
     */
    private static String suggestedFixFor(
            String message, String code, String concept, String field, String ruleName) {
        String siteFix = siteSuggestedFix(message);
        if (siteFix != null) {
            return siteFix;
        }
        String coded = codedSuggestedFix(code, concept, field, ruleName);
        if (coded != null) {
            return coded;
        }
        return deriveSuggestedFix(message);
    }

    /** The {@code -- suggestedFix: ...} marker text, or null when the message carries none. */
    private static String siteSuggestedFix(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = SITE_SUGGESTED_FIX.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        String fix = matcher.group(1).trim();
        return fix.isEmpty() ? null : capitalizeSentence(fix);
    }

    /** Null (not a generic sentence) when the code is one of the unclassified fallbacks. */
    private static String codedSuggestedFix(String code, String concept, String field, String ruleName) {
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
            default -> null;
        };
    }

    /**
     * Reads the message's own grammar and returns an imperative that names the exact token to
     * change. Ordered most-specific-shape first: {@code not found} before the generic
     * {@code unknown}, {@code must be one of} before the bare {@code must be}.
     */
    private static String deriveSuggestedFix(String message) {
        if (message == null || message.isBlank()) {
            return UNCLASSIFIED_SUGGESTED_FIX;
        }
        String lower = message.toLowerCase(Locale.ROOT);

        // 1. "<what> not found: <name>" / "<what> not found on concept X: <name>" / "<what> not found"
        int notFound = lower.lastIndexOf("not found");
        if (notFound >= 0) {
            String what = nounBefore(message, notFound);
            String name = nameAfter(message, notFound);
            return "Declare " + articled(what) + named(name)
                    + " before referencing it here, or point the reference at one that already "
                    + "exists -- the name is matched against the declared name exactly.";
        }

        // 2. "references unknown <kind> <name>" / "extends unknown base <name>"
        int referencesUnknown = lower.indexOf("references unknown ");
        if (referencesUnknown >= 0) {
            String tail = message.substring(referencesUnknown + "references unknown ".length()).trim();
            return "Declare " + articled(firstToken(tail)) + named(secondToken(tail))
                    + ", or change this reference to one the model already declares.";
        }
        int extendsUnknown = lower.indexOf("extends unknown ");
        if (extendsUnknown >= 0) {
            String tail = message.substring(extendsUnknown + "extends unknown ".length()).trim();
            return "Declare the base concept" + named(secondToken(tail))
                    + " before extending it, or drop the 'extends' so this concept stands alone.";
        }
        if (lower.contains("does not name")) {
            return "Change" + named(quotedToken(message))
                    + " to a name the referenced declaration actually exposes -- the message says "
                    + "which declaration is being looked in.";
        }

        // 2c. a value that is legal in isolation but absent from the closed list this node declares
        // ("transition to 'archived' is not declared in lifecycle.states"). Two real edits, and the
        // author has to pick: widen the list, or point at something already in it.
        String membership = firstNonNull(afterPhrase(message, lower, "is not declared in "),
                afterPhrase(message, lower, "is not a valid value of "));
        if (membership != null) {
            return "Add" + named(quotedToken(message)) + " to " + membership
                    + ", or point this reference at a value already declared there.";
        }

        // 3. duplicates and ambiguity: two declarations where the model allows one
        if (lower.contains("duplicate") || lower.contains("both declare")
                || lower.contains("both derive") || lower.contains("more than one")
                || lower.contains("at most one") || lower.contains("ambiguous")) {
            String name = firstNonNull(quotedToken(message), trailingToken(message));
            return "Keep exactly one declaration" + named(name) + " -- rename or remove the other, "
                    + "so the name resolves to a single declaration instead of two.";
        }

        // 4. something the model requires is absent
        String missing = subjectBefore(message, lower, "is required", "are required", "is missing",
                "is null", "must declare", "must define", "must have", "must contain", "requires",
                "has no");
        if (missing != null) {
            return "Add " + quotedOr(missing, "the required declaration")
                    + " here -- it is required, not optional, and the model stays rejected until it "
                    + "is present.";
        }

        // 5. present but empty/blank -- a different edit from absent, so a different sentence
        String blank = subjectBefore(message, lower, "must not be empty", "must not be blank",
                "must be non-blank", "is blank", "must not contain blank");
        if (blank != null) {
            return "Give " + quotedOr(blank, "it") + " a non-blank value, or drop the key entirely "
                    + "-- an empty declaration is rejected rather than treated as absent.";
        }

        // 6. a closed set was violated; the message already prints the members next to the value
        if (lower.contains("one of") || lower.contains("(supported:") || lower.contains("must be '")
                || lower.contains("must be \"") || lower.contains("must start with")
                || lower.contains("must match") || lower.contains("must evaluate")
                || lower.contains("must be ") || lower.contains("does not match")
                || lower.contains("is incompatible")) {
            // The offending VALUE, in the four shapes this package prints it: "..., found: x",
            // "..., got x", a trailing ": x", or the message's first quoted token. Ordered so a
            // message that prints both the required form AND the found value ("must start with
            // '/': board") quotes the value, not the requirement.
            String found = firstNonNull(afterMarker(message, lower, "found: "),
                    firstNonNull(afterMarker(message, lower, "got "),
                            firstNonNull(trailingToken(message), quotedToken(message))));
            return (found == null || found.isBlank()
                    ? "Change the value to one this rule accepts"
                    : "Change '" + found + "' to a value this rule accepts")
                    + " -- the message prints the allowed form (or the whole allowed set) beside the "
                    + "value that was found.";
        }

        // 7. declared, but not supported by this DSL version
        if (lower.contains("unsupported") || lower.contains("not supported")
                || lower.contains("unknown ")) {
            String found = firstNonNull(quotedToken(message), lastToken(message));
            return "Replace" + named(found) + " with a value this DSL version supports; if none "
                    + "fits, model the intent with a construct that exists rather than an unknown key.";
        }

        // 8. self-reference / cycle. "cycle" must match as a WORD: "lifecycle" contains it, and
        // matching it as a substring sent every lifecycle diagnostic down this branch.
        if (CYCLE_WORD.matcher(lower).find() || lower.contains("calls itself")
                || lower.contains("reference itself") || lower.contains("target itself")
                || lower.contains("extend itself") || lower.contains("shadows")) {
            return "Break the chain by removing one link in it -- a declaration may not reach "
                    + "itself, directly or through the intermediate declarations this message names.";
        }

        // 9. two declarations that are individually legal but illegal together
        if (lower.contains("mutually exclusive") || lower.contains("cannot be combined")
                || lower.contains("cannot") || lower.contains("may never")
                || lower.contains("may only") || lower.contains("not allowed")
                || lower.contains("is forbidden") || lower.contains("only allowed")
                || lower.contains("only supported") || lower.contains("is limited to")) {
            return "Remove one of the two conflicting declarations this message names -- each is "
                    + "legal on its own but they may not both sit on the same node.";
        }

        // 10. an expression the validator parsed but could not accept
        if (lower.contains("expression") || lower.contains("syntax") || lower.contains("invalid")
                || lower.contains("predicate") || lower.contains("condition")) {
            return "Rewrite the expression into the subset this validator accepts -- the message "
                    + "names the offending clause; unsupported syntax is refused, never ignored.";
        }

        return UNCLASSIFIED_SUGGESTED_FIX;
    }

    /**
     * R1.4: a knowledge-card id when one documents this diagnostic class, else the documentation
     * lookup key. See {@link #KNOWLEDGE_CARD_SIGNATURES} for why only three are wired.
     */
    private static String helpKeyFor(String message, String documentationKey) {
        if (message != null && !message.isBlank()) {
            String lower = message.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : KNOWLEDGE_CARD_SIGNATURES.entrySet()) {
                int separator = entry.getKey().indexOf(':');
                String scope = entry.getKey().substring(0, separator);
                String phrase = entry.getKey().substring(separator + 1);
                if (lower.contains(scope) && lower.contains(phrase)) {
                    return entry.getValue();
                }
            }
        }
        return documentationKey;
    }

    /**
     * Words that are never the noun an author has to act on, so a noun phrase that ends on one is
     * shortened rather than quoted back ("a a procedure" was the first thing this produced).
     */
    private static final Set<String> NOUN_STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "its", "it", "this", "that", "and", "or", "not", "but",
            "has", "have", "no", "one", "of", "on", "in", "to", "at", "by", "for", "names", "name",
            "with", "be", "was", "were", "must", "may", "can", "cannot", "own");

    /** The 1-2 word noun phrase immediately before {@code index}, e.g. "root concept". */
    private static String nounBefore(String message, int index) {
        String head = message.substring(0, index).trim();
        int colon = head.lastIndexOf(':');
        if (colon >= 0) {
            head = head.substring(colon + 1).trim();
        }
        // Drop any quoted value sitting between the noun and the phrase ("statusField 'state' not
        // found") -- the quoted part is the NAME, and quoting it inside the noun produced
        // "a statusfield 'state' 'state'".
        head = QUOTED_TOKEN.matcher(head).replaceAll(" ").trim();
        String[] words = head.isEmpty() ? new String[0] : head.split("\\s+");
        if (words.length == 0) {
            return "declaration";
        }
        String last = words[words.length - 1].toLowerCase(Locale.ROOT);
        if (last.isBlank() || NOUN_STOPWORDS.contains(last)) {
            return "declaration";
        }
        if (words.length >= 2) {
            String previous = words[words.length - 2].toLowerCase(Locale.ROOT);
            if (previous.matches("[a-z][a-z.]*") && !NOUN_STOPWORDS.contains(previous)) {
                return previous + " " + last;
            }
        }
        return last;
    }

    /**
     * The offending name printed after "not found", which this package writes either as
     * {@code ... not found: <name>} or {@code ... not found on concept X: <name>}. Only a colon
     * that comes AFTER the phrase can carry it -- the leading {@code "Aggregate Foo:"} context
     * colon must not be mistaken for one.
     */
    private static String nameAfter(String message, int phraseIndex) {
        int colon = message.indexOf(':', phraseIndex);
        if (colon < 0 || colon == message.length() - 1) {
            return quotedToken(message.substring(0, phraseIndex));
        }
        return firstWordOf(message.substring(colon + 1));
    }

    /**
     * The offending name when the message ENDS with {@code ": <name>"}, else null. The
     * single-word requirement is what separates "route must start with '/': board" (the tail IS
     * the value) from "field createdAt: ui.widget \"checkbox\" is incompatible with type date"
     * (the tail is the whole complaint, and quoting it back read as nonsense).
     */
    private static String trailingToken(String message) {
        int colon = message.lastIndexOf(':');
        if (colon < 0 || colon == message.length() - 1) {
            return null;
        }
        String tail = message.substring(colon + 1).trim();
        return tail.contains(" ") ? null : firstWordOf(tail);
    }

    private static String firstWordOf(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split("\\s+");
        String first = parts[0].replaceAll("^['\"]+|['\",;]+$", "");
        return first.isBlank() || first.length() > 60 ? null : first;
    }

    /** Text following {@code phrase}, up to the end of the clause -- the collection being looked in. */
    private static String afterPhrase(String message, String lower, String phrase) {
        int at = lower.indexOf(phrase);
        if (at < 0) {
            return null;
        }
        String tail = message.substring(at + phrase.length()).trim();
        int stop = tail.indexOf(" --");
        if (stop > 0) {
            tail = tail.substring(0, stop);
        }
        tail = tail.replaceAll("[.,;]+$", "").trim();
        return tail.isEmpty() || tail.length() > 60 ? null : tail;
    }

    /**
     * The noun immediately before whichever of {@code phrases} appears first, so a "&lt;x&gt; is
     * required" style message can be answered with "Add '&lt;x&gt;'" rather than "add the thing the
     * message names". Returns null when none of the phrases is present.
     */
    private static String subjectBefore(String message, String lower, String... phrases) {
        int best = -1;
        for (String phrase : phrases) {
            int at = lower.indexOf(phrase);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        if (best < 0) {
            return null;
        }
        String noun = nounBefore(message, best);
        return "declaration".equals(noun) ? "" : noun;
    }

    /** Text after a marker such as {@code "found: "}, up to the end of the message. */
    private static String afterMarker(String message, String lower, String marker) {
        int at = lower.lastIndexOf(marker);
        if (at < 0) {
            return null;
        }
        String tail = message.substring(at + marker.length()).trim();
        return tail.isEmpty() || tail.length() > 60 ? null : tail;
    }

    /** The final word of the message -- the offending value in this package's "unsupported X y" shape. */
    private static String lastToken(String message) {
        String[] parts = message.trim().split("\\s+");
        if (parts.length == 0) {
            return null;
        }
        String last = parts[parts.length - 1].replaceAll("^['\"(]+|['\").,;]+$", "");
        return last.isBlank() ? null : last;
    }

    private static String firstToken(String value) {
        String[] parts = value.trim().split("\\s+");
        return parts.length == 0 ? "declaration" : parts[0];
    }

    private static String secondToken(String value) {
        String[] parts = value.trim().split("\\s+");
        return parts.length < 2 ? null : parts[1];
    }

    /** The first single-quoted or double-quoted token in the message, if any. */
    private static String quotedToken(String message) {
        Matcher matcher = QUOTED_TOKEN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static final Pattern QUOTED_TOKEN = Pattern.compile("['\"]([^'\"]{1,60})['\"]");

    private static final Pattern CYCLE_WORD = Pattern.compile("\\bcycle\\b");

    private static String firstNonNull(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String articled(String noun) {
        if (noun == null || noun.isBlank()) {
            return "the missing declaration";
        }
        boolean vowel = "aeiou".indexOf(Character.toLowerCase(noun.charAt(0))) >= 0;
        return (vowel ? "an " : "a ") + noun;
    }

    private static String named(String value) {
        return value == null || value.isBlank() ? "" : " '" + value + "'";
    }

    private static String quotedOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : "'" + value + "'";
    }

    private static String capitalizeSentence(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
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
