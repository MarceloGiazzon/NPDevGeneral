package com.npdev.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 P6 (task 6.1). Exercises the {@code --previousCompiledModel}/{@code --schemaMigrationPlanOut}
 * CLI hook via a real, direct {@link GeneratorMain#main(String[])} invocation (not
 * {@code Build-NpdevApp.ps1}, which is out of this session's scope) -- the thin adapter this phase
 * added on top of {@code MigrationPlanEmitter}'s own already-unit-tested pure logic. Uses the
 * existing {@code canonical-demo} model fixture (same one {@link CanonicalDemoGenerationSmokeTest}
 * uses) rather than a hand-authored one, and an {@code InMemory} db definition to avoid any real
 * database.
 */
class GeneratorMainMigrationPlanCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CANONICAL_DEMO_MODEL =
            Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();

    @Test
    void firstGenerationWithNoPreviousModelProducesAFreshInstallPlanAndBothFlagsAreOptional() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-migration-plan-cli-fresh-");
        Path out = workspace.resolve("out");
        Path schemaRealizationDir = workspace.resolve("schema-realization");
        Path dbDefinition = writeInMemoryDbDefinition(workspace);
        Path planOut = workspace.resolve("migration-plan.json");

        GeneratorMain.main(new String[]{
                "--model", CANONICAL_DEMO_MODEL.toString(),
                "--out", out.toString(),
                "--schemaRealizationDir", schemaRealizationDir.toString(),
                "--dbDefinitionPath", dbDefinition.toString(),
                "--schemaMigrationPlanOut", planOut.toString(),
                "--no-assembleFinalApp"
        });

        assertTrue(Files.exists(planOut), "Expected the migration plan to be written to --schemaMigrationPlanOut");
        JsonNode plan = MAPPER.readTree(planOut.toFile());
        assertEquals("1", plan.path("migrationPlanVersion").asText());
        assertTrue(plan.path("freshInstall").asBoolean(), "No --previousCompiledModel was given -- expected freshInstall: true");
        assertTrue(plan.path("fromFingerprint").isNull(), "freshInstall must have a null fromFingerprint");
        assertFalse(plan.path("toFingerprint").asText().isBlank());
        assertTrue(plan.path("items").isArray() && plan.path("items").isEmpty());
        assertTrue(plan.path("destructiveAckToken").isNull());

        // The manifest's new task-6.3 field is present (empty) even when a plan WAS computed but had
        // no destructive items -- distinguishing "computed, nothing destructive" from "never computed"
        // is the executor's job (task 6.3), not this test's; this test only pins that the key exists
        // and is an empty array here.
        JsonNode manifest = MAPPER.readTree(
                out.resolve("src/main/resources/npdev/db/schema-realization-manifest.json").toFile());
        assertTrue(manifest.path("migrationPlanItemStableStrings").isArray());
        assertEquals(0, manifest.path("migrationPlanItemStableStrings").size());
    }

    @Test
    void secondGenerationOfTheUnchangedModelAgainstItsOwnPreviousCompiledModelProducesANoChangePlan() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-migration-plan-cli-nochange-");
        Path dbDefinition = writeInMemoryDbDefinition(workspace);

        // Run 1: ordinary generation (no plan flags at all) -- exactly today's existing callers,
        // proving the flags are truly optional / zero-behavior-change when absent.
        Path out1 = workspace.resolve("out1");
        GeneratorMain.main(new String[]{
                "--model", CANONICAL_DEMO_MODEL.toString(),
                "--out", out1.toString(),
                "--schemaRealizationDir", workspace.resolve("schema-realization-1").toString(),
                "--dbDefinitionPath", dbDefinition.toString(),
                "--no-assembleFinalApp"
        });
        Path previousCompiledModel = out1.resolve("src/main/resources/npdev/compiled-model.json");
        assertTrue(Files.exists(previousCompiledModel),
                "Expected run 1 to emit the canonical compiled-model.json every generated app ships "
                        + "(read by NPDevModelProvider at boot) -- this is the exact file a future "
                        + "Build-NpdevApp.ps1 -Upgrade would read as the 'previous model' input.");

        // Run 2: the SAME model, now passing run 1's compiled-model.json as --previousCompiledModel.
        Path out2 = workspace.resolve("out2");
        Path planOut = workspace.resolve("migration-plan-2.json");
        GeneratorMain.main(new String[]{
                "--model", CANONICAL_DEMO_MODEL.toString(),
                "--out", out2.toString(),
                "--schemaRealizationDir", workspace.resolve("schema-realization-2").toString(),
                "--dbDefinitionPath", dbDefinition.toString(),
                "--previousCompiledModel", previousCompiledModel.toString(),
                "--schemaMigrationPlanOut", planOut.toString(),
                "--no-assembleFinalApp"
        });

        JsonNode plan = MAPPER.readTree(planOut.toFile());
        assertFalse(plan.path("freshInstall").asBoolean(), "A previous model WAS supplied -- must not be freshInstall");
        assertFalse(plan.path("fromFingerprint").isNull(), "no-change is a DIFFERENT state from fresh-install: "
                + "fromFingerprint must be populated, not null");
        assertEquals(plan.path("toFingerprint").asText(), plan.path("fromFingerprint").asText(),
                "identical model compiled twice against the same db definition must produce identical fingerprints");
        assertTrue(plan.path("items").isArray() && plan.path("items").isEmpty(), "no model change -- expected zero items");
        assertTrue(plan.path("destructiveAckToken").isNull());
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
