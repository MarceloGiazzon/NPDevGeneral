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
    private final Map<String, RegisteredAdapterContribution> byPluginCapabilityOperation;
    private final Map<String, RegisteredAdapterContribution> byPluginCapability;

    public RuntimePluginAdapterRegistry(RuntimePluginManifest manifest) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.byCapabilityOperationAdapter = indexByCapabilityOperationAdapter(manifest);
        this.byCapabilityAdapter = indexByCapabilityAdapter(manifest);
        this.byPluginCapabilityOperation = indexByPluginCapabilityOperation(manifest);
        this.byPluginCapability = indexByPluginCapability(manifest);
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

    /**
     * Looks up whether {@code pluginId}'s manifest entry declares a binding for
     * {@code capability}/{@code operation} -- the callback allowlist check
     * {@code PluginExecutionPolicyEvaluator.evaluateCallback} needs: a plugin process may only call
     * back into capabilities its OWN manifest entry declares it needs, not an arbitrary capability
     * (SEC-3 / Model B, docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 1). Falls back to
     * a capability-only match (ignoring operation) the same way {@link #requireContribution} does, so a
     * plugin declared against one operation of a capability is not falsely denied a sibling operation
     * of that same capability. Returns {@code null} (not a thrown exception) when nothing matches --
     * callers decide what "not declared" means for their own error shape.
     */
    public RegisteredAdapterContribution findDeclaredCallback(String pluginId, String capability, String operation) {
        String normalizedPluginId = normalize(pluginId);
        String normalizedCapability = normalize(capability);
        String normalizedOperation = normalize(operation);

        if (!normalizedOperation.isBlank()) {
            RegisteredAdapterContribution exact = byPluginCapabilityOperation.get(
                    key(normalizedPluginId, normalizedCapability, normalizedOperation)
            );
            if (exact != null) {
                return exact;
            }
        }
        return byPluginCapability.get(key(normalizedPluginId, normalizedCapability));
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

    private static Map<String, RegisteredAdapterContribution> indexByPluginCapabilityOperation(
            RuntimePluginManifest manifest
    ) {
        Map<String, RegisteredAdapterContribution> index = new LinkedHashMap<>();
        for (RuntimePluginManifest.PluginContribution plugin : manifest.plugins()) {
            if (!plugin.enabled()) {
                continue;
            }
            for (RuntimePluginManifest.AdapterContribution contribution : plugin.adapters()) {
                RegisteredAdapterContribution registered = RegisteredAdapterContribution.from(plugin, contribution);
                index.putIfAbsent(
                        key(registered.pluginId(), registered.capability(), registered.operation()),
                        registered
                );
            }
        }
        return Map.copyOf(index);
    }

    private static Map<String, RegisteredAdapterContribution> indexByPluginCapability(RuntimePluginManifest manifest) {
        Map<String, RegisteredAdapterContribution> index = new LinkedHashMap<>();
        for (RuntimePluginManifest.PluginContribution plugin : manifest.plugins()) {
            if (!plugin.enabled()) {
                continue;
            }
            for (RuntimePluginManifest.AdapterContribution contribution : plugin.adapters()) {
                RegisteredAdapterContribution registered = RegisteredAdapterContribution.from(plugin, contribution);
                index.putIfAbsent(key(registered.pluginId(), registered.capability()), registered);
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
