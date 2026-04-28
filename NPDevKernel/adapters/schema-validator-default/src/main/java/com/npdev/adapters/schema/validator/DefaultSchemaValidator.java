package com.npdev.adapters.schema.validator;

import com.npdev.kernel.InputValidationError;
import com.npdev.kernel.ports.SchemaValidator;
import com.npdev.kernel.schema.SchemaObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class DefaultSchemaValidator implements SchemaValidator {

    @Override
    public List<InputValidationError> validate(SchemaObject schema, Object payload) {
        if (schema == null) {
            return List.of();
        }
        List<InputValidationError> errors = new ArrayList<>();
        validateNode(schema, payload, "$", errors);
        return List.copyOf(errors);
    }

    private static void validateNode(
            SchemaObject schema,
            Object payload,
            String fieldPath,
            List<InputValidationError> errors
    ) {
        if (schema == null) {
            return;
        }

        String type = normalizeType(schema.getType());
        if (!type.isBlank() && !matchesType(type, payload)) {
            errors.add(new InputValidationError(
                    fieldPath,
                    "type_mismatch",
                    "Expected type '" + type + "' but got '" + runtimeType(payload) + "'"
            ));
            return;
        }

        if ("object".equals(type) && payload instanceof Map<?, ?> mapPayload) {
            validateRequired(schema, mapPayload, fieldPath, errors);
            validateProperties(schema, mapPayload, fieldPath, errors);
        }
        if ("string".equals(type) && payload instanceof String stringPayload) {
            validateStringConstraints(schema, stringPayload, fieldPath, errors);
        }
        if (("integer".equals(type) || "number".equals(type)) && payload instanceof Number numberPayload) {
            validateNumericConstraints(schema, numberPayload, fieldPath, errors);
        }
    }

    private static void validateRequired(
            SchemaObject schema,
            Map<?, ?> payload,
            String fieldPath,
            List<InputValidationError> errors
    ) {
        for (String requiredField : schema.getRequired()) {
            if (requiredField == null || requiredField.isBlank()) {
                continue;
            }
            if (!payload.containsKey(requiredField) || payload.get(requiredField) == null) {
                errors.add(new InputValidationError(
                        fieldPath + "." + requiredField,
                        "required_missing",
                        "Required field is missing"
                ));
            }
        }
    }

    private static void validateProperties(
            SchemaObject schema,
            Map<?, ?> payload,
            String fieldPath,
            List<InputValidationError> errors
    ) {
        for (Map.Entry<String, SchemaObject> property : schema.getProperties().entrySet()) {
            if (!payload.containsKey(property.getKey())) {
                continue;
            }
            Object propertyValue = payload.get(property.getKey());
            validateNode(property.getValue(), propertyValue, fieldPath + "." + property.getKey(), errors);
        }
    }

    private static void validateStringConstraints(
            SchemaObject schema,
            String payload,
            String fieldPath,
            List<InputValidationError> errors
    ) {
        Integer minLength = schema.getMinLength();
        if (minLength != null && payload.length() < minLength) {
            errors.add(new InputValidationError(
                    fieldPath,
                    "min_length",
                    "Minimum length is " + minLength
            ));
        }
        Integer maxLength = schema.getMaxLength();
        if (maxLength != null && payload.length() > maxLength) {
            errors.add(new InputValidationError(
                    fieldPath,
                    "max_length",
                    "Maximum length is " + maxLength
            ));
        }

        String regex = schema.getRegex();
        if (regex == null || regex.isBlank()) {
            return;
        }
        try {
            if (!Pattern.compile(regex).matcher(payload).matches()) {
                errors.add(new InputValidationError(
                        fieldPath,
                        "regex_mismatch",
                        "Value does not match expected pattern"
                ));
            }
        } catch (PatternSyntaxException ignored) {
            errors.add(new InputValidationError(
                    fieldPath,
                    "regex_invalid",
                    "Schema regex is invalid"
            ));
        }
    }

    private static void validateNumericConstraints(
            SchemaObject schema,
            Number payload,
            String fieldPath,
            List<InputValidationError> errors
    ) {
        double value = payload.doubleValue();
        Double min = schema.getMin();
        if (min != null && value < min) {
            errors.add(new InputValidationError(
                    fieldPath,
                    "min_value",
                    "Minimum value is " + min
            ));
        }
        Double max = schema.getMax();
        if (max != null && value > max) {
            errors.add(new InputValidationError(
                    fieldPath,
                    "max_value",
                    "Maximum value is " + max
            ));
        }
    }

    private static boolean matchesType(String type, Object payload) {
        if (payload == null) {
            return false;
        }
        return switch (type) {
            case "object" -> payload instanceof Map<?, ?>;
            case "array" -> payload instanceof List<?>;
            case "string" -> payload instanceof String;
            case "boolean" -> payload instanceof Boolean;
            case "integer" -> payload instanceof Byte
                    || payload instanceof Short
                    || payload instanceof Integer
                    || payload instanceof Long;
            case "number" -> payload instanceof Number;
            default -> true;
        };
    }

    private static String runtimeType(Object payload) {
        return payload == null ? "null" : payload.getClass().getSimpleName();
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "";
        }
        String normalized = type.trim().toLowerCase();
        return normalized.isBlank() ? "" : normalized;
    }
}
