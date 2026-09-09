package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H13: HostPlanEmitter writes {@code _ops/host-plan.json} on EVERY generation (with or without a
 * {@code host.definition.json}); HostDeploymentEmitter writes deployment manifests ONLY when the
 * plan names an external target, and refuses (writes nothing) on an engine/target mismatch --
 * that refusal is `npdev host deploy`'s (H14) responsibility, this emitter simply never reaches an
 * external-target branch for an engine that does not match, because {@code npdev host plan} (H4)
 * is what refuses to WRITE such a definition in the first place. This test locks the emitter's own
 * contract: correct file set, byte-identical across two runs (LANDMINES #8), and nothing written
 * for a local (no-target) plan.
 */
class HostDeploymentEmitterTest {

    private static Path writeDbDefinition(Path directory, String engine) throws Exception {
        Path path = directory.resolve("db.definition.json");
        Files.writeString(path, """
                {
                  "database": { "engine": "%s", "host": "localhost", "port": 5432, "username": "npdev",
                                 "password": "secret", "createInternalTables": true, "createBusinessTables": true,
                                 "externallyProvisioned": false },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible", "scope": "NpdevOwnedTablesOnly" }
                }
                """.formatted(engine));
        return path;
    }

    private static void writeHostDefinition(Path directory, int rung, String target) throws Exception {
        String targetJson = target == null ? "null" : "\"" + target + "\"";
        Files.writeString(directory.resolve("host.definition.json"), """
                { "schemaVersion": "npdev-host-definition.v1", "rung": %d, "target": %s,
                  "reachableBy": { "kind": "platform-edge" } }
                """.formatted(rung, targetJson));
    }

    private static GeneratedDatabasePlan loadPlan(Path definitionDir, String engine) throws Exception {
        Path definitionPath = writeDbDefinition(definitionDir, engine);
        return new UserDatabaseDefinitionLoader().load(definitionPath, null);
    }

    @Test
    void hostPlanEmitterWritesAPlanEvenWithNoHostDefinitionYet(@TempDir Path tempDir) throws Exception {
        Path definitionDir = Files.createDirectories(tempDir.resolve("src"));
        Path appRoot = tempDir.resolve("app");
        GeneratedDatabasePlan plan = loadPlan(definitionDir, "H2Server");

        Path hostPlanPath = new HostPlanEmitter().emit(null, null, appRoot, plan);

        assertTrue(Files.isRegularFile(hostPlanPath));
        JsonNode json = new ObjectMapper().readTree(hostPlanPath.toFile());
        assertEquals(0, json.path("rung").asInt());
        assertTrue(json.path("target").isNull());
        assertEquals("nobody", json.path("reachableBy").path("kind").asText());
    }

    @Test
    void hostPlanEmitterResolvesAnExternalTargetFromHostDefinition(@TempDir Path tempDir) throws Exception {
        Path definitionDir = Files.createDirectories(tempDir.resolve("src"));
        Path appRoot = tempDir.resolve("app");
        GeneratedDatabasePlan plan = loadPlan(definitionDir, "Postgres");
        writeHostDefinition(definitionDir, 3, "render-neon");

        Path hostPlanPath = new HostPlanEmitter().emit(null, null, appRoot, plan);

        JsonNode json = new ObjectMapper().readTree(hostPlanPath.toFile());
        assertEquals(3, json.path("rung").asInt());
        assertEquals("render-neon", json.path("target").asText());
        assertEquals("managed", json.path("dataLivesOn").path("kind").asText());
        assertTrue(json.path("dataLivesOn").path("survivesRestart").asBoolean());
        assertTrue(json.path("requiredEnv").has("SPRING_DATASOURCE_URL"));
        assertEquals("you", json.path("requiredEnv").path("SPRING_DATASOURCE_URL").path("source").asText());
        assertEquals("npdev", json.path("requiredEnv").path("SPRING_PROFILES_ACTIVE").path("source").asText());
    }

    @Test
    void deploymentEmitterWritesNothingWhenThePlanNamesNoTarget(@TempDir Path tempDir) throws Exception {
        Path definitionDir = Files.createDirectories(tempDir.resolve("src"));
        Path appRoot = tempDir.resolve("app");
        GeneratedDatabasePlan plan = loadPlan(definitionDir, "H2Server");
        Path hostPlanPath = new HostPlanEmitter().emit(null, null, appRoot, plan);

        new HostDeploymentEmitter().emit(null, appRoot, plan, hostPlanPath);

        assertFalse(Files.exists(appRoot.resolve("render.yaml")));
        assertFalse(Files.exists(appRoot.resolve("koyeb.yaml")));
        assertFalse(Files.exists(appRoot.resolve(".env.render-neon.example")));
    }

    @Test
    void deploymentEmitterWritesRenderYamlAndEnvExampleForRenderNeon(@TempDir Path tempDir) throws Exception {
        Path definitionDir = Files.createDirectories(tempDir.resolve("src"));
        Path appRoot = tempDir.resolve("app");
        GeneratedDatabasePlan plan = loadPlan(definitionDir, "Postgres");
        writeHostDefinition(definitionDir, 3, "render-neon");
        Path hostPlanPath = new HostPlanEmitter().emit(null, null, appRoot, plan);

        new HostDeploymentEmitter().emit(null, appRoot, plan, hostPlanPath);

        Path renderYaml = appRoot.resolve("render.yaml");
        assertTrue(Files.isRegularFile(renderYaml));
        String yaml = Files.readString(renderYaml);
        assertTrue(yaml.contains("dockerfilePath: ./Dockerfile.build"), yaml);
        assertTrue(yaml.contains("SPRING_DATASOURCE_URL"), yaml);
        assertTrue(yaml.contains("sync: false"), "a you-sourced var must not be baked a literal value: " + yaml);

        Path envExample = appRoot.resolve(".env.render-neon.example");
        assertTrue(Files.isRegularFile(envExample));
        String env = Files.readString(envExample);
        assertTrue(env.contains("SPRING_PROFILES_ACTIVE=prod,postgres"), env);
        // write()'s platform line-ending conversion (System.lineSeparator(), matching every other
        // emitter's own write() helper) means this must not assume a literal LF -- check the LINE
        // itself, not a raw "KEY=\n" substring.
        assertTrue(env.lines().anyMatch(line -> line.equals("SPRING_DATASOURCE_URL=")),
                "an unresolved var must be left blank: " + env);
    }

    @Test
    void deploymentEmitterWritesKoyebYamlForKoyebTidb(@TempDir Path tempDir) throws Exception {
        Path definitionDir = Files.createDirectories(tempDir.resolve("src"));
        Path appRoot = tempDir.resolve("app");
        GeneratedDatabasePlan plan = loadPlan(definitionDir, "MySQL");
        writeHostDefinition(definitionDir, 3, "koyeb-tidb");
        Path hostPlanPath = new HostPlanEmitter().emit(null, null, appRoot, plan);

        new HostDeploymentEmitter().emit(null, appRoot, plan, hostPlanPath);

        Path koyebYaml = appRoot.resolve("koyeb.yaml");
        assertTrue(Files.isRegularFile(koyebYaml));
        String yaml = Files.readString(koyebYaml);
        assertTrue(yaml.contains("dockerfilePath") || yaml.contains("koyeb"), yaml);
        assertTrue(Files.isRegularFile(appRoot.resolve(".env.koyeb-tidb.example")));
    }

    @Test
    void deploymentEmitterWritesNothingForALocalMachineTarget(@TempDir Path tempDir) throws Exception {
        Path definitionDir = Files.createDirectories(tempDir.resolve("src"));
        Path appRoot = tempDir.resolve("app");
        GeneratedDatabasePlan plan = loadPlan(definitionDir, "H2Server");
        writeHostDefinition(definitionDir, 1, "cloudflared");
        Path hostPlanPath = new HostPlanEmitter().emit(null, null, appRoot, plan);

        new HostDeploymentEmitter().emit(null, appRoot, plan, hostPlanPath);

        assertFalse(Files.exists(appRoot.resolve("render.yaml")));
        assertFalse(Files.exists(appRoot.resolve("koyeb.yaml")));
    }

    @Test
    void deploymentEmitterWritesNothingForVpsSinceDockerComposeAlreadyIsTheDeployment(@TempDir Path tempDir)
            throws Exception {
        Path definitionDir = Files.createDirectories(tempDir.resolve("src"));
        Path appRoot = tempDir.resolve("app");
        GeneratedDatabasePlan plan = loadPlan(definitionDir, "Postgres");
        writeHostDefinition(definitionDir, 4, "vps");
        Path hostPlanPath = new HostPlanEmitter().emit(null, null, appRoot, plan);

        new HostDeploymentEmitter().emit(null, appRoot, plan, hostPlanPath);

        assertFalse(Files.exists(appRoot.resolve("render.yaml")));
        assertFalse(Files.exists(appRoot.resolve("koyeb.yaml")));
    }

    @Test
    void twoGenerationRunsProduceByteIdenticalManifests(@TempDir Path tempDir) throws Exception {
        Path definitionDirA = Files.createDirectories(tempDir.resolve("srcA"));
        Path appRootA = tempDir.resolve("appA");
        GeneratedDatabasePlan planA = loadPlan(definitionDirA, "Postgres");
        writeHostDefinition(definitionDirA, 3, "render-neon");
        Path hostPlanPathA = new HostPlanEmitter().emit(null, null, appRootA, planA);
        new HostDeploymentEmitter().emit(null, appRootA, planA, hostPlanPathA);

        Path definitionDirB = Files.createDirectories(tempDir.resolve("srcB"));
        Path appRootB = tempDir.resolve("appB");
        GeneratedDatabasePlan planB = loadPlan(definitionDirB, "Postgres");
        writeHostDefinition(definitionDirB, 3, "render-neon");
        Path hostPlanPathB = new HostPlanEmitter().emit(null, null, appRootB, planB);
        new HostDeploymentEmitter().emit(null, appRootB, planB, hostPlanPathB);

        assertEquals(Files.readString(appRootA.resolve("render.yaml")), Files.readString(appRootB.resolve("render.yaml")));
        assertEquals(Files.readString(appRootA.resolve(".env.render-neon.example")),
                Files.readString(appRootB.resolve(".env.render-neon.example")));
    }
}
