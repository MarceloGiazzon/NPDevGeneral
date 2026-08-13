package com.finalexec.npdev.service;

import com.finalexec.npdev.model.RuntimePluginManifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class RuntimePluginAdapterRegistry {

    private final RuntimePluginManifest manifest;
    private final Map<String, RegisteredAdapterContribution> byCapabilityOperationAdapter;
    private final Map<String, RegisteredAdapterContribution> byCapabilityAdapter;

    public RuntimePluginAdapterRegistry(RuntimePluginManifest manifest) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.byCapabilityOperationAdapter = indexByCapabilityOperationAdapter(manifest);
        this.byCapabilityAdapter = indexByCapabilityAdapter(manifest);
    }

    public RegisteredAdapterContribution requireContribution(String capability, String adapterId) {
        String key = key(normalize(capability), normalize(adapterId));
        RegisteredAdapterContribution contribution = byCapabilityAdapter.get(key);
        if (contribution != null) {
            return contribution;
        }
        throw unregisteredAdapter(capability, "", adapterId);
    }

    public RegisteredAdapterContribution requireContribution(String capability, String operation, String adapterId) {
        String normalizedCapability = normalize(capability);
        String normalizedAdapterId = normalize(adapterId);
        String normalizedOperation = normalize(operation);

        if (!normalizedOperation.isBlank()) {
            RegisteredAdapterContribution exact = byCapabilityOperationAdapter.get(
                    key(normalizedCapability, normalizedOperation, normalizedAdapterId)
            );
            if (exact != null) {
                return exact;
            }
        }

        RegisteredAdapterContribution fallback = byCapabilityAdapter.get(key(normalizedCapability, normalizedAdapterId));
        if (fallback != null) {
            return fallback;
        }
        throw unregisteredAdapter(capability, operation, adapterId);
    }

    public Summary toSummary() {
        return new Summary(manifest.manifestPath(), byCapabilityOperationAdapter.values().stream()
                .map(RegisteredAdapterContribution::toSummary)
                .toList());
    }

    private IllegalStateException unregisteredAdapter(String capability, String operation, String adapterId) {
        String opText = operation == null || operation.isBlank() ? "<binding>" : operation;
        return new IllegalStateException(
                "Adapter '%s' for capability '%s' operation '%s' is not declared in active plugin manifest '%s'"
                        .formatted(adapterId, capability, opText, manifest.manifestPath())
        );
    }

    private static Map<String, RegisteredAdapterContribution> indexByCapabilityOperationAdapter(
            RuntimePluginManifest manifest
    ) {
        Map<String, RegisteredAdapterContribution> index = new LinkedHashMap<>();
        for (RuntimePluginManifest.PluginContribution plugin : manifest.plugins()) {
            if (!plugin.enabled()) {
                continue;
            }
            for (RuntimePluginManifest.AdapterContribution contribution : plugin.adapters()) {
                RegisteredAdapterContribution registered = RegisteredAdapterContribution.from(plugin, contribution);
                String key = key(registered.capability(), registered.operation(), registered.adapterId());
                if (index.putIfAbsent(key, registered) != null) {
                    throw new IllegalStateException("Duplicate runtime plugin adapter contribution: " + key);
                }
            }
        }
        return Map.copyOf(index);
    }

    private static Map<String, RegisteredAdapterContribution> indexByCapabilityAdapter(
            RuntimePluginManifest manifest
    ) {
        Map<String, RegisteredAdapterContribution> index = new LinkedHashMap<>();
        for (RuntimePluginManifest.PluginContribution plugin : manifest.plugins()) {
            if (!plugin.enabled()) {
                continue;
            }
            for (RuntimePluginManifest.AdapterContribution contribution : plugin.adapters()) {
                RegisteredAdapterContribution registered = RegisteredAdapterContribution.from(plugin, contribution);
                index.putIfAbsent(key(registered.capability(), registered.adapterId()), registered);
            }
        }
        return Map.copyOf(index);
    }

    private static String key(String... values) {
        return String.join("|", values);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record RegisteredAdapterContribution(
            String pluginId,
            String pluginVersion,
            String capability,
            String operation,
            String adapterId,
            String bindingKey,
            String implementationKind,
            String runtimeRef
    ) {

        public RegisteredAdapterContribution {
            pluginId = normalize(pluginId);
            pluginVersion = pluginVersion == null ? "" : pluginVersion.trim();
            capability = normalize(capability);
            operation = normalize(operation);
            adapterId = normalize(adapterId);
            bindingKey = normalize(bindingKey);
            implementationKind = normalize(implementationKind);
            runtimeRef = runtimeRef == null ? "" : runtimeRef.trim();
        }

        static RegisteredAdapterContribution from(
                RuntimePluginManifest.PluginContribution plugin,
                RuntimePluginManifest.AdapterContribution contribution
        ) {
            return new RegisteredAdapterContribution(
                    plugin.pluginId(),
                    plugin.version(),
                    contribution.capability(),
                    contribution.operation(),
                    contribution.adapterId(),
                    contribution.bindingKey(),
                    contribution.implementation().kind(),
                    contribution.implementation().ref()
            );
        }

        SummaryItem toSummary() {
            return new SummaryItem(pluginId, capability, operation, adapterId, bindingKey, implementationKind);
        }
    }

    public record Summary(String manifestPath, List<SummaryItem> contributions) {
        public Summary {
            manifestPath = manifestPath == null ? "" : manifestPath.trim();
            contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));
        }
    }

    public record SummaryItem(
            String pluginId,
            String capability,
            String operation,
            String adapterId,
            String bindingKey,
            String implementationKind
    ) {
    }
}
