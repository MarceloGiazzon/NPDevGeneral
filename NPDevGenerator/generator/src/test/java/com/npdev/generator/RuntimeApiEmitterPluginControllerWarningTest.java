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
 * Wave 4 (BOUNDARY_LIFT_PLAN_2026-09-02.md package 4.4, B30-B): proves the generator itself warns,
 * at generation time, for every {@code plugin:java-controller} mount -- the deliverable of that
 * package is the trusted-tier-by-design DECISION (recorded in {@code docs/ACCEPTED_BOUNDARIES.md}
 * B13/B30), and this is the code that makes an author actually see it, rather than only an operator
 * who later reads the doc. {@link RuntimeApiEmitter#emitJavaControllerMounts} prints one line per
 * mounted controller naming its capability -- unlike {@code plugin:java-source} (real OS-process
 * isolation + a memory/CPU ceiling since SEC-5), a mounted controller runs IN-PROCESS with the
 * application's full privileges and is never sandboxed.
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
        assertTrue(console.contains("TRUSTED tier by design"), console);
        assertTrue(console.contains("docs/ACCEPTED_BOUNDARIES.md#B30"), console);
    }
}
