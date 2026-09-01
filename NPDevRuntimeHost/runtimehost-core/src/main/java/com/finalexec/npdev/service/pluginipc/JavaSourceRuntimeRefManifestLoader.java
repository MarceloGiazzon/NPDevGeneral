package com.finalexec.npdev.service.pluginipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reads {@code npdev/plugin-runtime/java-source-runtime-refs.json}, the manifest
 * {@code RuntimeApiEmitter.emitJavaSourceRuntimeRefManifestIfNeeded} (NPDevGenerator) writes.
 * Absent resource = no {@code plugin:java-source} mounts = an empty manifest, the same
 * graceful-absence convention {@code PluginBytecodeBootGate} uses for its own optional manifest
 * (NOT {@code RuntimePluginManifestLoader}'s schema-validated fail-fast style -- this manifest is
 * additive, never required for an app to boot).
 */
public final class JavaSourceRuntimeRefManifestLoader {

    /**
     * Must equal {@code RuntimeApiEmitter.emitJavaSourceRuntimeRefManifestIfNeeded}'s destination
     * (NPDevGenerator) -- npdev-java-source-runtime-refs twin-pair rule
     * (scripts/quality/twin-pair-registry.json) pins the two literals together.
     */
    static final String MANIFEST_RESOURCE = "npdev/plugin-runtime/java-source-runtime-refs.json";

    private final ObjectMapper objectMapper;
    private final ClassLoader classLoader;

    public JavaSourceRuntimeRefManifestLoader(ObjectMapper objectMapper) {
        this(objectMapper, Thread.currentThread().getContextClassLoader());
    }

    /** Testable seam: a custom classloader over a temp resources tree. */
    public JavaSourceRuntimeRefManifestLoader(ObjectMapper objectMapper, ClassLoader classLoader) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    public JavaSourceRuntimeRefManifest load() {
        try (InputStream in = classLoader.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (in == null) {
                return JavaSourceRuntimeRefManifest.empty();
            }
            JsonNode root = objectMapper.readTree(in);
            return parse(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + MANIFEST_RESOURCE, exception);
        }
    }

    private static JavaSourceRuntimeRefManifest parse(JsonNode root) {
        Map<String, JavaSourceRuntimeRefManifest.Entry> byRuntimeRef = new LinkedHashMap<>();
        for (JsonNode entryNode : root.path("javaSourcePlugins")) {
            Map<String, String> methodByOperation = new LinkedHashMap<>();
            entryNode.path("methodByOperation").fields()
                    .forEachRemaining(field -> methodByOperation.put(field.getKey(), field.getValue().asText()));
            JavaSourceRuntimeRefManifest.Entry entry = new JavaSourceRuntimeRefManifest.Entry(
                    entryNode.path("capability").asText(),
                    entryNode.path("capabilityType").asText(),
                    entryNode.path("adapterId").asText(),
                    entryNode.path("pluginId").asText(),
                    entryNode.path("runtimeRef").asText(),
                    entryNode.path("mainClass").asText(),
                    methodByOperation
            );
            byRuntimeRef.put(entry.runtimeRef(), entry);
        }
        return new JavaSourceRuntimeRefManifest(byRuntimeRef);
    }
}
