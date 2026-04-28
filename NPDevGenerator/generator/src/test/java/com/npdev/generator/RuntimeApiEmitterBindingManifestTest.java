package com.npdev.generator;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import com.npdev.generator.api.GeneratorFacade;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeApiEmitterBindingManifestTest {

    @Test
    void shouldEmitGeneratedBindingManifestAndResolverWiring() throws Exception {
        Path model = Path.of("..", "test-models", "user-minimal", "model.json").normalize();

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-runtime-binding-");
        Path migrations = Files.createTempDirectory("npdev-runtime-binding-migrations-");

        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());
        new GeneratorFacade(templates, writer).generate(compiled, out, migrations, model);

        Path runtimeConfig = out.resolve("src/main/java/com/npdev/generated/runtime/config/NPDevRuntimeConfig.java");
        Path manifestLoader = out.resolve("src/main/java/com/npdev/generated/runtime/config/GeneratedBindingManifestLoader.java");
        Path bindingManifest = out.resolve("src/main/resources/npdev/bindings/dev.bindings.json");
        Path alternateBindingManifest = out.resolve("src/main/resources/npdev/bindings/alt.bindings.json");

        assertTrue(Files.exists(runtimeConfig), "Expected generated runtime config");
        assertTrue(Files.exists(manifestLoader), "Expected generated binding manifest loader");
        assertTrue(Files.exists(bindingManifest), "Expected generated binding manifest resource");
        assertTrue(Files.exists(alternateBindingManifest), "Expected generated alternate binding manifest resource");

        String runtimeConfigContent = Files.readString(runtimeConfig);
        String manifestLoaderContent = Files.readString(manifestLoader);
        String bindingManifestContent = Files.readString(bindingManifest);
        String alternateBindingManifestContent = Files.readString(alternateBindingManifest);

        assertTrue(runtimeConfigContent.contains("@Configuration"),
                "Expected generated runtime config marker");
        assertTrue(runtimeConfigContent.contains("class NPDevRuntimeConfig"),
                "Expected generated runtime config class");
        assertTrue(manifestLoaderContent.contains("load(ObjectMapper objectMapper, String resourcePath)"),
                "Expected generated loader to accept an explicit runtime binding manifest path");
        assertTrue(!manifestLoaderContent.contains("dev.bindings.json"),
                "Expected generated loader to avoid hardcoded binding manifest paths");
        assertTrue(bindingManifestContent.contains("PersistenceCapability"),
                "Expected generated binding manifest to contain persistence binding example");
        assertTrue(alternateBindingManifestContent.contains("notification-warning-inproc"),
                "Expected generated alternate binding manifest to contain warning notification binding");
    }
}
