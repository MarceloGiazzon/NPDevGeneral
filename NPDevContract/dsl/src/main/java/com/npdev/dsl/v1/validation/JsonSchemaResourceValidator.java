package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * Validates bundled NPDev JSON schemas and resolves schema-to-schema references
 * from the classpath. Active schemas live under {@code /schema/}.
 */
public final class JsonSchemaResourceValidator {
    private static final String SCHEMA_URI_PREFIX = "https://npdev.local/schema/";
    private static final String CLASSPATH_SCHEMA_PREFIX = "classpath:/schema/";

    private final String schemaResourcePath;
    private final JsonSchema schema;

    public JsonSchemaResourceValidator(String schemaResourcePath) {
        if (schemaResourcePath == null || schemaResourcePath.isBlank()) {
            throw new IllegalArgumentException("schemaResourcePath is required");
        }
        this.schemaResourcePath = schemaResourcePath.startsWith("/")
                ? schemaResourcePath
                : "/" + schemaResourcePath;
        this.schema = loadSchema(this.schemaResourcePath);
    }

    public void validate(JsonNode root, String sourceLabel) throws IOException {
        ValidationResult result = validateWithDiagnostics(root, sourceLabel);
        if (!result.hasErrors()) {
            return;
        }
        throw new ModelSchemaValidationException(sourceLabel, result.getDiagnostics());
    }

    public ValidationResult validateWithDiagnostics(JsonNode root, String sourceLabel) {
        Set<ValidationMessage> violations = schema.validate(root);
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

    private static JsonSchema loadSchema(String schemaResourcePath) {
        try (InputStream stream = JsonSchemaResourceValidator.class.getResourceAsStream(schemaResourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Unable to locate schema resource: " + schemaResourcePath);
            }
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                    SpecVersion.VersionFlag.V202012,
                    builder -> builder.schemaMappers(mapper -> mapper.mapPrefix(SCHEMA_URI_PREFIX, CLASSPATH_SCHEMA_PREFIX))
            );
            return factory.getSchema(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load schema resource: " + schemaResourcePath, exception);
        }
    }
}
