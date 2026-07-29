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

class OfficialSamplesGenerationSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<SampleExpectation> EXPECTATIONS = List.of(
            new SampleExpectation("simple-user-registry", "trial.userregistry", "CreateUser"),
            new SampleExpectation("simple-contact-intake", "trial.contactintake", "SubmitContactMessage"),
            new SampleExpectation("medium-expense-approval", "trial.expenseapproval", "SubmitExpense")
    );

    @Test
    void officialSamplesGenerateStableRuntimeAndUiArtifacts() throws Exception {
        JsonModelParser parser = new JsonModelParser();

        for (SampleExpectation expectation : EXPECTATIONS) {
            Path model = resolveSampleModel(expectation.id());
            ModelAst ast = parser.parse(model);
            List<String> errors = new SemanticValidator().validate(ast);
            assertTrue(errors.isEmpty(), "Expected no validation errors for " + expectation.id() + ", got: " + errors);

            CompiledModel compiled = new ModelCompiler().compile(ast);
            Path out = Files.createTempDirectory("npdev-official-sample-generation-");
            Path migrations = Files.createTempDirectory("npdev-official-sample-migrations-");

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

            for (Path path : List.of(
                    compiledModelPath,
                    compiledMetadataPath,
                    metadataIndexPath,
                    uiIndexPath,
                    uiJsPath,
                    uiCssPath,
                    runtimeOverridesPath,
                    permissionManifestPath,
                    policyManifestPath
            )) {
                assertTrue(Files.exists(path), "Expected generated artifact for " + expectation.id() + ": " + path);
            }

            JsonNode metadataRoot = MAPPER.readTree(Files.readString(compiledMetadataPath));
            assertEquals(expectation.namespace(), metadataRoot.path("namespace").asText(),
                    "Namespace drift in generated metadata for " + expectation.id());
            assertTrue(metadataRoot.path("catalogs").path("actions").isArray(),
                    "Expected actions catalog for " + expectation.id());
            assertTrue(metadataRoot.path("catalogs").path("procedures").isArray(),
                    "Expected procedures catalog for " + expectation.id());
            assertTrue(metadataRoot.path("catalogs").path("panels").isArray(),
                    "Expected panels catalog for " + expectation.id());
            assertTrue(metadataRoot.path("catalogs").path("actions").findValuesAsText("name").contains(expectation.mainFlow()),
                    "Expected action catalog to include main flow for " + expectation.id());

            JsonNode metadataIndex = MAPPER.readTree(Files.readString(metadataIndexPath));
            assertEquals(11, metadataIndex.path("catalogs").size(),
                    "Metadata manifest catalog count drift for " + expectation.id());

            String compiledModelJson = Files.readString(compiledModelPath);
            assertTrue(compiledModelJson.contains("\"" + expectation.mainFlow() + "\""),
                    "Expected generated compiled model to include main flow for " + expectation.id());
        }
    }

    private static Path resolveSampleModel(String sampleId) {
        return Path.of("..", "resources", "Models", "official-samples", sampleId, "model.json").normalize();
    }

    private record SampleExpectation(String id, String namespace, String mainFlow) {
    }
}
