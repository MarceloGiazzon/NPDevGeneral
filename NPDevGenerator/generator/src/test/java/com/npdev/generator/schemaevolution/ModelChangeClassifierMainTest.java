package com.npdev.generator.schemaevolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast Lane plan item 1a (REG-103 follow-up): {@code --emitMetadataTo} on
 * {@link ModelChangeClassifierMain} writes {@code compiled-metadata.json} and every
 * {@code metadata/*.manifest.json} catalog for a METADATA_ONLY change, the other half of the fast
 * path {@code --emitCompiledModelTo} already covered for {@code compiled-model.json}.
 */
class ModelChangeClassifierMainTest {

    @TempDir
    Path tempDir;

    @Test
    void emitMetadataToWritesCompiledMetadataAndEveryPerCatalogManifest() throws Exception {
        Path modelPath = firstCorpusModelPath();
        Path emitTarget = tempDir.resolve("metadata-out");

        // Identical current/baseline -> guaranteed zero MigrationPlan items -> METADATA_ONLY,
        // exactly the "no concept/field/index change" branch ModelChangeClassifier.classify()
        // takes deterministically, without depending on any particular corpus model's shape.
        ModelChangeClassifierMain.main(new String[] {
                "--current", modelPath.toString(),
                "--baseline", modelPath.toString(),
                "--emitMetadataTo", emitTarget.toString()
        });

        Path compiledMetadataPath = emitTarget.resolve("src/main/resources/npdev/compiled-metadata.json");
        Path indexPath = emitTarget.resolve("src/main/resources/npdev/metadata/index.json");
        Path conceptsManifestPath = emitTarget.resolve("src/main/resources/npdev/metadata/concepts.manifest.json");
        assertTrue(Files.exists(compiledMetadataPath), "compiled-metadata.json was not written: " + compiledMetadataPath);
        assertTrue(Files.exists(indexPath), "metadata/index.json was not written: " + indexPath);
        assertTrue(Files.exists(conceptsManifestPath), "metadata/concepts.manifest.json was not written: " + conceptsManifestPath);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode compiledMetadata = mapper.readTree(compiledMetadataPath.toFile());
        assertTrue(compiledMetadata.has("namespace"), "compiled-metadata.json missing namespace field");

        JsonNode index = mapper.readTree(indexPath.toFile());
        assertTrue(index.path("catalogs").isArray(), "metadata/index.json missing catalogs array");
        assertEquals(11, index.path("catalogs").size(), "expected all 11 catalogs listed in metadata/index.json");
    }

    /** Any real, in-repo, schema-valid model -- content doesn't matter for this test since current
     *  and baseline are the same file, so the classification is METADATA_ONLY regardless of shape. */
    private static Path firstCorpusModelPath() throws IOException {
        Path samplesRoot = resolveSamplesRoot();
        try (Stream<Path> walk = Files.walk(samplesRoot, 3)) {
            List<Path> models = walk
                    .filter(p -> p.getFileName().toString().equals("model.json"))
                    .filter(p -> p.getParent().getFileName().toString().equals("Input"))
                    .sorted()
                    .toList();
            if (models.isEmpty()) {
                throw new IllegalStateException("No NPDevSamples/*/Input/model.json found under " + samplesRoot);
            }
            return models.get(0);
        }
    }

    private static Path resolveSamplesRoot() {
        for (Path candidate : List.of(
                Path.of("..", "..", "NPDevSamples"),
                Path.of("..", "..", "..", "NPDevSamples"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not resolve NPDevSamples root from " + Path.of("").toAbsolutePath());
    }
}
