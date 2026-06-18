package com.npdev.generator.packs;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinPackComposerTest {

    private static Path packsDir() {
        return Path.of("..", "..", "NPDevContract", "packs").normalize();
    }

    @Test
    void loadsConceptsFromRealBuiltinPacks() {
        BuiltinPackComposer composer = new BuiltinPackComposer();
        List<CompiledConcept> identity =
                composer.loadPackConcepts(packsDir().resolve("identity").resolve("pack.json"), "identity");
        List<CompiledConcept> workspace =
                composer.loadPackConcepts(packsDir().resolve("workspace").resolve("pack.json"), "workspace");

        assertTrue(identity.stream().anyMatch(c -> "identity::User".equals(c.getName())));
        assertTrue(identity.stream().anyMatch(c -> "identity::Role".equals(c.getName())));
        assertTrue(identity.stream().anyMatch(c -> "identity::UserRole".equals(c.getName())));
        assertTrue(workspace.stream().anyMatch(c -> "workspace::Menu".equals(c.getName())));
        assertTrue(workspace.stream().anyMatch(c -> "workspace::Preference".equals(c.getName())));
    }

    @Test
    void mergedBuiltinTablesAreEmittedAsEntities() throws Exception {
        Path appModel = Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();
        ModelAst ast = new JsonModelParser().parse(appModel);
        CompiledModel app = new ModelCompiler().compile(ast);

        BuiltinPackComposer composer = new BuiltinPackComposer();
        List<CompiledConcept> builtin = new ArrayList<>();
        builtin.addAll(composer.loadPackConcepts(packsDir().resolve("identity").resolve("pack.json"), "identity"));
        builtin.addAll(composer.loadPackConcepts(packsDir().resolve("workspace").resolve("pack.json"), "workspace"));
        CompiledModel merged = composer.merge(app, builtin);

        assertTrue(merged.findConcept("identity::User").isPresent());
        assertTrue(merged.findConcept("workspace::Menu").isPresent());

        Path out = Files.createTempDirectory("npdev-compose-out-");
        Path migrations = Files.createTempDirectory("npdev-compose-mig-");
        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .generate(merged, out, migrations, appModel);

        assertTrue(Files.exists(out.resolve("src/main/java/com/npdev/generated/entities/IdentityUser.java")),
                "Composed identity::User should be emitted as an entity");
        assertTrue(Files.exists(out.resolve("src/main/java/com/npdev/generated/entities/WorkspaceMenu.java")),
                "Composed workspace::Menu should be emitted as an entity");
    }
}
