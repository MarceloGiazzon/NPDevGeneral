package com.npdev.adapters.schema.validator;

import com.npdev.kernel.InputValidationError;
import com.npdev.kernel.schema.SchemaObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSchemaValidatorTest {

    @Test
    void validateReturnsMissingRequiredErrors() {
        SchemaObject schema = new SchemaObject(
                "object",
                Map.of(
                        "email", new SchemaObject("string", Map.of(), List.of(), null, null, null, null, null, null)
                ),
                List.of("email"),
                null,
                null,
                null,
                null,
                null,
                null
        );

        DefaultSchemaValidator validator = new DefaultSchemaValidator();
        List<InputValidationError> errors = validator.validate(schema, Map.of("name", "Ana"));

        assertEquals(1, errors.size());
        assertEquals("$.email", errors.get(0).field());
        assertEquals("required_missing", errors.get(0).code());
    }

    @Test
    void validateReturnsTypeMismatchErrors() {
        SchemaObject schema = new SchemaObject(
                "object",
                Map.of(
                        "age", new SchemaObject("integer", Map.of(), List.of(), null, null, null, 0d, 130d, null)
                ),
                List.of("age"),
                null,
                null,
                null,
                null,
                null,
                null
        );

        DefaultSchemaValidator validator = new DefaultSchemaValidator();
        List<InputValidationError> errors = validator.validate(schema, Map.of("age", "twenty"));

        assertEquals(1, errors.size());
        assertEquals("$.age", errors.get(0).field());
        assertEquals("type_mismatch", errors.get(0).code());
    }

    @Test
    void validateChecksStringAndNumericConstraints() {
        SchemaObject schema = new SchemaObject(
                "object",
                Map.of(
                        "email", new SchemaObject("string", Map.of(), List.of(), null, 5, null, null, null, ".+@.+"),
                        "score", new SchemaObject("number", Map.of(), List.of(), null, null, null, 0d, 10d, null)
                ),
                List.of("email", "score"),
                null,
                null,
                null,
                null,
                null,
                null
        );

        DefaultSchemaValidator validator = new DefaultSchemaValidator();
        List<InputValidationError> errors = validator.validate(schema, Map.of("email", "a", "score", 99));

        assertEquals(3, errors.size());
        assertTrue(errors.stream().anyMatch(error -> "min_length".equals(error.code())));
        assertTrue(errors.stream().anyMatch(error -> "regex_mismatch".equals(error.code())));
        assertTrue(errors.stream().anyMatch(error -> "max_value".equals(error.code())));
    }
}
