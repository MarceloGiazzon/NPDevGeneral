package com.npdev.dsl.v1.repo;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemModelRepositoryTest {

    @Test
    void publishesAndListsArtifactsDeterministically() throws Exception {
        Path temp = Files.createTempDirectory("npdev-repo-test-");
        try {
            Path repoModelsDir = temp.resolve("repository").resolve("models");
            Files.createDirectories(repoModelsDir);

            Path modelPath = temp.resolve("model.json");
            Files.writeString(modelPath, minimalModelJson(), StandardCharsets.UTF_8);

            ModelRepository repo = new FileSystemModelRepository(repoModelsDir);
            ModelArtifact first = repo.publish(modelPath);
            ModelArtifact second = repo.publish(modelPath);

            assertEquals(first.hash(), second.hash(), "same model must produce same hash");
            assertEquals(first.name(), second.name(), "same model must produce same name");
            assertTrue(Files.exists(first.compiledMetadataJsonPath()), "expected compiled metadata artifact");

            List<ModelArtifact> all = repo.list();
            assertTrue(all.stream().anyMatch(a -> a.hash().equals(first.hash()) && a.name().equals(first.name())));
            assertTrue(Files.exists(first.manifestJsonPath()));

            Map<?, ?> manifest = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    first.manifestJsonPath().toFile(),
                    Map.class
            );
            assertTrue(manifest.toString().contains("compiledMetadata"),
                    "expected manifest to include compiled metadata entry");
        } finally {
            // best effort cleanup
        }
    }

    private static String minimalModelJson() {
        return """
                {
                  "model": "RepoTestDomain",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "email", "type": "string", "required": true, "unique": true }
                      ]
                    }
                  ]
                }
                """;
    }
}

