package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.compiled.CompiledModel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Writes {@code _ops/host-plan.json} on EVERY generation (H13/H16) -- the resolved superset of
 * `host.definition.json` (the user's authored decisions, read from beside `db.definition.json` in
 * the app definition directory -- never inside the generated app, since regeneration deletes and
 * re-emits `_ops/` wholesale) merged with facts from the compiled model, {@code config.json} and
 * {@link GeneratedDatabasePlan}. Analogue of how {@code resolved-db-plan.json} resolves
 * {@code db.definition.json}. Never hand-edited; the Python-side twin of this merge is
 * {@code npdev_host.resolve_plan}, used for a preview before/without a build.
 *
 * <p>Runs unconditionally, whether or not `host.definition.json` exists yet -- an app that has
 * never run `npdev host plan` still gets a plan describing its default state: rung 0, reachable by
 * nobody. {@link HostDeploymentEmitter} reads this file's {@code target} to decide whether to write
 * anything at all.
 */
public final class HostPlanEmitter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Path emit(CompiledModel compiled, JsonNode config, Path finalAppRoot, GeneratedDatabasePlan databasePlan)
            throws Exception {
        JsonNode definition = readHostDefinition(databasePlan);

        int rung = definition == null ? 0 : definition.path("rung").asInt(0);
        String targetId = definition == null ? null : text(definition, "target");
        Optional<HostTargetProfile> target = HostTargetProfiles.of(targetId);

        int serverPort = readInt(config, 8080, "runtime", "serverPort");
        String engineName = databasePlan.engine().externalName();

        ObjectNode plan = MAPPER.createObjectNode();
        plan.put("schemaVersion", "npdev-host-plan.v1");
        plan.put("appId", databasePlan.appId());
        plan.put("rung", rung);
        if (targetId != null) {
            plan.put("target", targetId);
        } else {
            plan.putNull("target");
        }

        ObjectNode runsOn = plan.putObject("runsOn");
        runsOn.put("kind", target.map(HostPlanEmitter::runsOnKind).orElse("this-machine"));
        runsOn.put("port", serverPort);
        runsOn.put("portFromEnv", target.map(HostTargetProfile::injectsPortVar).filter(v -> v != null).orElse("SERVER_PORT"));
        Integer memoryMb = target.map(HostTargetProfile::appMemoryMb).orElse(null);
        if (memoryMb != null) {
            runsOn.put("memoryMb", memoryMb);
        } else {
            runsOn.putNull("memoryMb");
        }

        String dataKind = target.map(HostTargetProfile::databaseKind).orElse("this-machine");
        ObjectNode dataLivesOn = plan.putObject("dataLivesOn");
        dataLivesOn.put("kind", dataKind);
        dataLivesOn.put("engine", engineName);
        dataLivesOn.put("databaseName", databasePlan.resolvedDatabaseName());
        boolean survivesRestart;
        boolean survivesMachineDying;
        switch (dataKind) {
            case "ephemeral" -> {
                survivesRestart = false;
                survivesMachineDying = false;
            }
            case "managed" -> {
                survivesRestart = true;
                survivesMachineDying = true;
            }
            default -> {
                survivesRestart = databasePlan.physicalDatabase();
                survivesMachineDying = false;
            }
        }
        dataLivesOn.put("survivesRestart", survivesRestart);
        dataLivesOn.put("survivesThisMachineDying", survivesMachineDying);

        JsonNode reachableByDef = definition == null ? null : definition.get("reachableBy");
        String reachableKind = reachableByDef == null ? "nobody" : text(reachableByDef, "kind", "nobody");
        ObjectNode reachableBy = plan.putObject("reachableBy");
        reachableBy.put("kind", reachableKind);
        putTextOrNull(reachableBy, "provider", reachableByDef == null ? null : text(reachableByDef, "provider"));
        reachableBy.put("through", reachableByDef == null ? "shared-ingress" : text(reachableByDef, "through", "shared-ingress"));
        putTextOrNull(reachableBy, "domain", reachableByDef == null ? null : text(reachableByDef, "domain"));
        reachableBy.put("terminatesTlsElsewhere", !"nobody".equals(reachableKind));

        plan.put("authMode", definition == null ? "apikey" : text(definition, "authMode", "apikey"));

        ArrayNode acknowledged = plan.putArray("acknowledged");
        if (definition != null && definition.has("acknowledged") && definition.get("acknowledged").isArray()) {
            for (JsonNode entry : definition.get("acknowledged")) {
                acknowledged.add(entry.asText());
            }
        }

        plan.set("requiredEnv", requiredEnv(dataKind, engineName, reachableKind, target));

        Path opsDir = finalAppRoot.resolve("_ops");
        Files.createDirectories(opsDir);
        Path out = opsDir.resolve("host-plan.json");
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(plan) + "\n";
        Files.writeString(out, json, StandardCharsets.UTF_8);
        return out;
    }

    private static String runsOnKind(HostTargetProfile target) {
        return "docker".equals(target.needsBinary()) ? "container" : "platform";
    }

    /**
     * Reads {@code host.definition.json} beside {@code db.definition.json} -- SAME directory
     * {@link GeneratedDatabasePlan#definitionPath()} was loaded from. Returns {@code null} when
     * absent (an app that has never run `npdev host plan`); never guessed, never defaulted to a
     * partial object.
     */
    private static JsonNode readHostDefinition(GeneratedDatabasePlan databasePlan) throws Exception {
        if (databasePlan.definitionPath() == null) {
            return null;
        }
        Path candidate = databasePlan.definitionPath().resolveSibling("host.definition.json");
        if (!Files.isRegularFile(candidate)) {
            return null;
        }
        return MAPPER.readTree(candidate.toFile());
    }

    /**
     * The complete environment contract for this plan -- the Java twin of
     * `npdev_host.required_env` (Python). Pinned in sync by twin-pair rule
     * `host-plan-required-env-seams` (H17): both sides must agree on which variables a given
     * (engine, dataKind, reachableKind, target) combination requires, and why.
     */
    private static ObjectNode requiredEnv(String dataKind, String engineName, String reachableKind,
                                           Optional<HostTargetProfile> target) {
        ObjectNode env = MAPPER.createObjectNode();

        if (!"nobody".equals(reachableKind)) {
            ObjectNode entry = env.putObject("NPDEV_AUTH_APIKEYS");
            entry.putNull("value");
            entry.put("source", "you");
            entry.put("why", "An app with no key refuses to boot (fail-closed by design) -- anyone "
                    + "with the link reaches your data without one. Mint with `npdev host keys --new`.");
        }

        String runtimeMode = switch (engineName) {
            case "Postgres" -> "postgres";
            case "MySQL" -> "mysql";
            case "SqlServer" -> "sqlserver";
            default -> null;
        };
        if (runtimeMode != null) {
            ObjectNode springProfiles = env.putObject("SPRING_PROFILES_ACTIVE");
            springProfiles.put("value", "prod," + runtimeMode);
            springProfiles.put("source", "npdev");
            springProfiles.put("why", "Selects which properties file loads. Must agree with "
                    + "NPDEV_RUNTIME_MODE or StartupValidator refuses to boot, naming the missing side.");

            ObjectNode mode = env.putObject("NPDEV_RUNTIME_MODE");
            mode.put("value", runtimeMode);
            mode.put("source", "npdev");
            mode.put("why", "Selects which adapter beans wire. Baked to the engine this app was "
                    + "generated with -- no environment variable changes the engine itself.");

            ObjectNode storageMode = env.putObject("NPDEV_STORAGE_MODE");
            storageMode.put("value", "jdbc");
            storageMode.put("source", "npdev");
            storageMode.put("why", "Required alongside NPDEV_RUNTIME_MODE for any real (non-InMemory) engine.");
        }

        if ("managed".equals(dataKind)) {
            for (String var : new String[] {"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME",
                    "SPRING_DATASOURCE_PASSWORD"}) {
                ObjectNode entry = env.putObject(var);
                entry.putNull("value");
                entry.put("source", "you");
                entry.put("why", "The managed database's own connection details -- this app's local "
                        + "resolved-db-plan.json only describes its LOCAL database, not the one your "
                        + "hosting target provisions.");
            }
        }

        boolean noPersistentDisk = target.isPresent() && !target.get().persistentDisk();
        if (noPersistentDisk) {
            ObjectNode entry = env.putObject("NPDEV_SUPERUSER_BOOTSTRAPKEYHASH");
            entry.putNull("value");
            entry.put("source", "you");
            entry.put("why", "No persistent disk on this target -- SUPER_USER_KEY.txt does not survive "
                    + "a restart. Without this you are locked out of ControlPanel after the first one. "
                    + "Mint the hash with `npdev admin hash-key`.");
        }

        return env;
    }

    private static void putTextOrNull(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        } else {
            node.putNull(field);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null ? fallback : value;
    }

    private static int readInt(JsonNode root, int fallback, String... path) {
        JsonNode current = root;
        if (current == null) {
            return fallback;
        }
        for (String element : path) {
            current = current.path(element);
        }
        return current.isMissingNode() || current.isNull() ? fallback : current.asInt(fallback);
    }
}
