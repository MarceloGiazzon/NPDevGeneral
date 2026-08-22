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
import com.npdev.generator.testsupport.WorkspaceRootLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R10.2: proves {@link ModelSurfaceEmitter} emits a schema-driven surface -- one whose sections come
 * from walking whatever the compiled model actually carries, not from a hardcoded list of section
 * names -- rather than merely proving it emits SOME html.
 *
 * <p>The proof has two independent legs:
 * <ol>
 *   <li>{@link #rendersSectionsNobodyWroteCodeFor()}: a real model exercising {@code seeds[]} and
 *   {@code aggregates[].invariants[]} -- the two DSL sections that landed on this branch with no
 *   surface code written for either -- shows up fully in the emitted page's embedded JSON. Nothing
 *   in {@link ModelSurfaceEmitter} or {@code model-surface.mustache} was written with either section
 *   in mind; if the emitted page still shows the data, that is because the emitter never asked the
 *   model for any one section by name.</li>
 *   <li>{@link #emitterAndTemplateNeverNameAModelSectionLiterally()}: reads the actual emitter and
 *   template SOURCE (not their output) and asserts neither file contains the literal section-name
 *   tokens involved in leg 1. This is the mechanical guard against the exact failure mode this item
 *   exists to prevent: a later edit that adds an {@code if (key.equals("seeds"))}-shaped branch would
 *   fail this test even though leg 1 might still incidentally pass.</li>
 * </ol>
 */
class ModelSurfaceEmitterTest {
    @TempDir
    Path temp;

    @Test
    void rendersSectionsNobodyWroteCodeFor() throws Exception {
        Path modelPath = writeModelWithSeedsAndAggregateInvariants();
        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(modelPath);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected model to validate, got: " + errors);
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path generated = temp.resolve("generated");
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(generated, new RegenerationPolicy());
        new ModelSurfaceEmitter(templates, writer).emit(compiled);

        Path surface = generated.resolve("src/main/resources/static/model-surface.html");
        assertTrue(Files.exists(surface), "Expected model-surface.html to be written");
        String html = Files.readString(surface);

        // seeds: a pack/root-declared section with zero purpose-built surface code anywhere.
        assertTrue(html.contains("\"seeds\""), "Expected top-level \"seeds\" key in embedded JSON:\n" + html);
        assertTrue(html.contains("\"Seeded Widget\""), "Expected the seed's own data to appear:\n" + html);

        // aggregates[].invariants: nested one level deeper, also with zero purpose-built surface code.
        assertTrue(html.contains("\"aggregates\""), "Expected top-level \"aggregates\" key:\n" + html);
        assertTrue(html.contains("\"invariants\""), "Expected nested \"invariants\" key:\n" + html);
        assertTrue(html.contains("\"NameRequired\""), "Expected the invariant's own name to appear:\n" + html);
        assertTrue(html.contains("name != null"), "Expected the invariant's own expression to appear:\n" + html);

        // Sanity: the namespace substitution and embedding safety still work.
        assertTrue(html.contains("r102.model.surface.test"));
        assertTrue(html.contains("id=\"npdev-model\""));
    }

    @Test
    void emitterAndTemplateNeverNameAModelSectionLiterally() throws Exception {
        Path root = WorkspaceRootLocator.resolveWorkspaceRoot();
        Path emitterSource = root.resolve(
                "NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/ModelSurfaceEmitter.java");
        Path templateSource = root.resolve(
                "NPDevGenerator/generator/src/main/resources/npdev-templates/model-surface.mustache");
        assertTrue(Files.exists(emitterSource), "Expected " + emitterSource);
        assertTrue(Files.exists(templateSource), "Expected " + templateSource);

        String emitterCode = Files.readString(emitterSource).toLowerCase(Locale.ROOT);
        String templateCode = Files.readString(templateSource).toLowerCase(Locale.ROOT);

        // These are two of the model's own top-level/nested section names -- the exact ones leg 1
        // proves render correctly. If either ever appears as a literal token in the emitter or the
        // template, someone has started branching on "which section is this", which is precisely the
        // hardcoded-section-list failure mode this item exists to replace. (The template's own prose
        // comment already avoids naming any section; this assertion keeps that true mechanically.)
        for (String forbidden : List.of("seeds", "invariants", "aggregates")) {
            assertFalse(emitterCode.contains(forbidden),
                    "ModelSurfaceEmitter.java must not name model section \"" + forbidden + "\" literally");
            assertFalse(templateCode.contains(forbidden),
                    "model-surface.mustache must not name model section \"" + forbidden + "\" literally");
        }
    }

    @Test
    void modelJsonIsEmbeddedNotFetchedAndScriptCloseTagIsEscaped() throws Exception {
        Path modelPath = writeModelWithSeedsAndAggregateInvariants();
        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(modelPath);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected model to validate, got: " + errors);
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path generated = temp.resolve("generated2");
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(generated, new RegenerationPolicy());
        new ModelSurfaceEmitter(templates, writer).emit(compiled);
        String html = Files.readString(generated.resolve("src/main/resources/static/model-surface.html"));

        // No fetch()/XHR call in the executable script -- the page must work from file:// with no
        // server behind it. Scoped to the bare <script> tag's body (not the whole page) because the
        // template's own HTML comment explaining that design legitimately contains the prose
        // "fetch() is blocked by every browser", which a whole-page substring check would trip on.
        int scriptTagOpen = html.indexOf("<script>");
        assertTrue(scriptTagOpen >= 0, "Expected an executable <script> tag:\n" + html);
        int scriptBodyEnd = html.indexOf("</script>", scriptTagOpen);
        String scriptBody = html.substring(scriptTagOpen, scriptBodyEnd);
        assertFalse(scriptBody.contains("fetch("), "model-surface.html's script must not fetch its data:\n" + scriptBody);
        assertFalse(scriptBody.contains("XMLHttpRequest"), "model-surface.html's script must not use XHR:\n" + scriptBody);
        // The embedded JSON script tag must never contain a literal "</script" that would close it early.
        int scriptOpen = html.indexOf("id=\"npdev-model\"");
        assertTrue(scriptOpen >= 0);
        int payloadStart = html.indexOf('>', scriptOpen) + 1;
        int payloadEnd = html.indexOf("</script>", payloadStart);
        String payload = html.substring(payloadStart, payloadEnd);
        assertFalse(payload.contains("</"), "Embedded JSON payload must have \"</\" escaped:\n" + payload);
        assertEquals(-1, payload.indexOf("</script"));
    }

    @Test
    void emissionIsByteForByteDeterministicAcrossTwoRuns() throws Exception {
        Path modelPath = writeModelWithSeedsAndAggregateInvariants();
        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(modelPath);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected model to validate, got: " + errors);
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path outA = temp.resolve("run-a");
        Path outB = temp.resolve("run-b");
        new ModelSurfaceEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(outA, new RegenerationPolicy())).emit(compiled);
        new ModelSurfaceEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(outB, new RegenerationPolicy())).emit(compiled);

        byte[] htmlA = Files.readAllBytes(outA.resolve("src/main/resources/static/model-surface.html"));
        byte[] htmlB = Files.readAllBytes(outB.resolve("src/main/resources/static/model-surface.html"));
        assertTrue(java.util.Arrays.equals(htmlA, htmlB),
                "model-surface.html must be byte-for-byte identical across two emissions of the same "
                        + "compiled model -- no timestamps, no unordered-map iteration (generator determinism "
                        + "is gate-checked byte-for-byte, scripts/hygiene/check-deterministic-generation.ps1)");
    }

    private Path writeModelWithSeedsAndAggregateInvariants() throws Exception {
        return write("model.json", """
                {
                  "namespace": "r102.model.surface.test",
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
                  ],
                  "aggregates": [
                    {
                      "name": "widgetAggregate",
                      "root": "Widget",
                      "invariants": [
                        {
                          "name": "NameRequired",
                          "expression": "name != null && name != ''"
                        }
                      ]
                    }
                  ],
                  "seeds": [
                    {
                      "concept": "Widget",
                      "data": { "name": "Seeded Widget" }
                    }
                  ]
                }
                """);
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
