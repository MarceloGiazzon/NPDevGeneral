package com.finalexec.npdev.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record RuntimePluginManifest(
        String manifestPath,
        String manifestVersion,
        List<PluginContribution> plugins
) {

    public RuntimePluginManifest {
        manifestPath = normalizeRequired(manifestPath, "manifestPath");
        manifestVersion = normalizeRequired(manifestVersion, "manifestVersion");
        plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
    }

    public static RuntimePluginManifest fromJson(String manifestPath, JsonNode root) {
        Objects.requireNonNull(root, "root");

        List<PluginContribution> plugins = new ArrayList<>();
        for (JsonNode pluginNode : root.path("plugins")) {
            List<AdapterContribution> adapters = new ArrayList<>();
            for (JsonNode adapterNode : pluginNode.path("adapters")) {
                JsonNode implementationNode = adapterNode.path("implementation");
                adapters.add(new AdapterContribution(
                        adapterNode.path("capability").asText(),
                        adapterNode.path("operation").asText(),
                        adapterNode.path("adapterId").asText(),
                        adapterNode.path("bindingKey").asText(),
                        new ImplementationRef(
                                implementationNode.path("kind").asText(),
                                implementationNode.path("ref").asText()
                        )
                ));
            }
            plugins.add(new PluginContribution(
                    pluginNode.path("pluginId").asText(),
                    pluginNode.path("displayName").asText(),
                    pluginNode.path("version").asText(),
                    pluginNode.path("enabled").asBoolean(false),
                    adapters
            ));
        }
        return new RuntimePluginManifest(manifestPath, root.path("manifestVersion").asText(), plugins);
    }

    public Summary toSummary() {
        List<PluginSummary> pluginSummaries = plugins.stream()
                .map(PluginContribution::toSummary)
                .toList();
        List<String> activeAdapterIds = pluginSummaries.stream()
                .filter(PluginSummary::enabled)
                .flatMap(plugin -> plugin.adapters().stream())
                .map(AdapterSummary::adapterId)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        ids -> ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()
        ));
        return new Summary(manifestPath, manifestVersion, pluginSummaries, activeAdapterIds);
    }

    public List<AdapterContribution> enabledAdapterContributions() {
        return plugins.stream()
                .filter(PluginContribution::enabled)
                .flatMap(plugin -> plugin.adapters().stream())
                .sorted(Comparator.comparing(AdapterContribution::bindingKey, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public record PluginContribution(
            String pluginId,
            String displayName,
            String version,
            boolean enabled,
            List<AdapterContribution> adapters
    ) {

        public PluginContribution {
            pluginId = normalizeRequired(pluginId, "pluginId");
            displayName = normalizeRequired(displayName, "displayName");
            version = normalizeRequired(version, "version");
            adapters = List.copyOf(Objects.requireNonNull(adapters, "adapters"));
        }

        public PluginSummary toSummary() {
            return new PluginSummary(
                    pluginId,
                    displayName,
                    version,
                    enabled,
                    adapters.stream()
                            .map(AdapterContribution::toSummary)
                            .sorted(Comparator.comparing(AdapterSummary::bindingKey, String.CASE_INSENSITIVE_ORDER))
                            .toList()
            );
        }
    }

    public record AdapterContribution(
            String capability,
            String operation,
            String adapterId,
            String bindingKey,
            ImplementationRef implementation
    ) {

        public AdapterContribution {
            capability = normalizeRequired(capability, "capability").toLowerCase(Locale.ROOT);
            operation = normalizeRequired(operation, "operation").toLowerCase(Locale.ROOT);
            adapterId = normalizeRequired(adapterId, "adapterId");
            bindingKey = normalizeRequired(bindingKey, "bindingKey").toLowerCase(Locale.ROOT);
            implementation = Objects.requireNonNull(implementation, "implementation");
        }

        public AdapterSummary toSummary() {
            return new AdapterSummary(capability, operation, adapterId, bindingKey);
        }
    }

    public record ImplementationRef(String kind, String ref) {

        public ImplementationRef {
            kind = normalizeRequired(kind, "kind");
            ref = normalizeRequired(ref, "ref");
        }
    }

    public record Summary(
            String manifestPath,
            String manifestVersion,
            List<PluginSummary> plugins,
            List<String> activeAdapterIds
    ) {

        public Summary {
            manifestPath = normalizeRequired(manifestPath, "manifestPath");
            manifestVersion = normalizeRequired(manifestVersion, "manifestVersion");
            plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
            activeAdapterIds = List.copyOf(Objects.requireNonNull(activeAdapterIds, "activeAdapterIds"));
        }
    }

    public record PluginSummary(
            String pluginId,
            String displayName,
            String version,
            boolean enabled,
            List<AdapterSummary> adapters
    ) {

        public PluginSummary {
            pluginId = normalizeRequired(pluginId, "pluginId");
            displayName = normalizeRequired(displayName, "displayName");
            version = normalizeRequired(version, "version");
            adapters = List.copyOf(Objects.requireNonNull(adapters, "adapters"));
        }
    }

    public record AdapterSummary(
            String capability,
            String operation,
            String adapterId,
            String bindingKey
    ) {

        public AdapterSummary {
            capability = normalizeRequired(capability, "capability").toLowerCase(Locale.ROOT);
            operation = normalizeRequired(operation, "operation").toLowerCase(Locale.ROOT);
            adapterId = normalizeRequired(adapterId, "adapterId");
            bindingKey = normalizeRequired(bindingKey, "bindingKey").toLowerCase(Locale.ROOT);
        }
    }

    private static String normalizeRequired(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + " must be non-blank");
        }
        return normalized;
    }
}
