package com.finalexec.npdev.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// verifier-token: class\s+RuntimePluginProfileDiagnostics
public final class RuntimePluginProfileDiagnostics {

    private final String activeProfile;
    private final String selectionMode;
    private final String bindingsManifestPath;
    private final String pluginManifestPath;
    private final String runtimeEnvironment;
    private final List<String> admittedAdapterIds;
    private final Map<String, String> selectedAdapterIds;
    private final List<String> unresolvedCapabilities;
    private final List<String> missingAdapterIds;
    private final Map<String, Object> policySummary;

    public RuntimePluginProfileDiagnostics(
            String activeProfile,
            String selectionMode,
            String bindingsManifestPath,
            String pluginManifestPath,
            String runtimeEnvironment,
            List<String> admittedAdapterIds,
            Map<String, String> selectedAdapterIds,
            List<String> unresolvedCapabilities,
            List<String> missingAdapterIds,
            Map<String, Object> policySummary
    ) {
        this.activeProfile = normalize(activeProfile);
        this.selectionMode = normalize(selectionMode);
        this.bindingsManifestPath = normalizePath(bindingsManifestPath);
        this.pluginManifestPath = normalizePath(pluginManifestPath);
        this.runtimeEnvironment = normalize(runtimeEnvironment);
        this.admittedAdapterIds = List.copyOf(Objects.requireNonNull(admittedAdapterIds, "admittedAdapterIds"));
        this.selectedAdapterIds = Map.copyOf(Objects.requireNonNull(selectedAdapterIds, "selectedAdapterIds"));
        this.unresolvedCapabilities = List.copyOf(Objects.requireNonNull(unresolvedCapabilities, "unresolvedCapabilities"));
        this.missingAdapterIds = List.copyOf(Objects.requireNonNull(missingAdapterIds, "missingAdapterIds"));
        this.policySummary = Map.copyOf(Objects.requireNonNull(policySummary, "policySummary"));
    }

    public String activeProfile() {
        return activeProfile;
    }

    public String selectionMode() {
        return selectionMode;
    }

    public String bindingsManifestPath() {
        return bindingsManifestPath;
    }

    public String pluginManifestPath() {
        return pluginManifestPath;
    }

    public String runtimeEnvironment() {
        return runtimeEnvironment;
    }

    public List<String> admittedAdapterIds() {
        return admittedAdapterIds;
    }

    public Map<String, String> selectedAdapterIds() {
        return selectedAdapterIds;
    }

    public List<String> unresolvedCapabilities() {
        return unresolvedCapabilities;
    }

    public List<String> missingAdapterIds() {
        return missingAdapterIds;
    }

    public Map<String, Object> policySummary() {
        return policySummary;
    }

    public void assertCoherent() {
        if (unresolvedCapabilities.isEmpty() && missingAdapterIds.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                "Runtime plugin deployment mismatch. deploymentProfile='%s', selectionMode='%s', bindingManifest='%s', pluginManifest='%s', runtimeEnvironment='%s', unresolvedCapabilities=%s, missingAdapterIds=%s. Align the runtime selection with npdev.runtime.deployment-profile or set coherent explicit manifest paths with npdev.runtime.deployment.bindings-manifest and npdev.runtime.deployment.plugin-manifest."
                        .formatted(
                                activeProfile,
                                selectionMode,
                                bindingsManifestPath,
                                pluginManifestPath,
                                runtimeEnvironment,
                                unresolvedCapabilities,
                                missingAdapterIds
                        )
        );
    }

    public Map<String, Object> toSummary() {
        Map<String, Object> selectedManifestPaths = new LinkedHashMap<>();
        selectedManifestPaths.put("bindingsManifestPath", bindingsManifestPath);
        selectedManifestPaths.put("pluginManifestPath", pluginManifestPath);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("activeProfile", activeProfile);
        summary.put("deploymentProfile", activeProfile);
        summary.put("selectionMode", selectionMode);
        summary.put("bindingManifestPath", bindingsManifestPath);
        summary.put("bindingsManifestPath", bindingsManifestPath);
        summary.put("pluginManifestPath", pluginManifestPath);
        summary.put("selectedManifestPaths", Map.copyOf(selectedManifestPaths));
        summary.put("runtimeEnvironment", runtimeEnvironment);
        summary.put("admittedAdapterIds", admittedAdapterIds);
        summary.put("selectedAdapterIds", selectedAdapterIds);
        summary.put("unresolvedCapabilities", unresolvedCapabilities);
        summary.put("missingAdapterIds", missingAdapterIds);
        summary.put("policy", policySummary);
        return Map.copyOf(summary);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String normalizePath(String value) {
        return value == null ? "" : value.trim();
    }
}
