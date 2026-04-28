package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.model.RuntimePluginManifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class RuntimePluginManifestLoader {

    private final ObjectMapper objectMapper;
    private final PluginManifestSchemaValidator schemaValidator;

    public RuntimePluginManifestLoader(
            ObjectMapper objectMapper,
            PluginManifestSchemaValidator schemaValidator
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator");
    }

    public RuntimePluginManifest load(String resourcePath) {
        String normalizedResourcePath = normalizeResourcePath(resourcePath);
        try (InputStream inputStream = openManifestStream(normalizedResourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Unable to locate runtime plugin manifest: " + normalizedResourcePath);
            }

            JsonNode manifestRoot = objectMapper.readTree(inputStream);
            schemaValidator.validate(manifestRoot, normalizedResourcePath);
            return RuntimePluginManifest.fromJson(normalizedResourcePath, manifestRoot);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to load runtime plugin manifest from " + normalizedResourcePath,
                    exception
            );
        }
    }

    private static InputStream openManifestStream(String manifestLocation) throws IOException {
        if (looksLikeFilesystemPath(manifestLocation)) {
            return Files.newInputStream(Path.of(manifestLocation));
        }
        return RuntimePluginManifestLoader.class.getClassLoader()
                .getResourceAsStream(manifestLocation);
    }

    private static String normalizeResourcePath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must be non-blank");
        }
        String normalized = resourcePath.trim();
        if (looksLikeFilesystemPath(normalized)) {
            return Path.of(normalized).toAbsolutePath().normalize().toString();
        }
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

    private static boolean looksLikeFilesystemPath(String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            return false;
        }
        if (pathValue.startsWith("classpath:")) {
            return false;
        }
        return Files.exists(Path.of(pathValue))
                || pathValue.contains(":\\")
                || pathValue.startsWith("\\\\");
    }
}
