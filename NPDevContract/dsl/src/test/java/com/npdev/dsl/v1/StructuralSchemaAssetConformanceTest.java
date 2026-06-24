package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuralSchemaAssetConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void canonicalSchemaCopiesStayAligned() throws Exception {
        assertEquals(
                normalizeSchema(readJson(resolveCanonicalModelSchema())),
                normalizeSchema(readJson(resolveClasspathModelSchema())),
                "Classpath model schema copy must stay aligned with canonical model schema."
        );
    }

    /**
     * The model schema exists as four on-disk copies (canonical, classpath, NPDevContract/schemas,
     * and NPDevContract/schemas/authoring) because different consumers resolve it from different
     * relative roots. {@link #canonicalSchemaCopiesStayAligned()} only ever checked two of the
     * four -- the other two ({@code NPDevContract/schemas/model.schema.json} and its
     * {@code authoring/} mirror) drifted silently for weeks (missing {@code renamedFrom} and the
     * event {@code mode} field) before anything caught it. This test checks every known copy
     * against the same canonical source, so a future addition can't repeat that.
     */
    @Test
    void allKnownModelSchemaCopiesStayAligned() throws Exception {
        JsonNode canonical = normalizeSchema(readJson(resolveCanonicalModelSchema()));
        for (Path copy : resolveAllModelSchemaCopies()) {
            assertEquals(
                    canonical,
                    normalizeSchema(readJson(copy)),
                    "Model schema copy at " + copy + " must stay aligned with the canonical model schema."
            );
        }
    }

    @Test
    void canonicalDemoAndOfficialSampleModelsValidateStructurally() throws Exception {
        JsonSchema schema = readSchema(resolveCanonicalModelSchema());
        List<Path> modelPaths = collectModelPaths();
        assertFalse(modelPaths.isEmpty(), "Expected canonical demo plus official sample models to exist.");

        for (Path modelPath : modelPaths) {
            Set<ValidationMessage> violations = schema.validate(readJson(modelPath));
            assertTrue(
                    violations.isEmpty(),
                    "Expected structurally valid model at " + modelPath + ", got: " + violations
            );
        }
    }

    @Test
    void canonicalDemoAndOfficialSampleConfigsValidateStructurally() throws Exception {
        JsonSchema schema = readSchema(resolveCanonicalConfigSchema());
        List<Path> configPaths = collectConfigPaths();
        assertFalse(configPaths.isEmpty(), "Expected canonical demo plus official sample configs to exist.");

        for (Path configPath : configPaths) {
            Set<ValidationMessage> violations = schema.validate(readJson(configPath));
            assertTrue(
                    violations.isEmpty(),
                    "Expected structurally valid config at " + configPath + ", got: " + violations
            );
        }
    }

    @Test
    void malformedArbitraryConfigReceivesUsefulStructuralErrors() throws Exception {
        JsonSchema schema = readSchema(resolveCanonicalConfigSchema());
        JsonNode malformedConfig = MAPPER.readTree("""
                {
                  "configVersion": "1.0",
                  "generator": {
                    "failIfModelMissing": true
                  }
                }
                """);

        Set<ValidationMessage> violations = schema.validate(malformedConfig);
        assertFalse(violations.isEmpty(), "Expected malformed arbitrary config to be rejected structurally.");

        String messageText = violations.toString().toLowerCase();
        assertTrue(
                messageText.contains("required")
                        || messageText.contains("property")
                        || messageText.contains("propriedade")
                        || messageText.contains("obrigatória"),
                "Expected useful structural error messaging, got: " + violations
        );
    }

    private static List<Path> collectModelPaths() throws IOException {
        Path canonicalDemo = resolvePath(List.of(
                Path.of("resources", "Models", "canonical-demo", "model.json"),
                Path.of("..", "resources", "Models", "canonical-demo", "model.json")
        ));
        Path officialSamplesRoot = resolvePath(List.of(
                Path.of("resources", "Models", "official-samples"),
                Path.of("..", "resources", "Models", "official-samples")
        ));

        try (Stream<Path> sampleDirs = Files.list(officialSamplesRoot)) {
            List<Path> officialModels = sampleDirs
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> path.resolve("model.json"))
                    .toList();

            return Stream.concat(Stream.of(canonicalDemo), officialModels.stream()).toList();
        }
    }

    private static List<Path> collectConfigPaths() throws IOException {
        Path canonicalDemo = resolvePath(List.of(
                Path.of("resources", "Models", "canonical-demo", "config.json"),
                Path.of("..", "resources", "Models", "canonical-demo", "config.json")
        ));
        Path officialSamplesRoot = resolvePath(List.of(
                Path.of("resources", "Models", "official-samples"),
                Path.of("..", "resources", "Models", "official-samples")
        ));

        try (Stream<Path> sampleDirs = Files.list(officialSamplesRoot)) {
            List<Path> officialConfigs = sampleDirs
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> path.resolve("config.json"))
                    .toList();

            return Stream.concat(Stream.of(canonicalDemo), officialConfigs.stream()).toList();
        }
    }

    private static JsonSchema readSchema(Path path) throws Exception {
        String schemaJson = Files.readString(path);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        return factory.getSchema(schemaJson);
    }

    private static JsonNode readJson(Path path) throws IOException {
        return MAPPER.readTree(Files.readString(path));
    }

    private static JsonNode normalizeSchema(JsonNode schema) {
        JsonNode copy = schema.deepCopy();
        if (copy instanceof ObjectNode objectNode) {
            objectNode.remove(List.of("deprecated", "replacedBy", "canonicalSchema"));
        }
        return copy;
    }

    private static Path resolveCanonicalModelSchema() {
        return resolvePath(List.of(
                Path.of("resources", "Schemas", "model.schema.json"),
                Path.of("..", "resources", "Schemas", "model.schema.json")
        ));
    }

    private static Path resolveClasspathModelSchema() {
        return resolvePath(List.of(
                Path.of("src", "main", "resources", "schema", "model.schema.json")
        ));
    }

    /** Every known on-disk copy of model.schema.json other than the canonical one itself. */
    private static List<Path> resolveAllModelSchemaCopies() {
        return List.of(
                resolveClasspathModelSchema(),
                resolvePath(List.of(
                        Path.of("..", "schemas", "model.schema.json")
                )),
                resolvePath(List.of(
                        Path.of("..", "schemas", "authoring", "model.schema.json")
                ))
        );
    }

    private static Path resolveCanonicalConfigSchema() {
        return resolvePath(List.of(
                Path.of("resources", "Schemas", "config.schema.json"),
                Path.of("..", "resources", "Schemas", "config.schema.json")
        ));
    }

    private static Path resolvePath(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("Unable to resolve required structural schema asset path from candidates: "
                + candidates);
    }
}
