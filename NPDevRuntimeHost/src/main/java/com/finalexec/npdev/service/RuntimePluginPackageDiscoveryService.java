package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

// verifier-token: class\s+RuntimePluginPackageDiscoveryService
public final class RuntimePluginPackageDiscoveryService {

    private static final String INDEX_FILE_NAME = "index.json";
    private static final String PROJECTED_RESOURCE_LOCATION = "npdev/plugin-packages";
    private static final String FILESYSTEM_PLUGIN_FOLDER_MODE = "filesystem-folder";

    private final ObjectMapper objectMapper;
    private final String classpathDiscoveryLocation;
    private final String pluginPackageDirectory;
    private final String configuredDiscoveryMode;
    private final DiscoveryMode discoveryMode;

    public RuntimePluginPackageDiscoveryService(
            ObjectMapper objectMapper,
            String discoveryLocation
    ) {
        this(objectMapper, discoveryLocation, "", "");
    }

    public RuntimePluginPackageDiscoveryService(
            ObjectMapper objectMapper,
            String discoveryLocation,
            String pluginPackageDirectory
    ) {
        this(objectMapper, discoveryLocation, pluginPackageDirectory, "");
    }

    public RuntimePluginPackageDiscoveryService(
            ObjectMapper objectMapper,
            String discoveryLocation,
            String pluginPackageDirectory,
            String discoveryModeHint
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.classpathDiscoveryLocation = normalizeClasspathResourcePath(discoveryLocation);
        this.pluginPackageDirectory = normalizeOptionalFilesystemDirectory(pluginPackageDirectory);
        this.configuredDiscoveryMode = normalizeOptionalDiscoveryMode(discoveryModeHint);
        this.discoveryMode = inferDiscoveryMode(
                this.configuredDiscoveryMode,
                this.classpathDiscoveryLocation,
                this.pluginPackageDirectory
        );
    }

    public DiscoveryResult discover() {
        if (discoveryMode == DiscoveryMode.FILESYSTEM_FOLDER) {
            return discoverFromFilesystem();
        }
        return discoverFromClasspathIndex();
    }

    private DiscoveryResult discoverFromClasspathIndex() {
        String indexResourcePath = classpathDiscoveryLocation + "/" + INDEX_FILE_NAME;
        try (InputStream inputStream = RuntimePluginPackageDiscoveryService.class.getClassLoader()
                .getResourceAsStream(indexResourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Unable to locate runtime plugin package index: " + indexResourcePath);
            }

            JsonNode root = objectMapper.readTree(inputStream);
            JsonNode resourcesNode = root.path("resources");
            if (!resourcesNode.isArray()) {
                throw new IllegalStateException("Runtime plugin package index missing resources array: " + indexResourcePath);
            }

            List<DiscoveredPackageCandidate> candidates = java.util.stream.StreamSupport.stream(resourcesNode.spliterator(), false)
                    .map(JsonNode::asText)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .map(this::toCandidate)
                    .toList();
            return new DiscoveryResult(discoveryMode.value(), classpathDiscoveryLocation, indexResourcePath, candidates);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to discover runtime plugin packages from " + indexResourcePath, exception);
        }
    }

    private DiscoveryResult discoverFromFilesystem() {
        Path directory = Path.of(pluginPackageDirectory).toAbsolutePath().normalize();
        if (!Files.exists(directory)) {
            throw new IllegalStateException("Runtime plugin package directory does not exist: " + directory);
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Runtime plugin package directory is not a folder: " + directory);
        }
        try (Stream<Path> entries = Files.list(directory)) {
            List<DiscoveredPackageCandidate> candidates = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".package.json"))
                    .sorted((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(
                            left.getFileName().toString(),
                            right.getFileName().toString()
                    ))
                    .map(path -> toCandidate(path.toAbsolutePath().normalize().toString()))
                    .toList();
            return new DiscoveryResult(
                    discoveryMode.value(),
                    directory.toString(),
                    null,
                    candidates
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to enumerate runtime plugin package directory " + directory, exception);
        }
    }

    private DiscoveredPackageCandidate toCandidate(String resourcePath) {
        String normalized = normalizeCandidatePath(resourcePath);
        String derivedPackageId = fileName(normalized)
                .replace(".package.json", "")
                .toLowerCase(Locale.ROOT);
        return new DiscoveredPackageCandidate(activeDiscoveryLocation(), normalized, derivedPackageId);
    }

    private String activeDiscoveryLocation() {
        if (discoveryMode == DiscoveryMode.FILESYSTEM_FOLDER) {
            return pluginPackageDirectory;
        }
        return classpathDiscoveryLocation;
    }

