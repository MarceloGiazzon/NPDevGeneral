package com.npdev.generator.schemaevolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.dbconfig.DatabaseEngine;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.dbconfig.SchemaLifecyclePolicy;
import com.npdev.generator.dbconfig.SchemaLifecycleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-102 fix (MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 1.2, LC-C1/AC-3). Unit coverage over
 * synthetic {@link MigrationPlan}s (every {@link PlanItem.Kind} maps to the right coarse
 * {@link ModelChangeClassifier.Level}), plus a corpus property test for LC-C1's own DoD line: "a
 * change to a concept field is never classified METADATA_ONLY — property test over the corpus, not
 * a single example."
 */
class ModelChangeClassifierTest {

    @TempDir
    Path tempDir;

    @Test
    void freshInstallIsSafeAdditiveNotMetadataOnly() {
        MigrationPlan plan = new MigrationPlan(true, null, "sha256:x", List.of(), null);

        ModelChangeClassifier.Classification result = ModelChangeClassifier.classify(plan);

        assertEquals(ModelChangeClassifier.Level.SAFE_ADDITIVE, result.level());
    }

    @Test
    void noItemsOnANonFreshInstallIsMetadataOnly() {
        // Exactly LC-C1's own DoD example: a panel-label-only change touches no concept/field, so
        // MigrationPlanEmitter (which only ever diffs BusinessTableMetadata) necessarily produces
        // zero items -- METADATA_ONLY is reached by the SAME real diff engine, not approximated.
        MigrationPlan plan = new MigrationPlan(false, "sha256:same", "sha256:same", List.of(), null);

        ModelChangeClassifier.Classification result = ModelChangeClassifier.classify(plan);

        assertEquals(ModelChangeClassifier.Level.METADATA_ONLY, result.level());
    }

    @Test
    void addTableIsSafeAdditive() {
        assertLevel(PlanItem.addTable("widgets"), ModelChangeClassifier.Level.SAFE_ADDITIVE);
    }

    @Test
    void addColumnIsSafeAdditive() {
        assertLevel(PlanItem.addColumn("widgets", "description", "VARCHAR(255)"), ModelChangeClassifier.Level.SAFE_ADDITIVE);
    }

    @Test
    void renameTableIsSafeAdditive() {
        assertLevel(PlanItem.renameTable("accounts", "users"), ModelChangeClassifier.Level.SAFE_ADDITIVE);
    }

    @Test
    void renameColumnIsSafeAdditive() {
        assertLevel(PlanItem.renameColumn("widgets", "full_name", "name"), ModelChangeClassifier.Level.SAFE_ADDITIVE);
    }

    @Test
    void widenTypeIsSafeAdditive() {
        assertLevel(PlanItem.widenType("widgets", "login_count", "INTEGER", "BIGINT"), ModelChangeClassifier.Level.SAFE_ADDITIVE);
    }

    @Test
    void addUniqueConstraintIsSafeAdditive() {
        assertLevel(PlanItem.addUniqueConstraint("widgets", List.of("email")), ModelChangeClassifier.Level.SAFE_ADDITIVE);
    }

    @Test
    void addColumnBackfillIsBackfillRequired() {
        assertLevel(PlanItem.addColumnBackfill("widgets", "priority", "INTEGER", "1"), ModelChangeClassifier.Level.BACKFILL_REQUIRED);
    }

    @Test
    void dropColumnIsManualReview() {
        assertLevel(PlanItem.dropColumn(new com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropColumn(
                "widgets", "legacy_flag", "BOOLEAN")), ModelChangeClassifier.Level.MANUAL_REVIEW);
    }

    @Test
    void dropTableIsManualReview() {
        assertLevel(PlanItem.dropTable(new com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropTable("gadgets", -1L)),
                ModelChangeClassifier.Level.MANUAL_REVIEW);
    }

    @Test
    void narrowTypeIsManualReview() {
        assertLevel(PlanItem.narrowType(new com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.NarrowType(
                "widgets", "login_count", "BIGINT", "INTEGER")), ModelChangeClassifier.Level.MANUAL_REVIEW);
    }

