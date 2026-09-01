package com.finalexec.npdev.service.pluginipc;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SEC-3 Model B (docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md): the runtime-side twin of
 * {@code RuntimeApiEmitter.emitJavaSourceRuntimeRefManifestIfNeeded} (NPDevGenerator) -- the data
 * form of a {@code plugin:java-source} mount's FQCN + operation->method map, keyed by runtimeRef,
 * that a fungible pooled worker (PluginIpcChildProcessPool) needs to classload and invoke a real
 * generated plugin. An empty manifest (no entries) is the "no java-source mounts" no-op signal,
 * matching this app's other optional/additive manifests.
 */
public record JavaSourceRuntimeRefManifest(Map<String, Entry> byRuntimeRef) {

    public JavaSourceRuntimeRefManifest {
        byRuntimeRef = Map.copyOf(Objects.requireNonNull(byRuntimeRef, "byRuntimeRef"));
    }

    public static JavaSourceRuntimeRefManifest empty() {
        return new JavaSourceRuntimeRefManifest(Map.of());
    }

    public Optional<Entry> entryForRuntimeRef(String runtimeRef) {
        return Optional.ofNullable(byRuntimeRef.get(runtimeRef));
    }

    /**
     * A {@link com.npdev.kernel.CapabilityCall} never carries a plugin's runtimeRef (its
     * {@code adapterId} is the generic {@code "plugin:java-source"}, shared by every java-source
     * mount) -- only {@code capability}, which this platform already treats as unique per app
     * (the same assumption {@code RuntimePluginAdapterRegistry}'s own capability-keyed indexes
     * make). Built lazily rather than as a second stored field so the manifest stays a single
     * source of truth.
     */
    public Optional<Entry> entryForCapability(String capability) {
        return byRuntimeRef.values().stream()
                .filter(entry -> entry.capability().equalsIgnoreCase(capability))
                .findFirst();
    }

    public record Entry(
            String capability,
            String capabilityType,
            String adapterId,
            String pluginId,
            String runtimeRef,
            String mainClass,
            Map<String, String> methodByOperation
    ) {

        public Entry {
            capability = Objects.requireNonNull(capability, "capability");
            capabilityType = Objects.requireNonNull(capabilityType, "capabilityType");
            adapterId = Objects.requireNonNull(adapterId, "adapterId");
            pluginId = Objects.requireNonNull(pluginId, "pluginId");
            runtimeRef = Objects.requireNonNull(runtimeRef, "runtimeRef");
            mainClass = Objects.requireNonNull(mainClass, "mainClass");
            methodByOperation = Map.copyOf(Objects.requireNonNull(methodByOperation, "methodByOperation"));
        }
    }
}
