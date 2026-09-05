package com.finalexec.npdev.service.pluginipc;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * B30/SEC-9: the runtime-side twin of {@code RuntimeApiEmitter.emitPluginControllerRouteManifestIfNeeded}
 * (NPDevGenerator) -- the data form of a {@code plugin:java-controller} mount's route table
 * (httpMethod + full path + method name per route), keyed by capability, that
 * {@code PluginControllerProxyHandler} needs to resolve an incoming request to a mount and
 * {@code ManifestDrivenJavaControllerPluginHandler} needs to resolve a matched route to a real
 * method inside the isolated child. An empty manifest (no entries) is the "no controller mounts"
 * no-op signal, matching {@code JavaSourceRuntimeRefManifest}'s own convention.
 */
public record PluginControllerRouteManifest(Map<String, Entry> byCapability) {

    public PluginControllerRouteManifest {
        byCapability = Map.copyOf(Objects.requireNonNull(byCapability, "byCapability"));
    }

    public static PluginControllerRouteManifest empty() {
        return new PluginControllerRouteManifest(Map.of());
    }

    public boolean isEmpty() {
        return byCapability.isEmpty();
    }

    public Optional<Entry> entryForCapability(String capability) {
        return Optional.ofNullable(byCapability.get(capability));
    }

    /**
     * Longest-basePath-prefix match against every mounted controller's basePath -- the same
     * coverage semantics {@code PluginControllerSecurityConfig}'s interceptor already uses
     * ({@code basePath} itself, or anything one level or deeper under it). Longest match wins so
     * two mounts can never both plausibly claim the same request path.
     */
    public Optional<Entry> entryForRequestPath(String path) {
        Entry best = null;
        for (Entry entry : byCapability.values()) {
            boolean withinBasePath = path.equals(entry.basePath()) || path.startsWith(entry.basePath() + "/");
            if (withinBasePath && (best == null || entry.basePath().length() > best.basePath().length())) {
                best = entry;
            }
        }
        return Optional.ofNullable(best);
    }

    public record Entry(
            String capability,
            String controllerClassName,
            String basePath,
            List<Route> routes
    ) {

        public Entry {
            capability = Objects.requireNonNull(capability, "capability");
            controllerClassName = Objects.requireNonNull(controllerClassName, "controllerClassName");
            basePath = Objects.requireNonNull(basePath, "basePath");
            routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
        }
    }

    public record Route(String httpMethod, String path, String methodName) {
    }
}