    @Test
    void unknownIsManualReview() {
        assertLevel(PlanItem.unknown(new com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.Unknown("no literal default")),
                ModelChangeClassifier.Level.MANUAL_REVIEW);
    }

    @Test
    void worstItemWinsAcrossAMixedPlan() {
        // A safe ADD_COLUMN alongside a destructive DROP_COLUMN: the overall verdict must be the
        // worst one present, never averaged or first-wins -- an author must not be told "safe" when
        // part of their change is destructive.
        MigrationPlan plan = new MigrationPlan(false, "a", "b", List.of(
                PlanItem.addColumn("widgets", "description", "VARCHAR(255)"),
                PlanItem.dropColumn(new com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropColumn(
                        "widgets", "legacy_flag", "BOOLEAN"))
        ), null);

        ModelChangeClassifier.Classification result = ModelChangeClassifier.classify(plan);

        assertEquals(ModelChangeClassifier.Level.MANUAL_REVIEW, result.level());
        assertEquals(2, result.reasons().size());
    }

    private static void assertLevel(PlanItem item, ModelChangeClassifier.Level expected) {
        MigrationPlan plan = new MigrationPlan(false, "a", "b", List.of(item), null);
        ModelChangeClassifier.Classification result = ModelChangeClassifier.classify(plan);
        assertEquals(expected, result.level(), "item " + item.kind() + ": " + result.reasons());
    }

    // ------------------------------------------------------------------------------------------
    // Corpus property test -- LC-C1's own DoD: "A change to a concept field is never classified
    // METADATA_ONLY — property test over the corpus, not a single example."
    // ------------------------------------------------------------------------------------------

