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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the generator itself warns, at generation time, for every {@code plugin:java-controller}
 * mount, so an author sees the containment posture rather than only an operator who later reads the
 * doc. {@link RuntimeApiEmitter#emitJavaControllerMounts} prints one line per mounted controller
 * naming its capability. Since SEC-9 a mounted controller runs in the isolated plugin worker pool, so
 * the line states what that does and does not cover (SBX-1) and points at
 * {@code docs/reference/PLUGIN_SANDBOX.md}.
 */
class RuntimeApiEmitterPluginControllerWarningTest {

    @Test
    void warnsOncePerMountedPluginControllerNamingItsCapability() throws Exception {
        Path model = Path.of("..", "..", "NPDevSamples", "probes", "p7-plugin-controller", "Input", "model.json").normalize();
        assertTrue(Files.exists(model), "expected p7-plugin-controller sample at " + model.toAbsolutePath());

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-runtime-plugin-controller-warning-");
        Path migrations = Files.createTempDirectory("npdev-runtime-plugin-controller-warning-migrations-");
        TemplateEngine templates = new TemplateEngine("npdev-templates/");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            new GeneratorFacade(templates, new GeneratedSourceWriter(out, new RegenerationPolicy()))
                    .generate(compiled, out, migrations, model);
        } finally {
            System.setOut(originalOut);
        }

        String console = captured.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("WARNING (B30) -- plugin:java-controller 'adminTools'"),
                "expected a B30 trusted-tier warning naming 'adminTools':\n" + console);
        assertTrue(console.contains("WARNING (B30) -- plugin:java-controller 'superOnlyTools'"),
                "expected a B30 trusted-tier warning naming 'superOnlyTools':\n" + console);
        assertTrue(console.contains("isolated plugin worker process"), console);
        assertTrue(console.contains("Linux-only"), console);
        assertTrue(console.contains("docs/reference/PLUGIN_SANDBOX.md"), console);
    }
}
