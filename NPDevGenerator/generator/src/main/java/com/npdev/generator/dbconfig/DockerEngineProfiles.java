package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine-profile registry, read from {@code npdev/engine-profiles.json} on the classpath.
 *
 * <h2>Data, not code</h2>
 *
 * <p>The provisioning facts live in JSON rather than in a Java switch for the same reason
 * {@code SqlDialects} exists: adding an engine should be adding a row, not editing five scripts.
 * Every {@code quirk} in that file is a MEASURED failure from this project's own CI, not folklore --
 * SQL Server's 30-60s startup, MySQL's utf8mb4, the absent {@code MSSQL_DATABASE}.
 *
 * <h2>It refuses rather than defaults</h2>
 *
 * <p>An engine with no profile throws at generation time, naming the ones that exist. The
 * alternative -- falling back to "probably like Postgres" -- is precisely how MySQL came to be
 * offered by the config schema, the CLI and the Manager while its toolbox threw
 * {@code "Unsupported engine 'MySQL'"} at the user. Every SERVER profile is
 * {@link DockerEngineProfile#validate() validated} on load, so an incomplete one fails the build
 * instead of emitting scripts that report success before the database can serve.
 */
public final class DockerEngineProfiles {

    private static final String RESOURCE = "npdev/engine-profiles.json";
    private static final Map<DatabaseEngine, DockerEngineProfile> BY_ENGINE = load();

    private DockerEngineProfiles() {
    }

    /**
     * The profile for {@code engine}.
     *
     * @throws IllegalStateException naming the known engines -- never a silent Postgres-shaped guess
     */
    public static DockerEngineProfile of(DatabaseEngine engine) {
        DockerEngineProfile profile = BY_ENGINE.get(engine);
        if (profile == null) {
            throw new IllegalStateException(
                    "no engine profile for " + engine + ". Known: " + BY_ENGINE.keySet()
                    + ". An engine without a profile cannot be provisioned, and shipping it anyway is "
                    + "how a user came to be offered MySQL by the schema, the CLI and the Manager "
                    + "while the toolbox threw \"Unsupported engine\" at them.");
        }
        return profile;
    }

    /** Every profile, for the parity gate and for {@code npdev engines}. */
    public static List<DockerEngineProfile> all() {
        return List.copyOf(BY_ENGINE.values());
    }

    private static Map<DatabaseEngine, DockerEngineProfile> load() {
        try (InputStream stream = DockerEngineProfiles.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "engine profiles resource " + RESOURCE + " is missing from the generator jar. "
                        + "Without it no environment toolbox can be emitted for any engine.");
            }
            JsonNode root = new ObjectMapper().readTree(stream);
            JsonNode engines = root.path("engines");
            Map<DatabaseEngine, DockerEngineProfile> out = new LinkedHashMap<>();
            engines.fieldNames().forEachRemaining(name -> {
                DatabaseEngine engine = DatabaseEngine.parse(name);
                DockerEngineProfile profile = parse(engine, engines.path(name));
                profile.validate();
                out.put(engine, profile);
            });
            return Map.copyOf(out);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not read " + RESOURCE, failure);
        }
    }

    private static DockerEngineProfile parse(DatabaseEngine engine, JsonNode node) {
        return new DockerEngineProfile(
                engine,
                DockerEngineProfile.Kind.parse(node.path("kind").asText(null)),
                text(node, "provider"),
                node.hasNonNull("defaultPort") ? node.path("defaultPort").asInt() : null,
                text(node, "image"),
                text(node, "driverClass"),
                text(node, "jdbcUrlTemplate"),
                stringMap(node.path("containerEnv")),
                stringList(node.path("extraRunArgs")),
                node.path("createsDatabaseFromEnv").asBoolean(false),
                probe(node.path("readyProbe")),
                ensureDatabase(node.path("ensureDatabase")),
                text(node, "adminUser"),
                text(node, "guiLabel"),
                stringList(node.path("quirks")),
                text(node, "dataVolumePath"),
                text(node, "composeImage"),
                text(node, "backupCommand"));
    }

    private static DockerEngineProfile.Probe probe(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new DockerEngineProfile.Probe(
                stringList(node.path("exec")),
                node.path("expectExitCode").asInt(0),
                node.path("timeoutSeconds").asInt(0));
    }

    private static DockerEngineProfile.EnsureDatabase ensureDatabase(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new DockerEngineProfile.EnsureDatabase(
                stringList(node.path("listExec")),
                stringList(node.path("createExec")),
                stringMap(node.path("execEnv")));
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

    private static Map<String, String> stringMap(JsonNode node) {
        // Insertion-ordered: this map is serialized into resolved-db-plan.json, and a HashMap's
        // iteration order would make the emitted file differ byte-for-byte between runs -- the
        // GATE-DET-1 non-determinism this repo already has a gate for.
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fieldNames().forEachRemaining(name -> out.put(name, node.path(name).asText()));
        }
        return java.util.Collections.unmodifiableMap(out);
    }
}
