package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.runtime.support.GeneratedCrudRuntimeSupport.InvariantViolationDetail;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.FieldValueConstraints;
import com.npdev.kernel.ports.InvariantEngine;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static com.npdev.runtime.support.crud.reflection.ObjectFieldSupport.hasMapKey;
import static com.npdev.runtime.support.crud.schemaexpr.SchemaExpressionSupport.evaluateSchemaExpression;
import static com.npdev.runtime.support.crud.uniqueness.InvariantMessageSupport.extractFieldPath;
import static com.npdev.runtime.support.crud.uniqueness.InvariantMessageSupport.extractPathFromMessage;
import static com.npdev.runtime.support.crud.uniqueness.InvariantMessageSupport.inferInvariantFromMessage;
import static com.npdev.runtime.support.crud.valuecoercion.DslTypeCoercionSupport.normalizeByDslType;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.normalizeType;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.readPayloadValue;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.toUuid;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.mapWithStringKeys;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.normalize;
import static com.npdev.runtime.support.GeneratedCrudRuntimeSupport.readMapValue;

/**
 * CARVE-3: field type / enum / date / reference / nested-schema validation and the violation-detail
 * mappers, moved byte-faithfully out of GeneratedCrudRuntimeSupport.
 */
final class CrudSchemaValidationSupport {
    private final GeneratedCrudRuntimeSupport owner;

    CrudSchemaValidationSupport(GeneratedCrudRuntimeSupport owner) {
        this.owner = owner;
    }

    private Optional<CompiledConcept> findEntity(String name) {
        return owner.findEntity(name);
    }

    private Optional<CompiledField> resolveReferenceAnchor(CompiledField referenceField, CompiledConcept targetEntity) {
        return owner.resolveReferenceAnchor(referenceField, targetEntity);
    }

    private boolean existsByAnchor(CompiledConcept entity, CompiledField anchor, Object value) {
        return owner.existsByAnchor(entity, anchor, value);
    }

    List<String> validateFieldTypesAndReferences(CompiledConcept entity, Map<String, Object> payload) {
        if (entity == null || entity.getName() == null || entity.getName().isBlank()) {
            return List.of("Entity metadata is required for runtime field validation");
        }
        List<String> violations = new ArrayList<>();
        for (CompiledField field : entity.getFields()) {
            if (field == null || field.getName() == null || field.getName().isBlank()) {
                continue;
            }
            if (field.isId()) {
                continue;
            }

            Object value = readPayloadValue(payload, field.getName());
            if (value == null) {
                continue;
            }

            String dslType = normalizeType(field.getDslType());
            switch (dslType) {
                case "enum" -> validateEnumField(entity.getName(), field, value, violations);
                case "date" -> validateDateField(entity.getName(), field, value, violations);
                case "datetime" -> validateDateTimeField(entity.getName(), field, value, violations);
                case "reference" -> validateReferenceField(entity.getName(), field, value, violations);
                case "object", "array" -> validateNestedSchemaField(entity.getName(), field, value, violations);
                default -> {
                    // Validation for required/unique/expression invariants is handled by invariant engine.
                }
            }
            // REG-254/255/256: the declared maxLength / min / max / precision / scale are enforced on
            // create AND update (the update payload is the merged record), 422 like every other
            // field-level violation here. Shared with the flow createConcept path.
            violations.addAll(FieldValueConstraints.violations(entity.getName(), field, value));
        }
        return violations;
    }

    private void validateEnumField(
            String entityName,
            CompiledField field,
            Object value,
            List<String> violations
    ) {
        String normalized = value.toString().trim();
        if (normalized.isEmpty()) {
            return;
        }
        Collection<String> allowed = field.getEnumValues();
        boolean match = allowed.stream().anyMatch(candidate -> candidate != null && candidate.equals(normalized));
        if (!match) {
            violations.add("Entity " + entityName + ": enum constraint violated for field '" + field.getName()
                    + "' (allowed: " + allowed + ")");
        }
    }

    private void validateDateField(
            String entityName,
            CompiledField field,
            Object value,
            List<String> violations
    ) {
        try {
            if (value instanceof LocalDate) {
                return;
            }
            if (value instanceof String raw) {
                LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
                return;
            }
            violations.add("Entity " + entityName + ": date field '" + field.getName()
                    + "' must use ISO format YYYY-MM-DD");
        } catch (DateTimeParseException exception) {
            violations.add("Entity " + entityName + ": date field '" + field.getName()
                    + "' must use ISO format YYYY-MM-DD");
        }
    }

