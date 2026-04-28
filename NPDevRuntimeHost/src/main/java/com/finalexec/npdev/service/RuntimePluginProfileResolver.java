package com.finalexec.npdev.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class RuntimePluginProfileResolver {

    private static final String DEFAULT_PROFILE = "default";

    private final String deploymentProfile;
    private final String explicitBindingsManifest;
    private final String explicitPluginManifest;
    private final String explicitExecutionEnvironment;

    public RuntimePluginProfileResolver(
            String deploymentProfile,
            String explicitBindingsManifest,
            String explicitPluginManifest,
            String explicitExecutionEnvironment
    ) {
        this.deploymentProfile = deploymentProfile;
        this.explicitBindingsManifest = explicitBindingsManifest;
        this.explicitPluginManifest = explicitPluginManifest;
        this.explicitExecutionEnvironment = explicitExecutionEnvironment;
    }

    public ResolvedRuntimePluginProfile resolve() {
        String normalizedProfile = normalizeProfile(deploymentProfile);
        ProfileResources mappedResources = resolveProfileResources(normalizedProfile);
        String selectionMode = hasExplicitManifestSelection() ? "explicit" : "profile-fallback";

        if (mappedResources == null && !hasExplicitManifestSelection()) {
            throw new IllegalStateException(
                    "Unknown deployment plugin profile '%s'. Supported fallback profiles: %s. Set explicit manifest paths with npdev.runtime.deployment.bindings-manifest and npdev.runtime.deployment.plugin-manifest to bypass profile fallback."
                            .formatted(deploymentProfile, profileMappings().keySet())
            );
        }

        String bindingsManifestPath = normalizeResourcePath(
                explicitBindingsManifest,
                mappedResources == null ? "" : mappedResources.bindingsManifestPath()
        );
        String pluginManifestPath = normalizeResourcePath(
                explicitPluginManifest,
                mappedResources == null ? "" : mappedResources.pluginManifestPath()
        );

        assertClasspathResourceExists(bindingsManifestPath, "binding manifest", normalizedProfile);
        assertClasspathResourceExists(pluginManifestPath, "plugin manifest", normalizedProfile);

        return new ResolvedRuntimePluginProfile(
                normalizedProfile,
                normalizeExecutionEnvironment(
                        normalizedProfile,
                        explicitExecutionEnvironment,
                        bindingsManifestPath
                ),
                selectionMode,
                bindingsManifestPath,
                pluginManifestPath,
                hasExplicitManifestSelection()
        );
    }

    private boolean hasExplicitManifestSelection() {
        return !isBlank(explicitBindingsManifest) || !isBlank(explicitPluginManifest);
    }

    private static void assertClasspathResourceExists(String resourcePath, String label, String profile) {
        String normalized = normalizeResourcePath(resourcePath, "");
        ClassLoader classLoader = RuntimePluginProfileResolver.class.getClassLoader();
        if (classLoader.getResource(normalized) == null) {
            throw new IllegalStateException(
                    "Selected plugin profile '%s' points to missing %s '%s'"
                            .formatted(profile, label, normalized)
            );
        }
    }

    private static Map<String, ProfileResources> profileMappings() {
        Map<String, ProfileResources> profiles = new LinkedHashMap<>();
        profiles.put(DEFAULT_PROFILE, new ProfileResources(
                "npdev/bindings/dev.bindings.json",
                "npdev/plugins/default.plugin-manifest.json"
        ));
        profiles.put("warning", new ProfileResources(
                "npdev/bindings/alt.bindings.json",
                "npdev/plugins/warning.plugin-manifest.json"
        ));
        return Map.copyOf(profiles);
    }

    private static ProfileResources resolveProfileResources(String profile) {
        ProfileResources inferred = inferProfileResources(profile);
        if (inferred != null) {
            return inferred;
        }
        return profileMappings().get(profile);
    }

    private static ProfileResources inferProfileResources(String profile) {
        String normalized = normalizeProfile(profile);
        String bindingsManifestPath = "npdev/bindings/%s.bindings.json".formatted(normalized);
        String pluginManifestPath = "npdev/plugins/%s.plugin-manifest.json".formatted(normalized);
        ClassLoader classLoader = RuntimePluginProfileResolver.class.getClassLoader();
        if (classLoader.getResource(bindingsManifestPath) != null && classLoader.getResource(pluginManifestPath) != null) {
            return new ProfileResources(bindingsManifestPath, pluginManifestPath);
        }
        return null;
    }

    private static String normalizeProfile(String profile) {
        String normalized = profile == null || profile.isBlank() ? DEFAULT_PROFILE : profile.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return DEFAULT_PROFILE;
        }
        return normalized;
    }

    private static String normalizeExecutionEnvironment(
            String normalizedProfile,
            String explicitExecutionEnvironment,
            String bindingsManifestPath
    ) {
        if (!isBlank(explicitExecutionEnvironment)) {
            return explicitExecutionEnvironment.trim().toLowerCase(Locale.ROOT);
        }
        String inferredFromBindings = inferExecutionEnvironmentFromBindingsManifest(bindingsManifestPath);
        if (!inferredFromBindings.isBlank()) {
            return inferredFromBindings;
        }
        return normalizedProfile;
    }

    private static String inferExecutionEnvironmentFromBindingsManifest(String bindingsManifestPath) {
        String normalized = normalizeResourcePath(bindingsManifestPath, "");
        int lastSlash = normalized.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        String suffix = ".bindings.json";
        if (fileName.endsWith(suffix) && fileName.length() > suffix.length()) {
            return fileName.substring(0, fileName.length() - suffix.length()).trim().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private static String normalizeResourcePath(String resourcePath, String fallback) {
        String candidate = isBlank(resourcePath) ? fallback : resourcePath;
        if (isBlank(candidate)) {
            throw new IllegalArgumentException("resourcePath must resolve to a non-blank classpath resource");
        }
        String normalized = candidate.trim();
        if (normalized.startsWith("classpath:")) {
            normalized = normalized.substring("classpath:".length());
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ProfileResources(
            String bindingsManifestPath,
            String pluginManifestPath
    ) {
    }

    public record ResolvedRuntimePluginProfile(
            String activeProfile,
            String executionEnvironment,
            String selectionMode,
            String bindingsManifestPath,
            String pluginManifestPath,
            boolean expertOverrideActive
    ) {

        public ResolvedRuntimePluginProfile {
            activeProfile = Objects.requireNonNull(activeProfile, "activeProfile").trim().toLowerCase(Locale.ROOT);
            executionEnvironment = Objects.requireNonNull(executionEnvironment, "executionEnvironment").trim().toLowerCase(Locale.ROOT);
            selectionMode = Objects.requireNonNull(selectionMode, "selectionMode").trim().toLowerCase(Locale.ROOT);
            bindingsManifestPath = Objects.requireNonNull(bindingsManifestPath, "bindingsManifestPath").trim();
            pluginManifestPath = Objects.requireNonNull(pluginManifestPath, "pluginManifestPath").trim();
        }
    }
}
