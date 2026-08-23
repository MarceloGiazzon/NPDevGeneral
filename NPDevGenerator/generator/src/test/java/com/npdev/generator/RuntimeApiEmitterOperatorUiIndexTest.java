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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeApiEmitterOperatorUiIndexTest {

    @Test
    void theOperatorUiNoLongerAdvertisesTheDeletedReactRoute() throws Exception {
        Path model = Path.of("..", "resources", "Models", "canonical-demo", "model.json").normalize();

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-runtime-ui-index-");
        Path migrations = Files.createTempDirectory("npdev-runtime-ui-index-migrations-");

        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());
        new GeneratorFacade(templates, writer).generate(compiled, out, migrations, model);

        Path indexPath = out.resolve("src/main/resources/static/npdev-ui/index.html");
        assertTrue(Files.exists(indexPath), "Expected generated operator UI index page.");

        // ed160b85 (2026-08-20) deleted the /npdev-ui-react/ bundle and its redirect, but the
        // emitter kept threading the route into this page, so every generated app shipped a button
        // that 404s. Negative assertion so re-adding it fails here rather than in a user's browser.
        String html = Files.readString(indexPath);
        assertFalse(html.contains("/npdev-ui-react/"),
                "the operator UI must not link to the deleted React editor route");
    }
}
