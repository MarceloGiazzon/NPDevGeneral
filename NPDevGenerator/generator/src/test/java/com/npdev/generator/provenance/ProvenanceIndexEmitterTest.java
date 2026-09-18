package com.npdev.generator.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S14 (NPDEV_MEGA_ROADMAP.md, Track B): pins the provenance index -- for every persisted concept,
 * the emitted Spring classes, the schema migration, and the frontend route, each with a SHA-256
 * digest; and byte-identical on regeneration.
 */
class ProvenanceIndexEmitterTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void emitsIndexMappingConceptsToArtifacts() throws Exception {
        Path out = generate("Pigment");
        Path index = out.resolve(ProvenanceIndexEmitter.RELATIVE_PATH);
        assertTrue(Files.exists(index), "provenance-index.json must be emitted: " + index);

        JsonNode root = OBJECT_MAPPER.readTree(Files.readString(index));
        assertEquals("npdev-provenance-index.v1", root.get("schemaVersion").asText());
        JsonNode pigment = root.get("specNodes").get("Pigment");
        assertTrue(pigment != null, "index must have a spec node for Pigment");

        Map<String, String> typeByPath = new java.util.LinkedHashMap<>();
        for (JsonNode artifact : pigment.get("artifacts")) {
            String path = artifact.get("path").asText();
            String type = artifact.get("type").asText();
            String digest = artifact.get("digest").asText();
            assertTrue(digest.startsWith("sha256:"), "digest must be sha256-prefixed: " + digest);
            assertEquals(sha256(Files.readAllBytes(out.resolve(path))), digest,
                    "digest must equal the SHA-256 of the emitted file: " + path);
            typeByPath.put(path, type);
        }

        assertTrue(typeByPath.containsKey("src/main/java/com/npdev/generated/entities/Pigment.java"),
                "index must map Pigment -> generated entity");
        assertTrue(typeByPath.containsKey("src/main/java/com/npdev/generated/dtos/PigmentCreateRequest.java"),
                "index must map Pigment -> create DTO");
        assertTrue(typeByPath.containsKey("src/main/java/com/npdev/generated/services/PigmentServiceBase.java"),
                "index must map Pigment -> service base");
        assertTrue(typeByPath.containsKey("src/main/java/com/npdev/generated/controllers/PigmentControllerBase.java"),
                "index must map Pigment -> controller base");

        List<JsonNode> schemaArtifacts = new ArrayList<>();
        List<JsonNode> routeArtifacts = new ArrayList<>();
        for (JsonNode artifact : pigment.get("artifacts")) {
            if ("schema-migration".equals(artifact.get("type").asText())) {
                schemaArtifacts.add(artifact);
            }
            if ("frontend-route".equals(artifact.get("type").asText())) {
                routeArtifacts.add(artifact);
            }
        }
        // The V1 schema-realization script is only emitted for jdbc plans; the in-memory default
        // legacy plan omits it. The index must map the schema when it exists and omit it when not.
        Path schemaScript = out.resolve("src/main/resources/db/schema-realization/V1__npdev_schema_realization.sql");
        if (Files.exists(schemaScript)) {
            assertEquals(1, schemaArtifacts.size(),
                    "index must map Pigment -> its CREATE TABLE in the schema migration");
            assertEquals("CREATE TABLE pigments", schemaArtifacts.get(0).get("note").asText());
        } else {
            assertEquals(0, schemaArtifacts.size(),
                    "index must omit the schema-migration artifact when no jdbc script was emitted");
        }
        assertEquals(1, routeArtifacts.size(),
                "index must map Pigment -> its frontend route in the generated UI manifest");

        JsonNode nodeCount = root.get("nodeCount");
        assertTrue(nodeCount.asInt() >= 1, "nodeCount must be at least 1");
    }

    @Test
    void regenerationProducesByteIdenticalIndex() throws Exception {
        Path out1 = generate("Pigment");
        Path out2 = generate("Pigment");

        Path index1 = out1.resolve(ProvenanceIndexEmitter.RELATIVE_PATH);
        Path index2 = out2.resolve(ProvenanceIndexEmitter.RELATIVE_PATH);
        assertTrue(Files.exists(index1) && Files.exists(index2));

        byte[] first = Files.readAllBytes(index1);
        byte[] second = Files.readAllBytes(index2);
        assertEquals(new String(first, StandardCharsets.UTF_8),
                new String(second, StandardCharsets.UTF_8),
                "provenance-index.json must be byte-identical across generations (determinism)");
    }

    @Test
    void absentArtifactsAreOmittedNotFabricated() throws Exception {
        Path out = generate("Widget");
        Path index = out.resolve(ProvenanceIndexEmitter.RELATIVE_PATH);
        JsonNode root = OBJECT_MAPPER.readTree(Files.readString(index));

        Path removed = out.resolve("src/main/java/com/npdev/generated/entities/Widget.java");
        Files.delete(removed);
        // Re-emit after deleting one artifact: it must disappear from the index rather than
        // remain a stale entry pointing at a file that no longer exists.
        new ProvenanceIndexEmitter().emit(compileModel("Widget"), out);

        JsonNode widget = OBJECT_MAPPER.readTree(Files.readString(index)).get("specNodes").get("Widget");
        assertNotEquals(null, widget, "Widget spec node must exist");
        for (JsonNode artifact : widget.get("artifacts")) {
            assertNotEquals("src/main/java/com/npdev/generated/entities/Widget.java",
                    artifact.get("path").asText(),
                    "deleted artifact must be omitted from the re-emitted index");
        }
    }

    private static Path generate(String conceptName) throws Exception {
        Path model = writeModel(Files.createTempFile("npdev-prov-model-", ".json"), conceptName);
        Path out = Files.createTempDirectory("npdev-prov-");
        Path migrations = Files.createTempDirectory("npdev-prov-migrations-");
        CompiledModel compiled = compileModel(conceptName);
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());
        new GeneratorFacade(templates, writer).generate(compiled, out, migrations, model);
        return out;
    }

    private static CompiledModel compileModel(String conceptName) throws Exception {
        Path model = writeModel(Files.createTempFile("npdev-model-", ".json"), conceptName);
        ModelAst ast = new JsonModelParser().parse(model);
        return new ModelCompiler().compile(ast);
    }

    private static Path writeModel(Path path, String conceptName) throws Exception {
        Files.writeString(path, """
                {
                  "namespace": "prov.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "%s",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true }
                      ]
                    }
                  ]
                }
                """.formatted(conceptName), StandardCharsets.UTF_8);
        return path;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}