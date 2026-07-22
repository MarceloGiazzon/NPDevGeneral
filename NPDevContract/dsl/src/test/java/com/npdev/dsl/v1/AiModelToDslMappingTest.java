package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelToDslMappingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path WORKSPACE_ROOT = resolveWorkspaceRoot();
    private static final Path POLICY_PATH = WORKSPACE_ROOT.resolve("scripts/policy/ai-model-to-dsl-mapping-policy.json");
    private static final Path AI_MODEL_SCHEMA_PATH = WORKSPACE_ROOT.resolve("schemas/ai/ai-model.schema.json");
    private static final Path SCENARIO_ROOT = WORKSPACE_ROOT.resolve("golden-ai-scenarios");
    private static final Path TEST_OUTPUT_ROOT = WORKSPACE_ROOT.resolve("scripts/reports/tmp/ai-model-to-dsl-mapping-test");
    private static final Set<String> ALLOWED_CLASSIFICATIONS = Set.of(
            "mapped",
            "rejected",
            "diagnostic-only",
            "future-deferred"
    );

    @Test
    void everyAiModelSchemaFieldIsClassifiedExactlyOnce() throws Exception {
        JsonNode schema = JSON.readTree(AI_MODEL_SCHEMA_PATH.toFile());
        JsonNode policy = JSON.readTree(POLICY_PATH.toFile());
        Set<String> schemaFields = collectSchemaPaths(schema);
        Set<String> policyFields = collectPolicyFieldPaths(policy);

        Set<String> unmappedFields = new TreeSet<>(schemaFields);
        unmappedFields.removeAll(policyFields);
        Set<String> stalePolicyFields = new TreeSet<>(policyFields);
        stalePolicyFields.removeAll(schemaFields);

        assertTrue(unmappedFields.isEmpty(), "unmapped ai-model.v1 schema fields: " + unmappedFields);
        assertTrue(stalePolicyFields.isEmpty(), "policy fields not present in ai-model.v1 schema: " + stalePolicyFields);

        Set<String> duplicateFields = findDuplicatePolicyFields(policy);
        assertTrue(duplicateFields.isEmpty(), "duplicate policy fields: " + duplicateFields);

        for (JsonNode field : policy.path("schemaDeclaredFields")) {
            String classification = field.path("classification").asText();
            assertTrue(ALLOWED_CLASSIFICATIONS.contains(classification),
                    "unsupported classification for " + field.path("path").asText() + ": " + classification);
            assertFalse("unmapped".equals(classification), "field must not be classified as unmapped");
        }

        Set<String> requiredSurfaces = Stream.of(
                "app",
                "entities",
                "entities.fields",
                "flows",
                "panels",
                "procedures",
                "workflows",
                "roles",
                "tenancy",
                "auth",
                "verification"
        ).collect(Collectors.toCollection(TreeSet::new));
        Set<String> coveredSurfaces = new TreeSet<>();
        for (JsonNode surface : policy.path("requiredTopLevelCoverage")) {
            coveredSurfaces.add(surface.path("surface").asText());
            assertTrue(ALLOWED_CLASSIFICATIONS.contains(surface.path("classification").asText()),
                    "unsupported surface classification for " + surface.path("surface").asText());
        }
        assertEquals(requiredSurfaces, coveredSurfaces, "roadmap-required surfaces must be covered");
    }

    @Test
    void everyGoldenAiModelUsesOnlyClassifiedFields() throws Exception {
        JsonNode policy = JSON.readTree(POLICY_PATH.toFile());
        Set<String> classifiedFields = collectPolicyFieldPaths(policy);
        stream(policy.path("rejectedInstanceFields"))
                .map(node -> node.path("path").asText())
                .forEach(classifiedFields::add);
        List<String> unclassified = new ArrayList<>();
        int aiModelScenarioCount = 0;

        for (Path scenarioDir : scenarioDirs()) {
            JsonNode manifest = readJson(scenarioDir.resolve("scenario.manifest.json"));
            String aiModelFile = manifest.path("files").path("aiModel").asText("");
            if (aiModelFile.isBlank()) {
                continue;
            }
            aiModelScenarioCount++;
            JsonNode aiModel = readJson(scenarioDir.resolve(aiModelFile));
            for (String path : collectInstancePaths(aiModel)) {
                if (!classifiedFields.contains(path)) {
                    unclassified.add(scenarioDir.getFileName() + ":" + path);
                }
            }
        }

        assertEquals(24, aiModelScenarioCount, "golden AI-model scenario count changed; update the mapping evidence deliberately");
        assertTrue(unclassified.isEmpty(), "unclassified golden scenario AI model fields: " + unclassified);
    }

    @Test
    void positiveGoldenScenariosNormalizeToDocumentedDslTargets() throws Exception {
        deleteRecursively(TEST_OUTPUT_ROOT);
        List<String> positiveScenarioIds = List.of(
                "base-ai-loop",
                "tenant-workflow-ops",
                "tenant-service-desk",
                "tenant-approval-portal"
        );

        for (String scenarioId : positiveScenarioIds) {
            Path scenarioDir = SCENARIO_ROOT.resolve(scenarioId);
            JsonNode aiModel = readJson(scenarioDir.resolve("ai-model.json"));
            JsonNode result = runNormalizerForScenario(scenarioId);
            assertEquals("passed", result.path("status").asText(), "normalizer must pass for " + scenarioId);

            JsonNode officialModel = readJson(TEST_OUTPUT_ROOT.resolve(scenarioId).resolve("model.json"));
            assertEntityConceptMapping(aiModel, officialModel, scenarioId);
            assertFlowMapping(aiModel, officialModel, scenarioId);
            assertExpandedSurfaceMapping(aiModel, officialModel, scenarioId);
        }
    }

    @Test
    void negativeGoldenScenariosHaveDocumentedDiagnosticCodes() throws Exception {
        JsonNode policy = JSON.readTree(POLICY_PATH.toFile());
        Map<String, String> scenarioDiagnostics = stream(policy.path("goldenScenarioDiagnostics"))
                .collect(Collectors.toMap(
                        node -> node.path("scenarioId").asText(),
                        node -> node.path("expectedDiagnosticCode").asText()
                ));
        List<String> missingDiagnostics = new ArrayList<>();

        for (Path scenarioDir : scenarioDirs()) {
            JsonNode manifest = readJson(scenarioDir.resolve("scenario.manifest.json"));
            if (!"fail".equals(manifest.path("expectedOutcome").asText())) {
                continue;
            }
            boolean hasAiModel = !manifest.path("files").path("aiModel").asText("").isBlank();
            boolean hasVerification = !manifest.path("files").path("verification").asText("").isBlank();
            if ((hasAiModel || hasVerification) && !scenarioDiagnostics.containsKey(scenarioDir.getFileName().toString())) {
                missingDiagnostics.add(scenarioDir.getFileName().toString());
            }
        }
        assertTrue(missingDiagnostics.isEmpty(), "negative scenario diagnostics are undocumented: " + missingDiagnostics);

        for (JsonNode diagnosticScenario : policy.path("normalizerDiagnosticScenarios")) {
            String scenarioId = diagnosticScenario.path("scenarioId").asText();
            String expectedCode = diagnosticScenario.path("expectedDiagnosticCode").asText();
            JsonNode result = runNormalizerForNegativeAiModel(scenarioId);
            Set<String> actualCodes = stream(result.path("errors"))
                    .map(node -> node.path("code").asText())
                    .collect(Collectors.toCollection(TreeSet::new));
            assertTrue(actualCodes.contains(expectedCode),
                    "expected " + scenarioId + " to expose " + expectedCode + " but got " + actualCodes);
        }
    }

    private static void assertEntityConceptMapping(JsonNode aiModel, JsonNode officialModel, String scenarioId) {
        Set<String> entities = stream(aiModel.path("entities"))
                .map(node -> node.path("name").asText())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> concepts = stream(officialModel.path("concepts"))
                .map(node -> node.path("name").asText())
                .collect(Collectors.toCollection(TreeSet::new));
        assertTrue(concepts.containsAll(entities), scenarioId + " entities must map to concepts");
    }

    private static void assertFlowMapping(JsonNode aiModel, JsonNode officialModel, String scenarioId) {
        Set<String> aiFlows = stream(aiModel.path("flows"))
                .map(node -> node.path("name").asText())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> officialFlows = stream(officialModel.path("flows"))
                .map(node -> node.path("name").asText())
                .collect(Collectors.toCollection(TreeSet::new));
        assertTrue(officialFlows.containsAll(aiFlows), scenarioId + " flows must map to official flows");
    }

    private static void assertExpandedSurfaceMapping(JsonNode aiModel, JsonNode officialModel, String scenarioId) {
        if (!"expanded-beta-application".equals(aiModel.path("app").path("kind").asText())) {
            return;
        }
        Set<String> aiProcedures = stream(aiModel.path("procedures"))
                .map(node -> node.path("procedureId").asText())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> officialProcedures = stream(officialModel.path("procedures"))
                .map(node -> node.path("name").asText())
                .collect(Collectors.toCollection(TreeSet::new));
        assertTrue(officialProcedures.containsAll(aiProcedures), scenarioId + " procedures must map");

        Set<String> aiPanels = stream(aiModel.path("panels"))
                .map(node -> node.path("panelId").asText())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> officialPanels = stream(officialModel.path("panels"))
                .map(node -> node.path("name").asText())
                .collect(Collectors.toCollection(TreeSet::new));
        assertTrue(officialPanels.containsAll(aiPanels), scenarioId + " panels must map");

        Set<String> workflowIds = stream(aiModel.path("workflows"))
                .map(node -> node.path("workflowId").asText())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> lifecycleWorkflowIds = stream(officialModel.path("concepts"))
                .filter(node -> node.has("lifecycle"))
                .flatMap(node -> stream(node.path("lifecycle").path("states")))
                .map(node -> node.path("metadata").path("beta0WorkflowId").asText())
                .collect(Collectors.toCollection(TreeSet::new));
        assertTrue(lifecycleWorkflowIds.containsAll(workflowIds), scenarioId + " workflows must map to lifecycle metadata");
    }

    private static JsonNode runNormalizerForScenario(String scenarioId) throws Exception {
        Path scenarioOutput = TEST_OUTPUT_ROOT.resolve(scenarioId);
        Files.createDirectories(scenarioOutput);
        Path resultPath = scenarioOutput.resolve("normalizer-result.json");
        ProcessResult process = runProcess(List.of(
                "pwsh",
                "-NoProfile",
                "-File",
                "scripts/ai/Normalize-AiContract.ps1",
                "-ScenarioPath",
                "golden-ai-scenarios/" + scenarioId,
                "-OutputDirectory",
                relativeToWorkspace(scenarioOutput),
                "-ResultPath",
                relativeToWorkspace(resultPath)
        ));
        assertEquals(0, process.exitCode, "normalizer failed for " + scenarioId + "\n" + process.output);
        return readJson(resultPath);
    }

    private static JsonNode runNormalizerForNegativeAiModel(String scenarioId) throws Exception {
        Path scenarioOutput = TEST_OUTPUT_ROOT.resolve(scenarioId);
        Files.createDirectories(scenarioOutput);
        Path aiConfigPath = scenarioOutput.resolve("ai-config.json");
        ObjectNode config = JSON.createObjectNode();
        config.put("schemaVersion", "ai-generator-config.v1");
        config.put("scenario", scenarioId);
        ObjectNode target = config.putObject("target");
        target.put("runtime", "spring-boot");
        target.put("profile", "ai-beta-local");
        ObjectNode database = config.putObject("database");
        database.put("mode", "embedded-test");
        ObjectNode output = config.putObject("output");
        output.put("directory", "out/generated/" + scenarioId);
        JSON.writerWithDefaultPrettyPrinter().writeValue(aiConfigPath.toFile(), config);

        Path resultPath = scenarioOutput.resolve("normalizer-result.json");
        ProcessResult process = runProcess(List.of(
                "pwsh",
                "-NoProfile",
                "-File",
                "scripts/ai/Normalize-AiContract.ps1",
                "-AiModelPath",
                relativeToWorkspace(SCENARIO_ROOT.resolve(scenarioId).resolve("ai-model.json")),
                "-AiConfigPath",
                relativeToWorkspace(aiConfigPath),
                "-OutputDirectory",
                relativeToWorkspace(scenarioOutput.resolve("normalized")),
                "-ResultPath",
                relativeToWorkspace(resultPath)
        ));
        assertTrue(process.exitCode != 0, "negative normalizer scenario should fail: " + scenarioId);
        return readJson(resultPath);
    }

    private static ProcessResult runProcess(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(WORKSPACE_ROOT.toFile())
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(Duration.ofSeconds(90).toMillis(), TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!exited) {
            process.destroyForcibly();
            throw new AssertionError("process timed out: " + command + "\n" + output);
        }
        return new ProcessResult(process.exitValue(), output);
    }

    private static Set<String> collectSchemaPaths(JsonNode schema) {
        Set<String> paths = new TreeSet<>();
        collectSchemaPaths(schema, schema, "", paths);
        return paths;
    }

    private static void collectSchemaPaths(JsonNode root, JsonNode node, String prefix, Set<String> paths) {
        if (node.has("$ref")) {
            node = resolveRef(root, node.path("$ref").asText());
        }
        JsonNode properties = node.path("properties");
        if (properties.isObject()) {
            Iterator<String> names = properties.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                String path = prefix.isBlank() ? name : prefix + "." + name;
                paths.add(path);
                collectSchemaPaths(root, properties.path(name), path, paths);
            }
        }
        if (node.has("items")) {
            collectSchemaPaths(root, node.path("items"), prefix + "[]", paths);
        }
    }

    private static JsonNode resolveRef(JsonNode root, String ref) {
        if (!ref.startsWith("#/")) {
            throw new IllegalArgumentException("Only local refs are supported in this test: " + ref);
        }
        JsonNode node = root;
        for (String part : ref.substring(2).split("/")) {
            node = node.path(part);
        }
        return node;
    }

    private static Set<String> collectInstancePaths(JsonNode node) {
        Set<String> paths = new TreeSet<>();
        collectInstancePaths(node, "", paths);
        return paths;
    }

    private static void collectInstancePaths(JsonNode node, String prefix, Set<String> paths) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                String path = prefix.isBlank() ? name : prefix + "." + name;
                paths.add(path);
                collectInstancePaths(node.path(name), path, paths);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectInstancePaths(item, prefix + "[]", paths);
            }
        }
    }

    private static Set<String> collectPolicyFieldPaths(JsonNode policy) {
        return stream(policy.path("schemaDeclaredFields"))
                .map(node -> node.path("path").asText())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> findDuplicatePolicyFields(JsonNode policy) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new TreeSet<>();
        for (JsonNode field : policy.path("schemaDeclaredFields")) {
            String path = field.path("path").asText();
            if (!seen.add(path)) {
                duplicates.add(path);
            }
        }
        return duplicates;
    }

    private static List<Path> scenarioDirs() throws IOException {
        try (Stream<Path> stream = Files.walk(SCENARIO_ROOT)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("scenario.manifest.json")))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    private static Stream<JsonNode> stream(JsonNode node) {
        List<JsonNode> nodes = new ArrayList<>();
        node.forEach(nodes::add);
        return nodes.stream();
    }

    private static JsonNode readJson(Path path) throws IOException {
        return JSON.readTree(path.toFile());
    }

    private static String relativeToWorkspace(Path path) {
        return WORKSPACE_ROOT.relativize(path.toAbsolutePath().normalize()).toString();
    }

    private static Path resolveWorkspaceRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("schemas/ai/ai-model.schema.json"))) {
            return cwd;
        }
        Path candidate = cwd.resolve("../..").normalize();
        if (Files.exists(candidate.resolve("schemas/ai/ai-model.schema.json"))) {
            return candidate;
        }
        throw new IllegalStateException("Unable to resolve workspace root from " + cwd);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path item : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(item);
            }
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
