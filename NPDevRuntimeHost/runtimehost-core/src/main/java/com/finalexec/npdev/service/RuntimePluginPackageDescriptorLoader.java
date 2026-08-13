package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.model.RuntimePluginPackageDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class RuntimePluginPackageDescriptorLoader {

    private final ObjectMapper objectMapper;
    private final PluginPackageSchemaValidator packageSchemaValidator;

    public RuntimePluginPackageDescriptorLoader(
            ObjectMapper objectMapper,
            PluginPackageSchemaValidator schemaValidator
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.packageSchemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator");
    }

    public RuntimePluginPackageDescriptor load(String resourcePath) {
        String normalizedResourcePath = normalizeDescriptorLocation(resourcePath);
        try (InputStream inputStream = openDescriptorStream(normalizedResourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Unable to locate runtime plugin package descriptor: " + normalizedResourcePath);
            }

            JsonNode descriptorRoot = objectMapper.readTree(inputStream);
            if (looksLikeExternalManifestDescriptor(descriptorRoot)) {
                validateExternalManifestDescriptor(descriptorRoot, normalizedResourcePath);
                return RuntimePluginPackageDescriptor.fromExternalManifest(normalizedResourcePath, descriptorRoot);
            }
            packageSchemaValidator.validate(descriptorRoot, normalizedResourcePath);
            return RuntimePluginPackageDescriptor.fromJson(normalizedResourcePath, descriptorRoot);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to load runtime plugin package descriptor from " + normalizedResourcePath,
                    exception
            );
        }
    }

    private static boolean looksLikeExternalManifestDescriptor(JsonNode descriptorRoot) {
        return descriptorRoot.has("trustLevel")
                || descriptorRoot.path("compatibility").has("npdevMinVersion")
                || descriptorRoot.path("compatibility").has("npdevMaxVersion");
    }

    private static void validateExternalManifestDescriptor(JsonNode descriptorRoot, String sourceLabel) {
        requireNonBlankText(descriptorRoot, "packageId", sourceLabel);
        requireNonBlankText(descriptorRoot, "version", sourceLabel);

        String trustLevel = requireNonBlankText(descriptorRoot, "trustLevel", sourceLabel);
        if (!"trusted".equalsIgnoreCase(trustLevel) && !"untrusted".equalsIgnoreCase(trustLevel)) {
            throw new IllegalStateException(
                    "External plugin manifest validation failed for " + sourceLabel
                            + ": trustLevel must be 'trusted' or 'untrusted'"
            );
        }

        JsonNode compatibilityNode = requireObject(descriptorRoot, "compatibility", sourceLabel);
        requireNonBlankText(compatibilityNode, "npdevMinVersion", sourceLabel + " compatibility");
        requireNonBlankText(compatibilityNode, "npdevMaxVersion", sourceLabel + " compatibility");

        JsonNode capabilitiesNode = requireArray(descriptorRoot, "capabilities", sourceLabel);
        if (capabilitiesNode.isEmpty()) {
            throw new IllegalStateException(
                    "External plugin manifest validation failed for " + sourceLabel
                            + ": capabilities must declare at least one entry"
            );
        }

        for (int index = 0; index < capabilitiesNode.size(); index++) {
            JsonNode capabilityNode = capabilitiesNode.get(index);
            String capabilityContext = sourceLabel + " capabilities[" + index + "]";
            requireNonBlankText(capabilityNode, "capability", capabilityContext);
            requireNonBlankText(capabilityNode, "adapterId", capabilityContext);
            JsonNode operationsNode = requireArray(capabilityNode, "operations", capabilityContext);
            if (operationsNode.isEmpty()) {
                throw new IllegalStateException(
                        "External plugin manifest validation failed for " + capabilityContext
                                + ": operations must declare at least one value"
                );
            }
            for (JsonNode operationNode : operationsNode) {
                if (operationNode == null || operationNode.asText().isBlank()) {
                    throw new IllegalStateException(
                            "External plugin manifest validation failed for " + capabilityContext
                                    + ": operations must not contain blank values"
                    );
                }
            }
        }
    }

    private static JsonNode requireObject(JsonNode node, String propertyName, String sourceLabel) {
        JsonNode value = node.path(propertyName);
        if (!value.isObject()) {
            throw new IllegalStateException(
                    "External plugin manifest validation failed for " + sourceLabel
                            + ": property '" + propertyName + "' must be an object"
            );
        }
        return value;
    }

    private static JsonNode requireArray(JsonNode node, String propertyName, String sourceLabel) {
        JsonNode value = node.path(propertyName);
        if (!value.isArray()) {
            throw new IllegalStateException(
                    "External plugin manifest validation failed for " + sourceLabel
                            + ": property '" + propertyName + "' must be an array"
            );
        }
        return value;
    }

    private static String requireNonBlankText(JsonNode node, String propertyName, String sourceLabel) {
        JsonNode value = node.path(propertyName);
        String text = value.isMissingNode() || value.isNull() ? "" : value.asText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(
                    "External plugin manifest validation failed for " + sourceLabel
                            + ": property '" + propertyName + "' must be non-blank"
            );
        }
        return text.trim();
    }

    private static InputStream openDescriptorStream(String descriptorLocation) throws IOException {
        if (looksLikeFilesystemPath(descriptorLocation)) {
            return Files.newInputStream(Path.of(descriptorLocation));
        }
        return RuntimePluginPackageDescriptorLoader.class.getClassLoader()
                .getResourceAsStream(descriptorLocation);
    }

    private static String normalizeDescriptorLocation(String resourcePath) {
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
