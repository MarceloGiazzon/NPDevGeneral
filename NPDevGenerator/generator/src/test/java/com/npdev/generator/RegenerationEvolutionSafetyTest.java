package com.npdev.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.migration.MigrationRiskAssessment;
import com.npdev.generator.migration.MigrationRiskAssessmentBuilder;
import com.npdev.generator.migration.ModelDiffPreview;
import com.npdev.generator.migration.ModelDiffPreviewBuilder;
import com.npdev.generator.migration.RuntimeModelCompatibilityReport;
import com.npdev.generator.migration.RuntimeModelCompatibilityReportBuilder;
import com.npdev.generator.migration.StorageSchemaSnapshot;
import com.npdev.generator.migration.StorageSchemaSnapshotStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegenerationEvolutionSafetyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void canonicalDemoEvolutionRemainsSafeUnderRegeneration() throws Exception {
        Path canonicalModel = resolveCanonicalModel();
        Path baselineModelCopy = Files.createTempFile("npdev-step47-baseline-", ".json");
        Files.copy(canonicalModel, baselineModelCopy, StandardCopyOption.REPLACE_EXISTING);

        Path evolvedModel = Files.createTempFile("npdev-step47-evolved-", ".json");
        Files.writeString(evolvedModel, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(buildEvolvedModel(canonicalModel)));

        CompiledModel baselineCompiled = compileModel(baselineModelCopy);
        CompiledModel evolvedCompiled = compileModel(evolvedModel);

        Path baselineOut = Files.createTempDirectory("npdev-step47-baseline-out-");
        Path baselineDbRoot = Files.createTempDirectory("npdev-step47-baseline-db-");
        generate(baselineCompiled, baselineModelCopy, baselineOut, baselineDbRoot);

        Path evolvedOutOne = Files.createTempDirectory("npdev-step47-evolved-out-one-");
        Path evolvedDbRootOne = Files.createTempDirectory("npdev-step47-evolved-db-one-");
        copyDirectory(baselineDbRoot, evolvedDbRootOne);
        generate(evolvedCompiled, evolvedModel, evolvedOutOne, evolvedDbRootOne);

        Path evolvedOutTwo = Files.createTempDirectory("npdev-step47-evolved-out-two-");
        Path evolvedDbRootTwo = Files.createTempDirectory("npdev-step47-evolved-db-two-");
        copyDirectory(baselineDbRoot, evolvedDbRootTwo);
        generate(evolvedCompiled, evolvedModel, evolvedOutTwo, evolvedDbRootTwo);

        String baselineCompiledModel = readGeneratedAsset(baselineOut, "compiled-model.json");
        String baselineCompiledMetadata = readGeneratedAsset(baselineOut, "compiled-metadata.json");
        String evolvedCompiledModelOne = readGeneratedAsset(evolvedOutOne, "compiled-model.json");
        String evolvedCompiledMetadataOne = readGeneratedAsset(evolvedOutOne, "compiled-metadata.json");
        String evolvedCompiledModelTwo = readGeneratedAsset(evolvedOutTwo, "compiled-model.json");
        String evolvedCompiledMetadataTwo = readGeneratedAsset(evolvedOutTwo, "compiled-metadata.json");

        assertNotEquals(baselineCompiledModel, evolvedCompiledModelOne,
                "Model evolution should change generated compiled-model output.");
        assertNotEquals(baselineCompiledMetadata, evolvedCompiledMetadataOne,
                "Model evolution should change generated compiled-metadata output.");
        assertEquals(evolvedCompiledModelOne, evolvedCompiledModelTwo,
                "The evolved compiled-model output must stay deterministic across repeated regenerations.");
        assertEquals(evolvedCompiledMetadataOne, evolvedCompiledMetadataTwo,
                "The evolved compiled-metadata output must stay deterministic across repeated regenerations.");
        // no diff expectation: regenerating the same evolved model should produce no diff in canonical generated assets.

        assertTrue(evolvedCompiledModelOne.contains("\"portalNickname\""),
                "The added field must propagate into the compiled model.");
        assertTrue(evolvedCompiledMetadataOne.contains("Cancelled by clinic"),
                "The enum evolution must propagate into compiled metadata.");
        assertTrue(evolvedCompiledMetadataOne.contains("calendar-x-evolved"),
                "The enum icon-hint evolution must propagate into compiled metadata.");
        assertTrue(evolvedCompiledMetadataOne.contains("Schedule visit"),
                "The flow action evolution must propagate into compiled metadata.");
        assertTrue(evolvedCompiledMetadataOne.contains("Cancel visit"),
                "The transition action evolution must propagate into compiled metadata.");
        assertTrue(evolvedCompiledMetadataOne.contains("Evolved scheduling baseline used to verify safe iteration."),
                "The metadata evolution must propagate into compiled metadata.");

        String migrationPlanOne = readMigrationPlan(evolvedDbRootOne);
        String migrationPlanTwo = readMigrationPlan(evolvedDbRootTwo);
        assertEquals(migrationPlanOne, migrationPlanTwo,
                "Migration planning for the evolved model must be deterministic from the same baseline snapshot.");
        assertTrue(migrationPlanOne.contains("ALTER TABLE patients ADD COLUMN IF NOT EXISTS portal_nickname"),
                "The additive field evolution must produce an additive migration plan.");
        assertFalse(migrationPlanOne.toLowerCase().contains("drop column"),
                "The evolution scenario should not require destructive column drops.");

        StorageSchemaSnapshotStore store = new StorageSchemaSnapshotStore();
        StorageSchemaSnapshot baselineSnapshot = store.loadIfExists(
                baselineDbRoot.resolve("schema-snapshots").resolve("latest-storage-schema.json"));
        StorageSchemaSnapshot evolvedSnapshot = store.loadIfExists(
                evolvedDbRootOne.resolve("schema-snapshots").resolve("latest-storage-schema.json"));

        ModelDiffPreview preview = new ModelDiffPreviewBuilder().build(baselineSnapshot, evolvedSnapshot);
        MigrationRiskAssessment risk = new MigrationRiskAssessmentBuilder().build(baselineSnapshot, evolvedSnapshot);
        RuntimeModelCompatibilityReport report = new RuntimeModelCompatibilityReportBuilder()
                .build(baselineSnapshot, evolvedSnapshot, buildInfo());

        assertTrue(preview.deterministicDiff(), "The evolved diff must be deterministic.");
        assertTrue(preview.additiveChanges().stream().anyMatch(value -> value.contains("patients.portal_nickname")),
                "The diff preview must describe the added Patient portal nickname field.");
        assertTrue(preview.breakingChanges().isEmpty(),
                "The chosen evolution scenario should not create breaking schema changes.");
        assertEquals("SAFE_ADDITIVE", risk.overallRisk(),
                "The chosen evolution scenario should remain additive from a migration-risk perspective.");
        assertTrue(report.compatible(), "The evolved model should remain runtime-compatible.");
        assertEquals("COMPATIBLE", report.compatibilityStatus(),
                "The runtime compatibility report should stay compatible for this additive evolution.");
    }

    private static Properties buildInfo() {
        Properties buildInfo = new Properties();
        buildInfo.setProperty("npdev.version", "step47-test");
        buildInfo.setProperty("npdev.builtAt", "2026-03-31T19:15:00Z");
        return buildInfo;
    }

    private static ObjectNode buildEvolvedModel(Path canonicalModel) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readString(canonicalModel));
        root.put("version", "1.1");

        ObjectNode patient = findNamedObject(root.withArray("concepts"), "name", "Patient");
        ArrayNode patientFields = patient.withArray("fields");
        patientFields.add(MAPPER.createObjectNode()
                .put("name", "portalNickname")
                .put("type", "string")
                .set("ui", MAPPER.createObjectNode()
                        .put("label", "Portal nickname")
                        .put("shortLabel", "Portal")
                        .put("description", "Optional nickname used in patient-facing portal messages")
                        .put("helpText", "A safe additive field used to verify regeneration behavior")
                        .put("group", "Preferences")
                        .put("section", "Communication")
                        .put("order", 55)
                        .put("listColumn", true)
                        .put("listColumnOrder", 55)
                        .put("width", "md")));

        ObjectNode appointment = findNamedObject(root.withArray("concepts"), "name", "Appointment");
        appointment.with("ui").put("helpText", "Evolved scheduling baseline used to verify safe iteration.");

        ObjectNode statusField = findNamedObject(appointment.withArray("fields"), "name", "status");
        ObjectNode cancelledOption = findEnumOption(statusField.withArray("enumValues"), "Cancelled");
        cancelledOption.put("label", "Cancelled by clinic");
        cancelledOption.put("iconHint", "calendar-x-evolved");

        ObjectNode cancelTransition = findTransition(appointment.with("lifecycle").withArray("transitions"), "Scheduled", "Cancelled");
        cancelTransition.put("actionLabel", "Cancel visit");
        cancelTransition.with("action")
                .put("label", "Cancel visit")
                .put("confirmationText", "Cancel this visit and release the slot?");

        ObjectNode createAppointment = findNamedObject(root.withArray("flows"), "name", "CreateAppointment");
        createAppointment.with("action")
                .put("label", "Schedule visit")
                .put("confirmationText", "Schedule this visit and queue the related follow-up actions?");

        return root;
    }

    private static ObjectNode findNamedObject(ArrayNode array, String property, String value) {
        for (int index = 0; index < array.size(); index++) {
            if (value.equals(array.get(index).path(property).asText())) {
                return (ObjectNode) array.get(index);
            }
        }
        throw new IllegalStateException("Unable to find object with " + property + "=" + value);
    }

    private static ObjectNode findEnumOption(ArrayNode array, String value) {
        for (int index = 0; index < array.size(); index++) {
            if (value.equals(array.get(index).path("value").asText())) {
                return (ObjectNode) array.get(index);
            }
        }
        throw new IllegalStateException("Unable to find enum option " + value);
    }

    private static ObjectNode findTransition(ArrayNode array, String from, String to) {
        for (int index = 0; index < array.size(); index++) {
            if (from.equals(array.get(index).path("from").asText())
                    && to.equals(array.get(index).path("to").asText())) {
                return (ObjectNode) array.get(index);
            }
        }
        throw new IllegalStateException("Unable to find transition " + from + " -> " + to);
    }

    private static CompiledModel compileModel(Path modelPath) throws Exception {
        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);
        return new ModelCompiler().compile(ast);
    }

    private static void generate(CompiledModel model, Path modelPath, Path outRoot, Path dbRoot) throws Exception {
        Path migrationDir = dbRoot.resolve("migration");
        Files.createDirectories(migrationDir);

        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(outRoot, new RegenerationPolicy());
        new GeneratorFacade(templates, writer).generate(model, outRoot, migrationDir, modelPath);
    }

    private static String readGeneratedAsset(Path outRoot, String fileName) throws Exception {
        return Files.readString(outRoot.resolve("src").resolve("main").resolve("resources").resolve("npdev").resolve(fileName));
    }

    private static String readMigrationPlan(Path dbRoot) throws Exception {
        return Files.readString(dbRoot.resolve("migration-plans").resolve("latest-model-delta.sql"));
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            stream.forEach(path -> {
                try {
                    Path relative = source.relativize(path);
                    Path destination = target.resolve(relative);
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to copy Step 47 baseline state.", exception);
                }
            });
        }
    }

    private static Path resolveCanonicalModel() {
        return resolvePath(
                Path.of("..", "resources", "Models", "canonical-demo", "model.json"),
                Path.of("resources", "Models", "canonical-demo", "model.json")
        );
    }

    private static Path resolvePath(Path first, Path second) {
        if (Files.exists(first)) {
            return first.normalize();
        }
        if (Files.exists(second)) {
            return second.normalize();
        }
        throw new IllegalStateException("Unable to resolve Step 47 canonical model path.");
    }
}
