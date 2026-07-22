package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * Validates root model JSON against the canonical DSL 1.0.0 schema before semantic parsing.
 */
public final class JsonModelSchemaValidator {

    private static final String SCHEMA_RESOURCE_PATH = "/schema/model.schema.json";

    private final JsonSchemaResourceValidator delegate;

    public JsonModelSchemaValidator() {
        this.delegate = new JsonSchemaResourceValidator(SCHEMA_RESOURCE_PATH);
    }

    public void validate(JsonNode modelRoot, String sourceLabel) throws IOException {
        delegate.validate(modelRoot, sourceLabel);
    }

    public ValidationResult validateWithDiagnostics(JsonNode modelRoot, String sourceLabel) {
        return delegate.validateWithDiagnostics(modelRoot, sourceLabel);
    }
}