    @Test
    void addingAnOptionalFieldToAnyCorpusModelIsNeverMetadataOnlyAndIsSafeAdditive() throws Exception {
        List<Path> corpus = corpusModelPaths();
        assertTrue(corpus.size() >= 5, "expected a real in-repo corpus, found: " + corpus.size());
        List<String> failures = new ArrayList<>();
        for (Path modelPath : corpus) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode baselineNode = mapper.readTree(modelPath.toFile());
            if (!hasInlineConcepts(baselineNode)) {
                continue; // pack-only model (every concept comes from a composed pack) -- raw
                          // compile() runs schema validation before pack composition, which
                          // requires a top-level `concepts` key; nothing inline to mutate anyway.
            }
            CompiledModel baseline = compile(baselineNode);

            JsonNode currentNode = withFieldAddedToFirstConcept(mapper, baselineNode, false);
            if (currentNode == null) {
                continue; // no concepts in this corpus model -- nothing to mutate, not a failure
            }
            CompiledModel current = compile(currentNode);

            ModelChangeClassifier.Classification result = classifyModels(baseline, current);
            if (result.level() != ModelChangeClassifier.Level.SAFE_ADDITIVE) {
                failures.add(modelPath + " -> expected SAFE_ADDITIVE for a new optional field, got "
                        + result.level() + " (" + result.reasons() + ")");
            }
        }
        assertTrue(failures.isEmpty(), "corpus violations:\n" + String.join("\n", failures));
    }

    @Test
    void addingARequiredFieldWithNoDefaultToAnyCorpusModelIsNeverMetadataOnlyAndIsManualReview() throws Exception {
        List<Path> corpus = corpusModelPaths();
        List<String> failures = new ArrayList<>();
        for (Path modelPath : corpus) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode baselineNode = mapper.readTree(modelPath.toFile());
            if (!hasInlineConcepts(baselineNode)) {
                continue; // pack-only model -- see the sibling test for why compile() can't run here
            }
            CompiledModel baseline = compile(baselineNode);

            JsonNode currentNode = withFieldAddedToFirstConcept(mapper, baselineNode, true);
            if (currentNode == null) {
                continue;
            }
            CompiledModel current = compile(currentNode);

            ModelChangeClassifier.Classification result = classifyModels(baseline, current);
            if (result.level() == ModelChangeClassifier.Level.METADATA_ONLY) {
                failures.add(modelPath + " -> a required field with no default must never be METADATA_ONLY, got METADATA_ONLY");
            }
            if (result.level() != ModelChangeClassifier.Level.MANUAL_REVIEW) {
                failures.add(modelPath + " -> expected MANUAL_REVIEW for a new required field with no default, got "
                        + result.level() + " (" + result.reasons() + ")");
            }
        }
        assertTrue(failures.isEmpty(), "corpus violations:\n" + String.join("\n", failures));
    }

    private ModelChangeClassifier.Classification classifyModels(CompiledModel baseline, CompiledModel current) {
        MigrationPlan plan = MigrationPlanEmitter.compute(current, baseline, dbPlan());
        return ModelChangeClassifier.classify(plan);
    }

    private static CompiledModel compile(JsonNode node) throws IOException {
        ModelAst ast = new JsonModelParser().parse(node);
        return new ModelCompiler().compile(ast);
    }

    /** {@code true} if {@code root} declares a non-empty top-level {@code concepts} array. A
     *  pack-only model (all its concepts contributed by a composed {@code packs[].$ref}) has none --
     *  raw {@link #compile} runs schema validation on the UNRESOLVED node (no pack composition),
     *  and the schema requires the {@code concepts} key to be present at all, so such a model must
     *  be skipped before {@code compile()} is even attempted, not just before mutation. */
    private static boolean hasInlineConcepts(JsonNode root) {
        JsonNode concepts = root.get("concepts");
        return concepts != null && concepts.isArray() && !concepts.isEmpty();
    }

    /** Adds one new field (named to never collide) to the first concept's {@code fields} array, or
     *  returns {@code null} if the model declares no concepts. */
    private static JsonNode withFieldAddedToFirstConcept(ObjectMapper mapper, JsonNode root, boolean required) {
        if (!hasInlineConcepts(root)) {
            return null;
        }
        ObjectNode mutable = root.deepCopy();
        ArrayNode mutableConcepts = (ArrayNode) mutable.get("concepts");
        ObjectNode firstConcept = (ObjectNode) mutableConcepts.get(0);
        ArrayNode fields = firstConcept.has("fields") && firstConcept.get("fields").isArray()
                ? (ArrayNode) firstConcept.get("fields")
                : firstConcept.putArray("fields");
        ObjectNode newField = mapper.createObjectNode();
        newField.put("name", required ? "regTestRequiredNoDefaultField" : "regTestOptionalField");
        newField.put("type", "string");
        newField.put("required", required);
        fields.add(newField);
        return mutable;
    }

    private GeneratedDatabasePlan dbPlan() {
        return new GeneratedDatabasePlan(
                "model-change-classifier-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "model-change-classifier-test",
                "model-change-classifier-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "", "", 0, 0,
                "jdbc:h2:mem:model-change-classifier-test",
                "org.h2.Driver",
                "sa", "", "", 0, "", "",
                true, true,
                new SchemaLifecyclePolicy(
                        SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE, false, "",
                        SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE),
                "sha256:test",
                tempDir.resolve("database.json"),
                List.of("test"));
    }

    /** Every {@code NPDevSamples/*}/Input/model.json — the in-repo (git-tracked, CI-safe) half of
     *  the corpus {@code scripts/quality/validate-corpus.py} checks; {@code AppGen/apps} (the other
     *  half) lives outside the repo and is not assumed present in a fresh checkout. */
    private static List<Path> corpusModelPaths() throws IOException {
        Path samplesRoot = resolveSamplesRoot();
        try (Stream<Path> walk = Files.walk(samplesRoot, 3)) {
            return walk
                    .filter(p -> p.getFileName().toString().equals("model.json"))
                    .filter(p -> p.getParent().getFileName().toString().equals("Input"))
                    .sorted()
                    .toList();
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
