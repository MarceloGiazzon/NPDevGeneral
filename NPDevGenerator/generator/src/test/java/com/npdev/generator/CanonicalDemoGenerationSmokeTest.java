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

class CanonicalDemoGenerationSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void canonicalDemoGenerationEmitsStableRuntimeAndUiArtifacts() throws Exception {
        Path model = Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-canonical-demo-generation-");
        Path migrations = Files.createTempDirectory("npdev-canonical-demo-migrations-");

        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());
        new GeneratorFacade(templates, writer).generate(compiled, out, migrations, model);

        Path compiledModelPath = out.resolve("src/main/resources/npdev/compiled-model.json");
        Path compiledMetadataPath = out.resolve("src/main/resources/npdev/compiled-metadata.json");
        Path metadataIndexPath = out.resolve("src/main/resources/npdev/metadata/index.json");
        Path uiIndexPath = out.resolve("src/main/resources/static/npdev-ui-react/index.html");
        Path uiJsPath = out.resolve("src/main/resources/static/npdev-ui-react/assets/app.js");
        Path uiCssPath = out.resolve("src/main/resources/static/npdev-ui-react/assets/app.css");
        Path runtimeOverridesPath = out.resolve("src/main/resources/npdev/runtime/dev.runtime.json");
        Path permissionManifestPath = out.resolve("src/main/resources/npdev/security/dev.permissions.json");
        Path policyManifestPath = out.resolve("src/main/resources/npdev/security/dev.ui-metadata-policy.json");
        Path pluginIndexPath = out.resolve("src/main/resources/npdev/plugin-packages/index.json");

        for (Path path : List.of(
                compiledModelPath,
                compiledMetadataPath,
                metadataIndexPath,
                uiIndexPath,
                uiJsPath,
                uiCssPath,
                runtimeOverridesPath,
                permissionManifestPath,
                policyManifestPath,
                pluginIndexPath
        )) {
            assertTrue(Files.exists(path), "Expected generated artifact: " + path);
        }

        JsonNode metadataRoot = MAPPER.readTree(Files.readString(compiledMetadataPath));
        assertEquals("canonical.clinicdemo", metadataRoot.path("namespace").asText());
        assertEquals(4, metadataRoot.path("catalogs").path("concepts").size());
        assertEquals(9, metadataRoot.path("catalogs").path("actions").size());

        JsonNode metadataIndex = MAPPER.readTree(Files.readString(metadataIndexPath));
        assertEquals(9, metadataIndex.path("catalogs").size());
        assertTrue(metadataRoot.path("catalogs").path("procedures").isArray(),
                "Expected procedure catalog in generated metadata.");
        assertTrue(metadataRoot.path("catalogs").path("panels").isArray(),
                "Expected panel catalog in generated metadata.");

        String compiledModelJson = Files.readString(compiledModelPath);
        assertTrue(compiledModelJson.contains("\"CreateAppointment\""),
                "Expected generated compiled model to include the canonical flow.");
    }
}
