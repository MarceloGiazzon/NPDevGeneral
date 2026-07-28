package com.npdev.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeApiEmitterMetadataManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldEmitDeterministicMetadataManifestPackage() throws Exception {
        Path model = Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path outOne = Files.createTempDirectory("npdev-runtime-manifests-1-");
        Path outTwo = Files.createTempDirectory("npdev-runtime-manifests-2-");
        Path migrationsOne = Files.createTempDirectory("npdev-runtime-manifests-migrations-1-");
        Path migrationsTwo = Files.createTempDirectory("npdev-runtime-manifests-migrations-2-");

        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        new GeneratorFacade(templates, new GeneratedSourceWriter(outOne, new RegenerationPolicy()))
                .generate(compiled, outOne, migrationsOne, model);
        new GeneratorFacade(templates, new GeneratedSourceWriter(outTwo, new RegenerationPolicy()))
                .generate(compiled, outTwo, migrationsTwo, model);

        Path indexOne = outOne.resolve("src/main/resources/npdev/metadata/index.json");
        Path indexTwo = outTwo.resolve("src/main/resources/npdev/metadata/index.json");
        Path conceptsOne = outOne.resolve("src/main/resources/npdev/metadata/concepts.manifest.json");
        Path fieldsOne = outOne.resolve("src/main/resources/npdev/metadata/fields.manifest.json");
        Path enumsOne = outOne.resolve("src/main/resources/npdev/metadata/enums.manifest.json");
        Path referencesOne = outOne.resolve("src/main/resources/npdev/metadata/references.manifest.json");
        Path proceduresOne = outOne.resolve("src/main/resources/npdev/metadata/procedures.manifest.json");
        Path panelsOne = outOne.resolve("src/main/resources/npdev/metadata/panels.manifest.json");
        Path actionsOne = outOne.resolve("src/main/resources/npdev/metadata/actions.manifest.json");
        Path transitionsOne = outOne.resolve("src/main/resources/npdev/metadata/transitions.manifest.json");
        Path layoutOne = outOne.resolve("src/main/resources/npdev/metadata/layout.manifest.json");
        Path validationOne = outOne.resolve("src/main/resources/npdev/metadata/validation-hints.manifest.json");
        Path invocationsOne = outOne.resolve("src/main/resources/npdev/metadata/invocations.manifest.json");

        for (Path path : List.of(indexOne, conceptsOne, proceduresOne, panelsOne, fieldsOne, enumsOne, referencesOne, actionsOne, transitionsOne, layoutOne, validationOne, invocationsOne)) {
            assertTrue(Files.exists(path), "Expected generated metadata manifest artifact: " + path);
        }

        JsonNode indexRoot = MAPPER.readTree(Files.readString(indexOne));
        assertEquals("1.0.0", indexRoot.path("metadataManifestVersion").asText());
        assertEquals("1.0.0", indexRoot.path("metadataVersion").asText());
        assertEquals(11, indexRoot.path("catalogs").size(), "Expected eleven metadata manifest catalogs (F2.2 added transitions + invocations).");

        JsonNode invocationsRoot = MAPPER.readTree(Files.readString(invocationsOne));
        assertEquals("invocations", invocationsRoot.path("catalog").asText());
        assertTrue(invocationsRoot.path("items").isArray(), "Expected invocations manifest items array.");
        assertTrue(invocationsRoot.path("items").size() > 0, "Expected generated invocations manifest entries.");

        JsonNode transitionsRoot = MAPPER.readTree(Files.readString(transitionsOne));
        assertEquals("transitions", transitionsRoot.path("catalog").asText());
        assertTrue(transitionsRoot.path("items").isArray(), "Expected transitions manifest items array.");
        assertTrue(transitionsRoot.path("items").size() > 0,
                "Expected generated transitions manifest entries (canonical-demo declares an Appointment lifecycle).");

        JsonNode actionsRoot = MAPPER.readTree(Files.readString(actionsOne));
        assertEquals("actions", actionsRoot.path("catalog").asText());
        assertTrue(actionsRoot.path("items").isArray(), "Expected actions manifest items array.");
        assertTrue(actionsRoot.path("items").size() >= 4, "Expected generated actions manifest entries.");
        assertTrue(actionsRoot.toString().contains("Create appointment"),
                "Expected canonical flow action metadata in actions manifest.");

        JsonNode validationRoot = MAPPER.readTree(Files.readString(validationOne));
        assertEquals("validationHints", validationRoot.path("catalog").asText());
        assertEquals("validation", validationRoot.path("sourceCatalog").asText());
        assertTrue(validationRoot.path("items").isArray(), "Expected validation-hints manifest items array.");

        JsonNode proceduresRoot = MAPPER.readTree(Files.readString(proceduresOne));
        assertEquals("procedures", proceduresRoot.path("catalog").asText());
        assertTrue(proceduresRoot.path("items").isArray(), "Expected procedures manifest items array.");

        JsonNode panelsRoot = MAPPER.readTree(Files.readString(panelsOne));
        assertEquals("panels", panelsRoot.path("catalog").asText());
        assertTrue(panelsRoot.path("items").isArray(), "Expected panels manifest items array.");

        assertArrayEquals(Files.readAllBytes(indexOne), Files.readAllBytes(indexTwo),
                "Expected deterministic metadata manifest index emission.");
        assertArrayEquals(Files.readAllBytes(actionsOne),
                Files.readAllBytes(outTwo.resolve("src/main/resources/npdev/metadata/actions.manifest.json")),
                "Expected deterministic actions manifest emission.");
    }
}
