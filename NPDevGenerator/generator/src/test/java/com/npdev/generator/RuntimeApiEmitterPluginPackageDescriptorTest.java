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

class RuntimeApiEmitterPluginPackageDescriptorTest {

    @Test
    void shouldEmitProjectedPluginPackageDescriptorsFromNpResourcesDeterministically() throws Exception {
        Path model = Path.of("..", "test-models", "user-minimal", "model.json").normalize();

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path outOne = Files.createTempDirectory("npdev-runtime-plugin-package-1-");
        Path outTwo = Files.createTempDirectory("npdev-runtime-plugin-package-2-");
        Path migrationsOne = Files.createTempDirectory("npdev-runtime-plugin-package-migrations-1-");
        Path migrationsTwo = Files.createTempDirectory("npdev-runtime-plugin-package-migrations-2-");

        TemplateEngine templates = new TemplateEngine("npdev-templates/");

        new GeneratorFacade(templates, new GeneratedSourceWriter(outOne, new RegenerationPolicy()))
                .generate(compiled, outOne, migrationsOne, model);
        new GeneratorFacade(templates, new GeneratedSourceWriter(outTwo, new RegenerationPolicy()))
                .generate(compiled, outTwo, migrationsTwo, model);

        Path projectedInProc = outOne.resolve("src/main/resources/npdev/plugin-packages/notification-inproc.package.json");
        Path projectedWarning = outOne.resolve("src/main/resources/npdev/plugin-packages/notification-warning.package.json");
        Path projectedIncompatible = outOne.resolve("src/main/resources/npdev/plugin-packages/notification-incompatible.package.json");
        Path projectedIndex = outOne.resolve("src/main/resources/npdev/plugin-packages/index.json");
        Path projectedInProcTwo = outTwo.resolve("src/main/resources/npdev/plugin-packages/notification-inproc.package.json");
        Path projectedWarningTwo = outTwo.resolve("src/main/resources/npdev/plugin-packages/notification-warning.package.json");
        Path projectedIncompatibleTwo = outTwo.resolve("src/main/resources/npdev/plugin-packages/notification-incompatible.package.json");
        Path projectedIndexTwo = outTwo.resolve("src/main/resources/npdev/plugin-packages/index.json");
        Path sourceInProc = resolvePluginPackageSource("notification-inproc.package.json");
        Path sourceWarning = resolvePluginPackageSource("notification-warning.package.json");
        Path sourceIncompatible = resolvePluginPackageSource("notification-incompatible.package.json");

        assertTrue(Files.exists(projectedInProc), "Expected generated inproc package descriptor resource");
        assertTrue(Files.exists(projectedWarning), "Expected generated warning package descriptor resource");
        assertTrue(Files.exists(projectedIncompatible), "Expected generated incompatible package descriptor resource");
        assertTrue(Files.exists(projectedIndex), "Expected generated plugin package index resource");

        assertEquals(Files.readString(sourceInProc), Files.readString(projectedInProc),
                "Expected generated inproc package descriptor to match NP canonical source");
        assertEquals(Files.readString(sourceWarning), Files.readString(projectedWarning),
                "Expected generated warning package descriptor to match NP canonical source");
        assertEquals(Files.readString(sourceIncompatible), Files.readString(projectedIncompatible),
                "Expected generated incompatible package descriptor to match NP canonical source");
        assertTrue(Files.readString(projectedIndex).contains("notification-inproc.package.json"),
                "Expected package index to include inproc descriptor");
        assertTrue(Files.readString(projectedIndex).contains("notification-warning.package.json"),
                "Expected package index to include warning descriptor");
        assertTrue(Files.readString(projectedIndex).contains("notification-incompatible.package.json"),
                "Expected package index to include incompatible descriptor");

        assertArrayEquals(Files.readAllBytes(projectedInProc), Files.readAllBytes(projectedInProcTwo),
                "Expected deterministic inproc package descriptor emission");
        assertArrayEquals(Files.readAllBytes(projectedWarning), Files.readAllBytes(projectedWarningTwo),
                "Expected deterministic warning package descriptor emission");
        assertArrayEquals(Files.readAllBytes(projectedIncompatible), Files.readAllBytes(projectedIncompatibleTwo),
                "Expected deterministic incompatible package descriptor emission");
        assertArrayEquals(Files.readAllBytes(projectedIndex), Files.readAllBytes(projectedIndexTwo),
                "Expected deterministic package index emission");
    }

    private static Path resolvePluginPackageSource(String fileName) {
        Path[] candidates = new Path[] {
                Path.of("resources", "PluginPackages", fileName),
                Path.of("..", "resources", "PluginPackages", fileName)
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("Plugin package source not found for test: " + fileName);
    }
}
