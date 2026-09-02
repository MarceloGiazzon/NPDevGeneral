package com.npdev.generator.schemaevolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.schemaevolution.RenameCandidateScorer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Boundary lift plan 2026-09-02, package 2.2 (B1): {@link ModelChangeClassifierMain}'s
     * {@code enrichRenameCandidatesWithFieldNames} reverse-maps a {@link RenameCandidateScorer}
     * candidate's SQL table/column back to the DSL {@code Concept}/field name, via the SAME
     * {@code SqlIdentifierSupport} convention {@link MigrationPlanEmitter} used forward -- so
     * {@code npdev migrate rename --from-suggestions} has something to feed the existing
     * {@code run_migrate_rename} stamping path. Exercised directly against hand-built fixtures
     * (no JSON parsing, no schema validation) so the test targets exactly this lookup.
     */
    @Test
    void enrichRenameCandidatesResolvesConceptAndFieldNamesWhenTheSqlNamesMatchARealField() {
        CompiledField id = new CompiledField("id", "uuid", "java.util.UUID", true, true, false);
        CompiledField droppedField = new CompiledField("emailAddres", "string", "java.lang.String", false, false, false);
        CompiledConcept widget = new CompiledConcept("Widget", "Widget", "", List.of(id, droppedField));
        Map<String, CompiledConcept> byName = new LinkedHashMap<>();
        byName.put("Widget", widget);
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", byName);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode renameCandidates = root.putArray("renameCandidates");
        ObjectNode candidate = renameCandidates.addObject();
        candidate.put("table", "widgets");
        candidate.put("droppedColumn", "email_addres");
        candidate.put("addedColumn", "email_address"); // no field named emailAddress in the fixture
        candidate.put("score", 90);
        candidate.put("maxScore", 100);
        candidate.putArray("signals");

        ModelChangeClassifierMain.enrichRenameCandidatesWithFieldNames(root, model);

        JsonNode result = root.get("renameCandidates").get(0);
        assertEquals("Widget", result.get("concept").asText());
        assertEquals("emailAddres", result.get("droppedField").asText());
        assertTrue(result.path("addedField").isMissingNode(),
                "no field named emailAddress exists in the fixture -- addedField must be left absent, never guessed");
    }

    @Test
    void enrichRenameCandidatesLeavesAnUnmatchedTableCompletelyAlone() {
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of());

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode renameCandidates = root.putArray("renameCandidates");
        ObjectNode candidate = renameCandidates.addObject();
        candidate.put("table", "does_not_exist");
        candidate.put("droppedColumn", "a");
        candidate.put("addedColumn", "b");
        candidate.put("score", 10);
        candidate.put("maxScore", 100);
        candidate.putArray("signals");

        ModelChangeClassifierMain.enrichRenameCandidatesWithFieldNames(root, model);

        JsonNode result = root.get("renameCandidates").get(0);
        assertTrue(result.path("concept").isMissingNode(), "a table matching no concept must not get a concept guess");
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
