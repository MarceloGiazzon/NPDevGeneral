package com.npdev.generator.emitters;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EDIT-12: proves {@link ModelAuthoringEmitter} emits a page that (a) exists and mentions the
 * client-side capability it replaces a slice of, (b) never calls back into this app's own server --
 * neither to fetch nor, critically, to POST an edited model anywhere -- keeping R10.1's deleted
 * draft write-back door shut, and (c) is byte-for-byte deterministic across two emissions of the
 * same model, same bar {@code ModelSurfaceEmitterTest} holds R10.2's sibling page to.
 */
class ModelAuthoringEmitterTest {
    @TempDir
    Path temp;

    @Test
    void emitsModelAuthoringHtmlWithExpectedCapabilities() throws Exception {
        Path modelPath = writeSimpleModel();
        CompiledModel compiled = compile(modelPath);

        Path generated = temp.resolve("generated");
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(generated, new RegenerationPolicy());
        new ModelAuthoringEmitter(templates, writer).emit(compiled);

        Path page = generated.resolve("src/main/resources/static/model-authoring.html");
        assertTrue(Files.exists(page), "Expected model-authoring.html to be written");
        String html = Files.readString(page);

        assertTrue(html.contains("showDirectoryPicker"), "Expected the File System Access API entry point:\n" + html);
        assertTrue(html.contains("edit12.authoring.test"), "Expected the namespace substitution to work:\n" + html);
        assertTrue(html.contains("Add concept"), "Expected the one shipped scaffolding action:\n" + html);
        assertTrue(html.contains("model.json"), "Expected the imported/exported file name to appear:\n" + html);
    }

    @Test
    void neverCallsBackIntoThisAppsOwnServer() throws Exception {
        Path modelPath = writeSimpleModel();
        CompiledModel compiled = compile(modelPath);

        Path generated = temp.resolve("generated2");
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(generated, new RegenerationPolicy());
        new ModelAuthoringEmitter(templates, writer).emit(compiled);
        String html = Files.readString(generated.resolve("src/main/resources/static/model-authoring.html"));

        int scriptOpen = html.indexOf("<script>");
        assertTrue(scriptOpen >= 0, "Expected an executable <script> tag:\n" + html);
        int scriptClose = html.indexOf("</script>", scriptOpen);
        String scriptBody = html.substring(scriptOpen, scriptClose);

        // No network call of any kind -- import/export goes straight to the user's own chosen local
        // folder via the File System Access API, never through this app's server.
        assertFalse(scriptBody.contains("fetch("), "model-authoring.html's script must not fetch anything:\n" + scriptBody);
        assertFalse(scriptBody.contains("XMLHttpRequest"), "model-authoring.html's script must not use XHR:\n" + scriptBody);

        // The specific regression this test exists to prevent: R10.1 deleted this platform's only
        // server-side draft write-back endpoints on purpose. Re-adding a POST of an edited model to
        // any /api/... path would reopen that exact door.
        assertFalse(html.toLowerCase(java.util.Locale.ROOT).contains("/api/"),
                "model-authoring.html must not name any /api/... endpoint -- it must never write a "
                        + "model back through this app's own server:\n" + html);
    }

    @Test
    void emissionIsByteForByteDeterministicAcrossTwoRuns() throws Exception {
        Path modelPath = writeSimpleModel();
        CompiledModel compiled = compile(modelPath);

        Path outA = temp.resolve("run-a");
        Path outB = temp.resolve("run-b");
        new ModelAuthoringEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(outA, new RegenerationPolicy())).emit(compiled);
        new ModelAuthoringEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(outB, new RegenerationPolicy())).emit(compiled);

        byte[] htmlA = Files.readAllBytes(outA.resolve("src/main/resources/static/model-authoring.html"));
        byte[] htmlB = Files.readAllBytes(outB.resolve("src/main/resources/static/model-authoring.html"));
        assertTrue(java.util.Arrays.equals(htmlA, htmlB),
                "model-authoring.html must be byte-for-byte identical across two emissions of the same "
                        + "compiled model -- no timestamps, no unordered-map iteration (generator determinism "
                        + "is gate-checked byte-for-byte, scripts/hygiene/check-deterministic-generation.ps1)");
    }

    private CompiledModel compile(Path modelPath) throws Exception {
        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(modelPath);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected model to validate, got: " + errors);
        return new ModelCompiler().compile(ast);
    }

    private Path writeSimpleModel() throws Exception {
        Path path = temp.resolve("model.json");
        Files.writeString(path, """
                {
                  "namespace": "edit12.authoring.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true }
                      ]
                    }
                  ]
                }
                """);
        return path;
    }
}
