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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeApiEmitterCompiledMetadataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldEmitCompiledMetadataResourceAlongsideCompiledModel() throws Exception {
        Path model = Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-runtime-metadata-");
        Path migrations = Files.createTempDirectory("npdev-runtime-metadata-migrations-");

        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());
        new GeneratorFacade(templates, writer).generate(compiled, out, migrations, model);

        Path compiledModelPath = out.resolve("src/main/resources/npdev/compiled-model.json");
        Path compiledMetadataPath = out.resolve("src/main/resources/npdev/compiled-metadata.json");

        assertTrue(Files.exists(compiledModelPath), "Expected generated compiled-model resource.");
        assertTrue(Files.exists(compiledMetadataPath), "Expected generated compiled-metadata resource.");

        JsonNode metadataRoot = MAPPER.readTree(Files.readString(compiledMetadataPath));
        assertEquals("1.0.0", metadataRoot.path("metadataVersion").asText());
        assertTrue(metadataRoot.path("catalogs").path("layout").isArray(),
                "Expected layout catalog in generated compiled metadata.");
        assertTrue(metadataRoot.path("catalogs").path("validation").isArray(),
                "Expected validation catalog in generated compiled metadata.");
        assertTrue(metadataRoot.path("catalogs").path("procedures").isArray(),
                "Expected procedure catalog in generated compiled metadata.");
        assertTrue(metadataRoot.path("catalogs").path("panels").isArray(),
                "Expected panel catalog in generated compiled metadata.");
        assertTrue(metadataRoot.path("catalogs").path("actions").size() >= 3,
                "Expected flow and orchestration action metadata in generated compiled metadata.");
    }
}
