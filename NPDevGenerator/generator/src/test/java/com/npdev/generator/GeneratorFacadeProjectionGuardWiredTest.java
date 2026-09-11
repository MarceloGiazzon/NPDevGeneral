package com.npdev.generator;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path A P0.2: {@code GeneratedProjectionGuard} existed with three forbidden patterns and a
 * passing unit test, but was called from zero production code paths -- the flagship
 * constitutional guardrail had never run on a real generation. This proves it now does: a
 * planted violation under {@code outRoot/src/main/java} fails a real {@link GeneratorFacade}
 * generation, and a clean generation still succeeds.
 */
class GeneratorFacadeProjectionGuardWiredTest {

    private static Path canonicalDemoModel() {
        return Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();
    }

    private static CompiledModel compileCanonicalDemo() throws Exception {
        ModelAst ast = new JsonModelParser().parse(canonicalDemoModel());
        return new ModelCompiler().compile(ast);
    }

    @Test
    void plantedAdapterInstantiationFailsGeneration() throws Exception {
        CompiledModel compiled = compileCanonicalDemo();
        Path out = Files.createTempDirectory("npdev-guard-wired-red-");
        Path migrations = Files.createTempDirectory("npdev-guard-wired-red-mig-");

        // Simulate a stray/hand-authored source already sitting under the projection before this
        // generation pass runs -- the guard scans the whole src/main/java tree, so it must catch
        // this even though no emitter in this pass wrote it.
        Path planted = out.resolve("src/main/java/com/npdev/generated/services/PlantedViolation.java");
        Files.createDirectories(planted.getParent());
        Files.writeString(planted, """
                package com.npdev.generated.services;

                public class PlantedViolation {
                    public void save() {
                        new NotificationCapabilityAdapter();
                    }
                }
                """);

        GeneratorFacade facade = new GeneratorFacade(
                new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> facade.generate(compiled, out, migrations, canonicalDemoModel()));
        assertTrue(ex.getMessage().contains("thin-projection guard"),
                "expected the projection guard's own message, got: " + ex.getMessage());
    }

    @Test
    void cleanGenerationStillSucceeds() throws Exception {
        CompiledModel compiled = compileCanonicalDemo();
        Path out = Files.createTempDirectory("npdev-guard-wired-green-");
        Path migrations = Files.createTempDirectory("npdev-guard-wired-green-mig-");

        GeneratorFacade facade = new GeneratorFacade(
                new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()));

        assertDoesNotThrow(() -> facade.generate(compiled, out, migrations, canonicalDemoModel()));
    }
}
