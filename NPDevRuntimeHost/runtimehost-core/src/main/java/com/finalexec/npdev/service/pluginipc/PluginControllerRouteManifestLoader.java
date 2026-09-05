package com.finalexec.npdev.service.pluginipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads {@code npdev/plugin-runtime/plugin-controller-routes.json}, the manifest
 * {@code RuntimeApiEmitter.emitPluginControllerRouteManifestIfNeeded} (NPDevGenerator) writes.
 * Absent resource = no {@code plugin:java-controller} mounts = an empty manifest, the same
 * graceful-absence convention {@link JavaSourceRuntimeRefManifestLoader} uses for its own manifest.
 *
 * <p>npdev-plugin-controller-route-manifest: twin-pair token (scripts/quality/twin-pair-registry.json)
 * binding this loader's resource path to the emitter's write path and to
 * {@code GeneratedPluginMountPlan}'s AST-derived route extraction (NPDevGenerator).
 */
public final class PluginControllerRouteManifestLoader {

    static final String MANIFEST_RESOURCE = "npdev/plugin-runtime/plugin-controller-routes.json";

    private final ObjectMapper objectMapper;
    private final ClassLoader classLoader;

    public PluginControllerRouteManifestLoader(ObjectMapper objectMapper) {
        this(objectMapper, Thread.currentThread().getContextClassLoader());
    }

    /** Testable seam: a custom classloader over a temp resources tree. */
    public PluginControllerRouteManifestLoader(ObjectMapper objectMapper, ClassLoader classLoader) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    public PluginControllerRouteManifest load() {
        try (InputStream in = classLoader.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (in == null) {
                return PluginControllerRouteManifest.empty();
            }
            JsonNode root = objectMapper.readTree(in);
            return parse(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + MANIFEST_RESOURCE, exception);
        }
    }

    private static PluginControllerRouteManifest parse(JsonNode root) {
        Map<String, PluginControllerRouteManifest.Entry> byCapability = new LinkedHashMap<>();
        for (JsonNode entryNode : root.path("controllerPlugins")) {
            List<PluginControllerRouteManifest.Route> routes = new ArrayList<>();
            for (JsonNode routeNode : entryNode.path("routes")) {
                routes.add(new PluginControllerRouteManifest.Route(
                        routeNode.path("httpMethod").asText(),
                        routeNode.path("path").asText(),
                        routeNode.path("methodName").asText()
                ));
            }
            PluginControllerRouteManifest.Entry entry = new PluginControllerRouteManifest.Entry(
                    entryNode.path("capability").asText(),
                    entryNode.path("controllerClassName").asText(),
                    entryNode.path("basePath").asText(),
                    routes
            );
            byCapability.put(entry.capability(), entry);
        }
        return new PluginControllerRouteManifest(byCapability);
    }
}