    private void validateDateTimeField(
            String entityName,
            CompiledField field,
            Object value,
            List<String> violations
    ) {
        if (value instanceof OffsetDateTime || value instanceof LocalDateTime) {
            return;
        }
        if (!(value instanceof String raw)) {
            violations.add("Entity " + entityName + ": datetime field '" + field.getName()
                    + "' must use ISO datetime format");
            return;
        }

        String candidate = raw.trim();
        if (candidate.isEmpty()) {
            return;
        }
        try {
            DateTimeFormatter.ISO_DATE_TIME.parseBest(
                    candidate,
                    OffsetDateTime::from,
                    LocalDateTime::from
            );
        } catch (DateTimeParseException exception) {
            violations.add("Entity " + entityName + ": datetime field '" + field.getName()
                    + "' must use ISO datetime format");
        }
    }

    private void validateReferenceField(
            String entityName,
            CompiledField field,
            Object value,
            List<String> violations
    ) {
        String targetEntityName = field.getReferenceTarget();
        if (targetEntityName == null || targetEntityName.isBlank()) {
            violations.add("Entity " + entityName + ": reference field '" + field.getName()
                    + "' has no target entity");
            return;
        }

        Optional<CompiledConcept> targetEntityOpt = findEntity(targetEntityName);
        if (targetEntityOpt.isEmpty()) {
            violations.add("Entity " + entityName + ": reference target '" + targetEntityName
                    + "' not found in compiled model");
            return;
        }

        CompiledConcept targetEntity = targetEntityOpt.get();
        CompiledField anchor = resolveReferenceAnchor(field, targetEntity).orElse(null);
        if (anchor == null) {
            violations.add("Entity " + entityName + ": reference field '" + field.getName()
                    + "' has no resolvable target anchor");
            return;
        }

        Object anchorValue = normalizeByDslType(anchor.getDslType(), value);
        if (anchorValue == null) {
            violations.add("Entity " + entityName + ": reference field '" + field.getName()
                    + "' must match anchor " + targetEntityName + "." + anchor.getName());
            return;
        }

        if (!existsByAnchor(targetEntity, anchor, anchorValue)) {
            violations.add("Entity " + entityName + ": reference '" + field.getName()
                    + "' points to non-existent " + targetEntityName + "." + anchor.getName()
                    + " " + anchorValue);
        }
    }

    private void validateNestedSchemaField(
            String entityName,
            CompiledField field,
            Object value,
            List<String> violations
    ) {
        CompiledSchema schema = field.getSchema();
        if (schema == null) {
            violations.add("Entity " + entityName + ": field '" + field.getName()
                    + "' is missing schema metadata for nested validation");
            return;
        }
        validateSchemaValue(entityName, field.getName(), schema, value, violations);
    }

    private void validateSchemaValue(
            String entityName,
            String path,
            CompiledSchema schema,
            Object value,
            List<String> violations
    ) {
        if (schema == null) {
            return;
        }
        String type = normalizeType(schema.getType());
        if (type.isBlank()) {
            return;
        }
        if (value == null) {
            return;
        }

        switch (type) {
            case "object" -> validateObjectSchemaValue(entityName, path, schema, value, violations);
            case "array" -> validateArraySchemaValue(entityName, path, schema, value, violations);
            case "enum" -> validateEnumSchemaValue(entityName, path, schema, value, violations);
            case "date" -> validateDateSchemaValue(entityName, path, value, violations);
            case "datetime" -> validateDateTimeSchemaValue(entityName, path, value, violations);
            case "reference", "uuid" -> {
                if (toUuid(value) == null) {
                    violations.add("Entity " + entityName + ": field '" + path + "' must be a UUID");
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) {
                    violations.add("Entity " + entityName + ": field '" + path + "' must be boolean");
                }
            }
            default -> {
                // Scalar type rules already covered by existing field validators when applicable.
            }
        }
    }

