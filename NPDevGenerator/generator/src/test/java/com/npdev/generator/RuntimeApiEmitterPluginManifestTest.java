package com.npdev.generator;

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

class RuntimeApiEmitterPluginManifestTest {

    @Test
    void shouldEmitProjectedPluginManifestsFromNpResourcesDeterministically() throws Exception {
        Path model = Path.of("..", "test-models", "user-minimal", "model.json").normalize();

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path outOne = Files.createTempDirectory("npdev-runtime-plugin-1-");
        Path outTwo = Files.createTempDirectory("npdev-runtime-plugin-2-");
        Path migrationsOne = Files.createTempDirectory("npdev-runtime-plugin-migrations-1-");
        Path migrationsTwo = Files.createTempDirectory("npdev-runtime-plugin-migrations-2-");

        TemplateEngine templates = new TemplateEngine("npdev-templates/");

        new GeneratorFacade(templates, new GeneratedSourceWriter(outOne, new RegenerationPolicy()))
                .generate(compiled, outOne, migrationsOne, model);
        new GeneratorFacade(templates, new GeneratedSourceWriter(outTwo, new RegenerationPolicy()))
                .generate(compiled, outTwo, migrationsTwo, model);

        Path projectedDefault = outOne.resolve("src/main/resources/npdev/plugins/default.plugin-manifest.json");
        Path projectedWarning = outOne.resolve("src/main/resources/npdev/plugins/warning.plugin-manifest.json");
        Path projectedDefaultTwo = outTwo.resolve("src/main/resources/npdev/plugins/default.plugin-manifest.json");
        Path projectedWarningTwo = outTwo.resolve("src/main/resources/npdev/plugins/warning.plugin-manifest.json");
        Path sourceDefault = resolvePluginSource("default.plugin-manifest.json");
        Path sourceWarning = resolvePluginSource("warning.plugin-manifest.json");

        assertTrue(Files.exists(projectedDefault), "Expected generated default plugin manifest resource");
        assertTrue(Files.exists(projectedWarning), "Expected generated warning plugin manifest resource");

        assertEquals(Files.readString(sourceDefault), Files.readString(projectedDefault),
                "Expected generated default plugin manifest to match NP canonical source");
        assertEquals(Files.readString(sourceWarning), Files.readString(projectedWarning),
                "Expected generated warning plugin manifest to match NP canonical source");

        assertArrayEquals(Files.readAllBytes(projectedDefault), Files.readAllBytes(projectedDefaultTwo),
                "Expected deterministic default plugin manifest emission");
        assertArrayEquals(Files.readAllBytes(projectedWarning), Files.readAllBytes(projectedWarningTwo),
                "Expected deterministic warning plugin manifest emission");
    }

    private static Path resolvePluginSource(String fileName) {
        Path[] candidates = new Path[] {
                Path.of("resources", "Plugins", fileName),
                Path.of("..", "resources", "Plugins", fileName)
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("Plugin manifest source not found for test: " + fileName);
    }
}
