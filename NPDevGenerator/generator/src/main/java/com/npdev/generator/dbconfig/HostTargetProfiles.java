package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The hosting-target-profile registry, read from {@code npdev/hosting-targets.json} on the
 * classpath -- a build-time copy of {@code scripts/policy/hosting-targets.json} (H2), the same
 * "data, not code" shape {@link DockerEngineProfiles} already uses for per-DB-engine facts.
 *
 * <p>Unlike a database engine, an app can perfectly well have NO target selected (rung 0-2), so
 * {@link #of(String)} returns an {@link Optional} rather than throwing -- "no target" is an
 * ordinary state here, not a configuration error.
 */
public final class HostTargetProfiles {

    private static final String RESOURCE = "npdev/hosting-targets.json";
    private static final Map<String, HostTargetProfile> BY_ID = load();

    private HostTargetProfiles() {
    }

    public static Optional<HostTargetProfile> of(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static List<HostTargetProfile> all() {
        return List.copyOf(BY_ID.values());
    }

    private static Map<String, HostTargetProfile> load() {
        try (InputStream stream = HostTargetProfiles.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "hosting targets resource " + RESOURCE + " is missing from the generator jar. "
                        + "Without it `npdev host deploy` cannot resolve any external target.");
            }
            JsonNode root = new ObjectMapper().readTree(stream);
            Map<String, HostTargetProfile> out = new LinkedHashMap<>();
            for (JsonNode node : root.path("targets")) {
                HostTargetProfile profile = parse(node);
                out.put(profile.id(), profile);
            }
            return Map.copyOf(out);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not read " + RESOURCE, failure);
        }
    }

    private static HostTargetProfile parse(JsonNode node) {
        return new HostTargetProfile(
                node.path("id").asText(),
                node.path("label").asText(),
                node.path("rung").asInt(),
                text(node, "requiresEngine"),
                node.hasNonNull("appMemoryMb") ? node.path("appMemoryMb").asInt() : null,
                node.hasNonNull("sleepsAfterMin") ? node.path("sleepsAfterMin").asInt() : null,
                node.path("databaseKind").asText(),
                node.path("persistentDisk").asBoolean(true),
                text(node, "injectsPortVar"),
                node.path("terminatesTls").asBoolean(true),
                node.path("needsAccount").asBoolean(false),
                text(node, "needsBinary"),
                node.path("fit").asText(),
                stringList(node.path("caveats")));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> out.add(item.asText()));
        }
        return List.copyOf(out);
    }
}