    private void validateObjectSchemaValue(
            String entityName,
            String path,
            CompiledSchema schema,
            Object value,
            List<String> violations
    ) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            violations.add("Entity " + entityName + ": field '" + path + "' must be an object");
            return;
        }
        Map<String, Object> map = mapWithStringKeys(rawMap);

        for (String requiredField : schema.getRequired()) {
            if (requiredField == null || requiredField.isBlank()) {
                continue;
            }
            boolean present = hasMapKey(map, requiredField);
            if (!present) {
                CompiledSchema requiredSchema = schema.getProperties().get(requiredField);
                if (requiredSchema != null
                        && (requiredSchema.getDefaultValue() != null
                        || (requiredSchema.getDefaultExpression() != null && !requiredSchema.getDefaultExpression().isBlank())
                        || (requiredSchema.getDerivedExpression() != null && !requiredSchema.getDerivedExpression().isBlank()))) {
                    present = true;
                }
            }
            if (!present) {
                violations.add("Entity " + entityName + ": required nested field '" + path + "."
                        + requiredField + "' is missing");
            }
        }

        for (Map.Entry<String, CompiledSchema> property : schema.getProperties().entrySet()) {
            String propertyName = property.getKey();
            Object propertyValue = readMapValue(map, propertyName);
            if (propertyValue == null && property.getValue() != null) {
                if (property.getValue().getDefaultValue() != null) {
                    propertyValue = property.getValue().getDefaultValue();
                } else if (property.getValue().getDefaultExpression() != null
                        && !property.getValue().getDefaultExpression().isBlank()) {
                    propertyValue = evaluateSchemaExpression(property.getValue().getDefaultExpression(), map);
                } else if (property.getValue().getDerivedExpression() != null
                        && !property.getValue().getDerivedExpression().isBlank()) {
                    propertyValue = evaluateSchemaExpression(property.getValue().getDerivedExpression(), map);
                }
            }
            if (propertyValue == null) {
                continue;
            }
            validateSchemaValue(
                    entityName,
                    path + "." + propertyName,
                    property.getValue(),
                    propertyValue,
                    violations
            );
        }
    }

    private void validateArraySchemaValue(
            String entityName,
            String path,
            CompiledSchema schema,
            Object value,
            List<String> violations
    ) {
        if (!(value instanceof Collection<?> values)) {
            violations.add("Entity " + entityName + ": field '" + path + "' must be an array");
            return;
        }
        if (schema.getItems() == null) {
            return;
        }
        int index = 0;
        for (Object item : values) {
            validateSchemaValue(entityName, path + "[" + index + "]", schema.getItems(), item, violations);
            index++;
        }
    }

    private void validateEnumSchemaValue(
            String entityName,
            String path,
            CompiledSchema schema,
            Object value,
            List<String> violations
    ) {
        if (schema.getEnumValues() == null || schema.getEnumValues().isEmpty()) {
            return;
        }
        String normalized = value.toString().trim();
        if (normalized.isEmpty()) {
            return;
        }
        boolean match = schema.getEnumValues().stream()
                .anyMatch(candidate -> candidate != null && candidate.equals(normalized));
        if (!match) {
            violations.add("Entity " + entityName + ": enum constraint violated for field '" + path
                    + "' (allowed: " + schema.getEnumValues() + ")");
        }
    }

    private void validateDateSchemaValue(
            String entityName,
            String path,
            Object value,
            List<String> violations
    ) {
        try {
            if (value instanceof LocalDate) {
                return;
            }
            if (value instanceof String raw) {
                LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
                return;
            }
            violations.add("Entity " + entityName + ": date field '" + path
                    + "' must use ISO format YYYY-MM-DD");
        } catch (DateTimeParseException exception) {
            violations.add("Entity " + entityName + ": date field '" + path
                    + "' must use ISO format YYYY-MM-DD");
        }
    }

    private void validateDateTimeSchemaValue(
            String entityName,
            String path,
            Object value,
            List<String> violations
    ) {
        if (value instanceof OffsetDateTime || value instanceof LocalDateTime) {
            return;
        }
        if (!(value instanceof String raw)) {
            violations.add("Entity " + entityName + ": datetime field '" + path
                    + "' must use ISO datetime format");
            return;
        }

        String candidate = raw.trim();
        if (candidate.isEmpty()) {
            return;
        }
        try {
            DateTimeFormatter.ISO_DATE_TIME.parseBest(
                    candidate,
                    OffsetDateTime::from,
                    LocalDateTime::from
            );
        } catch (DateTimeParseException exception) {
            violations.add("Entity " + entityName + ": datetime field '" + path
                    + "' must use ISO datetime format");
        }
    }

    InvariantViolationDetail toTypedViolation(String concept, String message) {
        String path = extractPathFromMessage(message);
        String invariant = inferInvariantFromMessage(path, message);
        return new InvariantViolationDetail(
                "invariant_failed",
                concept,
                invariant,
                path,
                message,
                false
        );
    }

    InvariantViolationDetail toInvariantViolation(
            String concept,
            InvariantEngine.Violation violation,
            Set<String> uniqueInvariantRefs
    ) {
        String invariant = violation == null || violation.invariantRef() == null || violation.invariantRef().isBlank()
                ? "unknown_invariant"
                : violation.invariantRef();
        String message = violation == null || violation.message() == null || violation.message().isBlank()
                ? "Invariant validation failed"
                : violation.message();
        String path = extractFieldPath(violation);
        boolean unique = uniqueInvariantRefs.contains(normalize(invariant))
                || message.toLowerCase(Locale.ROOT).contains("unique constraint violated");

        return new InvariantViolationDetail(
                "invariant_failed",
                concept,
                invariant,
                path,
                message,
                unique
        );
    }
}
