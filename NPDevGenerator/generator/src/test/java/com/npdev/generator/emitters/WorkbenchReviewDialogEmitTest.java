package com.npdev.generator.emitters;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI-7: a workbench action declared with {@code review: true} opens a field-by-field "proposed
 * changes" dialog instead of patching the draft blindly. The dialog lives in its own static script,
 * emitted into every app next to shell.js; the workbench page loads it and routes the plain-patch
 * branch through it.
 */
class WorkbenchReviewDialogEmitTest {

    private static final String MODEL = """
            {
              "namespace": "review.dialog.demo",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "concepts": [
                { "name": "Widget", "ui": { "label": "Widget" },
                  "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """;

    @Test
    void reviewScriptIsEmittedNextToTheShell(@TempDir Path tempDir) throws Exception {
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, MODEL);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-review-ui-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(compiled, "ADMIN", new SettingResolver(SettingStore.empty()));

        Path script = out.resolve("src/main/resources/static/npdev-review.js");
        assertTrue(Files.exists(script), "static/npdev-review.js must be written for every app");
        String js = Files.readString(script);
        assertTrue(js.contains("window.NPDevReview = { open: open };"), js);
        assertTrue(js.contains("Apply selected"), "the dialog offers an Apply selected button");
        assertFalse(js.contains("{{"), "no unrendered mustache tags in the emitted script");
    }

    @Test
    void workbenchPageLoadsTheScriptAndRoutesReviewActionsThroughIt() {
        String page = new TemplateEngine("npdev-templates/").render("workbench-page.html.mustache", Map.of());

        assertTrue(page.contains("<script src=\"/npdev-review.js\"></script>"),
                "the workbench page must load the review dialog script");
        assertTrue(page.contains("action.review && window.NPDevReview"),
                "the plain-patch branch must check the action's review flag");
        assertTrue(page.contains("NPDevReview.open("),
                "a review action must open the dialog instead of patching the draft");
        assertTrue(page.contains("store.patch(accepted)"),
                "only the ticked fields are folded into the draft");
    }
}
