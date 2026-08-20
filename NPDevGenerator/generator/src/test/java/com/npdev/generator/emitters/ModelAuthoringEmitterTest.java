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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        assertTrue(html.contains("model.json"), "Expected the imported/exported file name to appear:\n" + html);

        // All seven scaffolding actions the frozen AuthoringApp.js bundle offered. Asserted by the
        // user-visible label rather than the element id, because the label is what makes the
        // capability reachable -- an id can exist on a control nothing renders.
        for (String action : List.of("Add concept", "Add field", "Add flow", "Add panel",
                "Add invariant", "Add state", "Add transition")) {
            assertTrue(html.contains(action), "Expected scaffolding action \"" + action + "\":\n" + html);
        }

        // Both starter templates it offered, by their own names.
        assertTrue(html.contains("Business Record Starter"), "Expected the Business Record starter:\n" + html);
        assertTrue(html.contains("Approval Workflow Starter"), "Expected the Approval Workflow starter:\n" + html);
    }

    /**
     * The scaffolds this page produces have to be shapes the DSL actually accepts, or the page hands
     * a user a model.json the generator will reject -- which is worse than not scaffolding at all.
     * These assert the specific structural decisions EDIT-12 records: a lifecycle carries the status
     * field it names, a flow gets a real step rather than an empty shell, and a concept gets its id.
     */
    @Test
    void scaffoldedShapesMatchWhatTheDslRequires() throws Exception {
        Path generated = temp.resolve("generated3");
        new ModelAuthoringEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(generated, new RegenerationPolicy())).emit(compile(writeSimpleModel()));
        String html = Files.readString(generated.resolve("src/main/resources/static/model-authoring.html"));

        // concept.fields is required, and an id field is the platform convention.
        assertTrue(html.contains("{ name: 'id', type: 'uuid', id: true, required: true }"),
                "Every scaffolded concept must carry its id field:\n" + html);

        // LifecycleValidation: statusField must NAME a field the concept declares, that field must be
        // of type `enum`, and its enumValues must cover every state (a transition may only name values
        // among them). All three are why the states are the single source of truth and the field is
        // rebuilt from them, rather than the two being edited separately.
        assertTrue(html.contains("function syncStatusEnum"),
                "Expected the status enum to be rebuilt from the states by one helper:\n" + html);
        assertTrue(html.contains("statusField.type = 'enum';"),
                "A scaffolded lifecycle's status field must be typed enum, not string:\n" + html);
        assertTrue(html.contains("statusField.enumValues = lifecycle.states.map"),
                "The status field's enumValues must be derived from the declared states:\n" + html);

        // ...and a lifecycle also needs exactly one initial state and at least one transition, which
        // the page reports while still incomplete rather than leaving to a later failed build.
        assertTrue(html.contains("function lifecycleAdvice"),
                "Expected the page to report an incomplete lifecycle:\n" + html);

        // flowStep.type is required; a flow declaring an input mode but never persisting is a
        // warned-about defect, so the scaffold emits the persistence step its mode implies.
        assertTrue(html.contains("type: mode === 'create' ? 'createConcept' : 'updateConcept'"),
                "A scaffolded flow must persist what its declared mode implies:\n" + html);
        assertTrue(html.contains("type: 'return'"),
                "A scaffolded flow must return a value:\n" + html);

        // panel.route is required alongside panel.name.
        assertTrue(html.contains("function routeFromName"),
                "A scaffolded panel must always get a route, derived when not supplied:\n" + html);

        // ux-metadata warns on any concept or field with no user-visible label; a scaffolding tool
        // that emits a warning per item it adds trains the user to ignore the validator.
        assertTrue(html.contains("function labelled"),
                "Every scaffolded concept and field must get a ui.label:\n" + html);
    }

    /**
     * Every button the page renders must actually be wired to a handler. This guard exists because
     * slice 2 shipped an Export button with no {@code addEventListener} for one revision: the page
     * looked complete, every unit assertion still passed (the markup and the handler function both
     * existed), and only driving it in a real browser showed the click doing nothing. A control
     * that renders but is inert is invisible to any test that only greps for its label.
     */
    @Test
    void everyRenderedButtonIsWiredToAHandler() throws Exception {
        Path generated = temp.resolve("generated4");
        new ModelAuthoringEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(generated, new RegenerationPolicy())).emit(compile(writeSimpleModel()));
        String html = Files.readString(generated.resolve("src/main/resources/static/model-authoring.html"));

        int scriptOpen = html.indexOf("<script>");
        String markup = html.substring(0, scriptOpen);
        String script = html.substring(scriptOpen);

        Matcher buttons = Pattern.compile("<button id=\"([^\"]+)\"").matcher(markup);
        List<String> found = new ArrayList<>();
        while (buttons.find()) {
            found.add(buttons.group(1));
        }
        assertFalse(found.isEmpty(), "Expected the page to render identified buttons:\n" + markup);

        for (String id : found) {
            // Wired either through a cached variable (var fooBtn = el('id'); fooBtn.addEventListener)
            // or inline (el('id').addEventListener). Both forms have to name the id somewhere in the
            // script, and the script has to attach a listener to whatever that lookup returned.
            assertTrue(script.contains("'" + id + "'"),
                    "Button \"" + id + "\" is rendered but its id is never looked up in the script -- "
                            + "a control that renders and does nothing:\n" + script);
        }

        // ...and the count of click listeners has to keep up with the count of buttons, so adding a
        // button without wiring it fails here rather than silently shipping inert.
        int clickListeners = script.split("addEventListener\\('click'", -1).length - 1;
        assertTrue(clickListeners >= found.size(),
                "Rendered " + found.size() + " identified button(s) but attached only " + clickListeners
                        + " click listener(s) -- at least one control is inert:\n" + script);
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
