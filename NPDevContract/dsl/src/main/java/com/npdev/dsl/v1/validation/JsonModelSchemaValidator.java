package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Validates root model JSON against the canonical DSL 1.0.0 schema before semantic parsing.
 */
public final class JsonModelSchemaValidator {

    private static final String SCHEMA_RESOURCE_PATH = "/schema/model.schema.json";

    private final JsonSchema schema;

    public JsonModelSchemaValidator() {
        this.schema = loadSchema();
    }

    public void validate(JsonNode modelRoot, String sourceLabel) throws IOException {
        ValidationResult result = validateWithDiagnostics(modelRoot, sourceLabel);
        if (!result.hasErrors()) {
            return;
        }
        throw new ModelSchemaValidationException(sourceLabel, result.getDiagnostics());
    }

    public ValidationResult validateWithDiagnostics(JsonNode modelRoot, String sourceLabel) {
        Set<ValidationMessage> violations = schema.validate(modelRoot);
        if (violations.isEmpty()) {
            return ValidationResult.fromDiagnostics(List.of());
        }

        List<ValidationDiagnostic> diagnostics = violations.stream()
                .map(message -> ValidationDiagnosticNormalizer.structuralDiagnostic(message, sourceLabel))
                .sorted((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(
                        left.getMessage(),
                        right.getMessage()
                ))
                .toList();

        return ValidationResult.fromDiagnostics(diagnostics);
    }

    private static JsonSchema loadSchema() {
        try (InputStream stream = JsonModelSchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE_PATH)) {
            if (stream == null) {
                throw new IllegalStateException("Unable to locate schema resource: " + SCHEMA_RESOURCE_PATH);
            }
            String schemaJson = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            return factory.getSchema(schemaJson);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load schema resource: " + SCHEMA_RESOURCE_PATH, exception);
        }
    }
}
