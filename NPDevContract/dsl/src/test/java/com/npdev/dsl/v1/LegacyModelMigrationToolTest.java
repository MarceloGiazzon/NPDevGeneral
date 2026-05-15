package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyModelMigrationToolTest {

    @Test
    void migratesLegacyEntitiesToConcepts() throws Exception {
        Path workspaceRoot = findWorkspaceRoot();
        Path output = Files.createTempFile("npdev-migrated-model-", ".json");
        Process process = new ProcessBuilder(
                commandForCurrentOs(workspaceRoot).toString(),
                "migrate",
                "legacy-model",
                "--input",
                workspaceRoot.resolve("test-fixtures/legacy-model.json").toString(),
                "--output",
                output.toString()
        ).directory(workspaceRoot.toFile()).redirectErrorStream(true).start();

        String outputText = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Migration command timed out: " + outputText);
        assertEquals(0, process.exitValue(), outputText);

        JsonNode migrated = new ObjectMapper().readTree(output.toFile());
        assertTrue(migrated.has("concepts"));
        assertFalse(migrated.has("entities"));
        assertEquals("LegacyCustomer", migrated.get("concepts").get(0).get("name").asText());
        assertEquals("NPDevContract/schemas/model.schema.json", migrated.get("$schema").asText());
    }

    private static Path commandForCurrentOs(Path workspaceRoot) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return workspaceRoot.resolve("npdev.bat");
        }
        return workspaceRoot.resolve("npdev");
    }

    private static Path findWorkspaceRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".npdev-root"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate workspace root.");
    }
}
