package com.finalexec.npdev.service;

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

public final class PluginManifestSchemaValidator {

    static final String DEFAULT_SCHEMA_RESOURCE_PATH = "npdev/schema/npdev-plugin-manifest-v1.schema.json";

    private final String schemaResourcePath;
    private final JsonSchema schema;

    public PluginManifestSchemaValidator() {
        this(DEFAULT_SCHEMA_RESOURCE_PATH);
    }

    public PluginManifestSchemaValidator(String schemaResourcePath) {
        this.schemaResourcePath = normalizeResourcePath(schemaResourcePath);
        this.schema = loadSchema(this.schemaResourcePath);
    }

    public void validate(JsonNode manifestRoot, String sourceLabel) {
        Set<ValidationMessage> violations = schema.validate(manifestRoot);
        if (violations.isEmpty()) {
            return;
        }

        List<String> messages = violations.stream()
                .map(ValidationMessage::getMessage)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        StringBuilder builder = new StringBuilder();
        builder.append("Plugin manifest schema validation failed for ")
                .append(sourceLabel)
                .append(":");
        for (String message : messages) {
            builder.append(System.lineSeparator()).append(" - ").append(message);
        }
        throw new IllegalStateException(builder.toString());
    }

    private static JsonSchema loadSchema(String resourcePath) {
        try (InputStream stream = PluginManifestSchemaValidator.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Unable to locate plugin manifest schema resource: " + resourcePath);
            }
            String schemaJson = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            return factory.getSchema(schemaJson);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load plugin manifest schema resource: " + resourcePath, exception);
        }
    }

    private static String normalizeResourcePath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must be non-blank");
        }
        String normalized = resourcePath.trim();
        if (normalized.startsWith("classpath:")) {
            normalized = normalized.substring("classpath:".length());
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("resourcePath must resolve to a classpath resource");
        }
        return normalized;
    }
}