    private static String normalizeClasspathResourcePath(String resourcePath) {
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

    private static String normalizeOptionalFilesystemDirectory(String pluginPackageDirectory) {
        if (pluginPackageDirectory == null || pluginPackageDirectory.isBlank()) {
            return "";
        }
        return Path.of(pluginPackageDirectory.trim()).toAbsolutePath().normalize().toString();
    }

    private static String normalizeCandidatePath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must be non-blank");
        }
        if (looksLikeFilesystemPath(resourcePath)) {
            return Path.of(resourcePath.trim()).toAbsolutePath().normalize().toString();
        }
        return normalizeClasspathResourcePath(resourcePath);
    }

    private static DiscoveryMode inferDiscoveryMode(
            String configuredDiscoveryMode,
            String discoveryLocation,
            String pluginPackageDirectory
    ) {
        if (!configuredDiscoveryMode.isBlank()) {
            DiscoveryMode explicitMode = DiscoveryMode.fromValue(configuredDiscoveryMode);
            if (explicitMode == DiscoveryMode.FILESYSTEM_FOLDER && pluginPackageDirectory.isBlank()) {
                throw new IllegalStateException(
                        "Runtime plugin package discovery mode 'filesystem-folder' requires npdev.runtime.plugin-package-directory"
                );
            }
            return explicitMode;
        }
        if (!pluginPackageDirectory.isBlank()) {
            return DiscoveryMode.FILESYSTEM_FOLDER;
        }
        if (PROJECTED_RESOURCE_LOCATION.equalsIgnoreCase(discoveryLocation)) {
            return DiscoveryMode.PROJECTED_RESOURCE;
        }
        return DiscoveryMode.CLASSPATH_INDEX;
    }

    private static boolean looksLikeFilesystemPath(String pathValue) {
        String normalized = pathValue == null ? "" : pathValue.trim();
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.startsWith("classpath:")) {
            return false;
        }
        if (normalized.startsWith("file:")) {
            return true;
        }
        return Files.exists(Path.of(normalized))
                || normalized.contains(":\\")
                || normalized.startsWith("\\\\");
    }

    private static String normalizeDiscoveryLocation(String discoveryLocation) {
        if (discoveryLocation == null || discoveryLocation.isBlank()) {
            throw new IllegalArgumentException("discoveryLocation must be non-blank");
        }
        if (looksLikeFilesystemPath(discoveryLocation)) {
            return Path.of(discoveryLocation.trim()).toAbsolutePath().normalize().toString();
        }
        return normalizeClasspathResourcePath(discoveryLocation);
    }

    private static String normalizeOptionalIndexResourcePath(String indexResourcePath) {
        if (indexResourcePath == null || indexResourcePath.isBlank()) {
            return null;
        }
        if (looksLikeFilesystemPath(indexResourcePath)) {
            return Path.of(indexResourcePath.trim()).toAbsolutePath().normalize().toString();
        }
        return normalizeClasspathResourcePath(indexResourcePath);
    }

    private static String normalizeOptionalDiscoveryMode(String discoveryModeHint) {
        if (discoveryModeHint == null || discoveryModeHint.isBlank()) {
            return "";
        }
        return discoveryModeHint.trim().toLowerCase(Locale.ROOT);
    }

    private static String fileName(String pathValue) {
        String normalized = pathValue == null ? "" : pathValue.trim();
        int slashIndex = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        return slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
    }

    public record DiscoveryResult(
            String discoveryMode,
            String discoveryLocation,
            String indexResourcePath,
            List<DiscoveredPackageCandidate> candidates
    ) {

        public DiscoveryResult {
            discoveryMode = Objects.requireNonNull(discoveryMode, "discoveryMode").trim().toLowerCase(Locale.ROOT);
            discoveryLocation = normalizeDiscoveryLocation(discoveryLocation);
            indexResourcePath = normalizeOptionalIndexResourcePath(indexResourcePath);
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        }
    }

    public record DiscoveredPackageCandidate(
            String discoveryLocation,
            String resourcePath,
            String derivedPackageId
    ) {

        public DiscoveredPackageCandidate {
            discoveryLocation = normalizeDiscoveryLocation(discoveryLocation);
            resourcePath = normalizeCandidatePath(resourcePath);
            derivedPackageId = Objects.requireNonNullElse(derivedPackageId, "").trim();
            if (derivedPackageId.isBlank()) {
                throw new IllegalArgumentException("derivedPackageId must be non-blank");
            }
        }
    }

    private enum DiscoveryMode {
        FILESYSTEM_FOLDER(FILESYSTEM_PLUGIN_FOLDER_MODE),
        CLASSPATH_INDEX("classpath-index"),
        PROJECTED_RESOURCE("projected-resource");

        private final String value;

        DiscoveryMode(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }

        static DiscoveryMode fromValue(String value) {
            String normalized = normalizeOptionalDiscoveryMode(value);
            for (DiscoveryMode candidate : values()) {
                if (candidate.value.equalsIgnoreCase(normalized)) {
                    return candidate;
                }
            }
            throw new IllegalStateException("Unsupported runtime plugin package discovery mode: " + value);
        }
    }
}
