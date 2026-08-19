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
import java.util.stream.Collectors;

import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;
import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;

/**
 * Semantic validation for field-level schema shape (object/array nested schema) and value-behavior
 * expressions ({@code defaultExpression} / {@code derivedExpression}: syntax, field references, and
 * dependency-cycle detection).
 *
 * <p>Split out of {@code SemanticValidator} (T1.15) as a sub-boundary of the Concept section
 * (see {@link ConceptValidation}).
 */
final class FieldValueValidation {

    private FieldValueValidation() {
    }

    static void validateObjectFieldSchema(String entityName, FieldAst field, List<String> errors) {
        SchemaAst schema = field.getSchema();
        if (schema == null || !"object".equals(normalize(schema.getType()))) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": object field must declare object schema with properties");
            return;
        }
        validateNestedSchema(entityName, field.getName(), field.getName(), schema, errors);
    }

    static void validateArrayFieldSchema(String entityName, FieldAst field, List<String> errors) {
        SchemaAst schema = field.getSchema();
        if (schema == null || !"array".equals(normalize(schema.getType()))) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": array field must declare array schema with items");
            return;
        }
        validateNestedSchema(entityName, field.getName(), field.getName(), schema, errors);
    }

    static void validateDecimalFieldSchema(String entityName, FieldAst field, List<String> errors) {
        SchemaAst schema = field.getSchema();
        Integer precision = schema == null ? null : schema.getPrecision();
        Integer scale = schema == null ? null : schema.getScale();
        if (precision != null && (precision < 1 || precision > 38)) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": decimal precision must be between 1 and 38, got " + precision);
        }
        if (scale != null && scale < 0) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": decimal scale must be >= 0, got " + scale);
        }
        if (precision != null && scale != null && scale > precision) {
            errors.add("Entity " + entityName + " field " + field.getName()
                    + ": decimal scale (" + scale + ") must not exceed precision (" + precision + ")"
                    + " -- suggestedFix: lower scale to at most " + precision + ", or raise precision to at "
                    + "least " + scale + "; scale counts digits AFTER the point and precision counts them all");
        }
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
                            + ": object schema at " + schemaPath + " marks missing required property " + requiredField
                            + " -- suggestedFix: add a property named '" + requiredField + "' to properties at "
                            + schemaPath + ", or drop '" + requiredField + "' from that schema's required[]");
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

    static boolean areCompatibleTypes(String left, String right) {
        return normalizeComparableType(left).equals(normalizeComparableType(right));
    }

    private static String normalizeComparableType(String value) {
        String normalized = normalize(value);
        return switch (normalized) {
            case "int" -> "integer";
            default -> normalized;
        };
    }

    static void validateFieldValueBehavior(
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

    static void validateFieldValueBehaviorGraph(
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

    /**
     * R4.1 (roadmap): the author-time shape check for a defaultExpression/derivedExpression used
     * to be a hand-rolled recognizer capped at a five-name function whitelist (concat/coalesce/
     * trim/uppercase/lowercase) that had no notion of an operator at all -- {@code "quantity *
     * unitPrice"} failed to parse as anything (not a literal, not a bare identifier, no '(' to
     * make it a call), so the single most common derived field in any business app was refused at
     * author time even though the runtime already evaluated it.
     *
     * <p>The runtime ({@code SchemaExpressionSupport.evaluateSchemaExpression}, the
     * expression-cel adapter's {@code applySchemaValueBehaviors}) has evaluated the full {@link
     * ComputedExpression} grammar -- arithmetic, comparison, logical, function calls, lambdas --
     * for every default/derived expression all along, trying it first and only falling back to a
     * legacy literal-evaluator for the small set of things it doesn't parse. This method now
     * validates the identical grammar the runtime evaluates, the same widening
     * {@code ConceptValidation.validateAccessExpression}/{@code referencedFields} already applied
     * to invariant and row-level access expressions (LIFT-EXPR-P3) -- one grammar, one validator,
     * instead of a second hand-rolled one that only understood a subset. A call to a function the
     * runtime does not (yet) register -- e.g. a future {@code nextNumber()}/{@code role()} -- now
     * parses here too, since {@link ComputedExpression} accepts any call syntax; it evaluates to
     * null at runtime (the existing lenient fallback) until such a function is actually
     * registered, exactly the "rides the same widened path" future work the roadmap calls out.
     */
    private static ValueExpressionAnalysis analyzeValueBehaviorExpression(String expression) {
        String trimmed = expression == null ? "" : expression.trim();
        if (trimmed.isBlank()) {
            return new ValueExpressionAnalysis(false, List.of(), "expression must be non-blank");
        }
        try {
            ComputedExpression.validate(trimmed);
        } catch (ComputedExpression.ExpressionException syntaxError) {
            return new ValueExpressionAnalysis(false, List.of(),
                    "unsupported syntax: " + trimmed + " (" + syntaxError.getMessage() + ")");
        }
        return new ValueExpressionAnalysis(true, List.copyOf(ComputedExpression.referencedFields(trimmed)), null);
    }

    private record ValueExpressionAnalysis(boolean valid, List<String> references, String error) {
        private ValueExpressionAnalysis {
            references = references == null ? List.of() : List.copyOf(references);
        }
    }

}
