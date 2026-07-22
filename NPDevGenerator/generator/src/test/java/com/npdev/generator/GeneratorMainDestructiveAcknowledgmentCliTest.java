package com.npdev.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 P6 (task 6.2b). Exercises the new {@code --destructiveAcknowledgment} CLI flag via a real,
 * direct {@link GeneratorMain#main(String[])} invocation, mirroring
 * {@link GeneratorMainMigrationPlanCliTest}'s pattern exactly (same {@code canonical-demo} model
 * fixture, same {@code InMemory} db definition to avoid any real database -- the manifest is still
 * emitted for InMemory apps, {@code physicalDatabase: false}, which is all this test needs to read).
 *
 * <p>This flag is the missing link Session A (6.1/6.3) deliberately left open: {@code SchemaManifest}
 * has carried a {@code destructiveAcknowledgment} field since Phase 4, and
 * {@code SchemaLifecycleExecutor#loadManifest} already parses the manifest's
 * {@code destructiveAcknowledgment} JSON key, but nothing generator-side ever WROTE a real value into
 * it -- every real generated app's manifest carried {@code ""} there until this flag.
 */
class GeneratorMainDestructiveAcknowledgmentCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CANONICAL_DEMO_MODEL =
            Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();

    @Test
    void tokenPassedViaTheCliFlagLandsVerbatimInTheGeneratedManifest() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-destructive-ack-cli-present-");
        Path out = workspace.resolve("out");
        Path schemaRealizationDir = workspace.resolve("schema-realization");
        Path dbDefinition = writeInMemoryDbDefinition(workspace);

        GeneratorMain.main(new String[]{
                "--model", CANONICAL_DEMO_MODEL.toString(),
                "--out", out.toString(),
                "--schemaRealizationDir", schemaRealizationDir.toString(),
                "--dbDefinitionPath", dbDefinition.toString(),
                "--destructiveAcknowledgment", "sha256:deadbeef",
                "--no-assembleFinalApp"
        });

        JsonNode manifest = MAPPER.readTree(
                out.resolve("src/main/resources/npdev/db/schema-realization-manifest.json").toFile());
        assertEquals("sha256:deadbeef", manifest.path("destructiveAcknowledgment").asText());
    }

    @Test
    void absentFlagLeavesTheManifestFieldEmptyExactlyLikeEveryPriorPhase() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-destructive-ack-cli-absent-");
        Path out = workspace.resolve("out");
        Path schemaRealizationDir = workspace.resolve("schema-realization");
        Path dbDefinition = writeInMemoryDbDefinition(workspace);

        GeneratorMain.main(new String[]{
                "--model", CANONICAL_DEMO_MODEL.toString(),
                "--out", out.toString(),
                "--schemaRealizationDir", schemaRealizationDir.toString(),
                "--dbDefinitionPath", dbDefinition.toString(),
                "--no-assembleFinalApp"
        });

        JsonNode manifest = MAPPER.readTree(
                out.resolve("src/main/resources/npdev/db/schema-realization-manifest.json").toFile());
        assertTrue(manifest.path("destructiveAcknowledgment").asText().isEmpty(),
                "no --destructiveAcknowledgment was given -- the manifest key must be \"\", not absent or null");
    }

    private static Path writeInMemoryDbDefinition(Path workspace) throws Exception {
        Path dbDefinition = workspace.resolve("db.definition.json");
        Files.writeString(dbDefinition, """
                {
                  "database": { "engine": "InMemory" },
                  "schemaLifecycle": { "strategy": "KeepExistingIfCompatible" }
                }
                """);
        return dbDefinition;
    }
}
