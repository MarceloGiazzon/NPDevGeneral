package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI-0 / UI-4: the plain (non-Mustache) view assets under
 * {@code static-assets/business-ui/views/} are emitted verbatim next to app.js, loaded before it,
 * and parse as JavaScript. History (UI-4) is a registered detail section.
 */
public class BusinessUiEmitterViewAssetsTest {

    private static final String STATIC = "src/main/resources/static/npdev-business-ui/";

    private static Path emit() throws Exception {
        Path modelPath = Files.createTempFile("npdev-view-assets-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "viewassets.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Customer",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "displayName", "type": "string", "required": true },
                        { "name": "status", "type": "enum", "enumValues": ["Active", "Inactive"] }
                      ]
                    }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-view-assets-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    private static boolean nodeOnPath() {
        try {
            Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static int run(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "node timed out");
        if (process.exitValue() != 0) {
            System.err.println(output);
        }
        return process.exitValue();
    }

    @Test
    void registryAssetIsEmittedVerbatimAndLoadedBeforeAppJs() throws Exception {
        Path out = emit();
        Path emitted = out.resolve(STATIC + "views/registry.js");
        assertTrue(Files.exists(emitted), "views/registry.js must be emitted");
        assertEquals(new TemplateEngine("npdev-templates/").raw("static-assets/business-ui/views/registry.js"),
                Files.readString(emitted));
        assertTrue(Files.exists(out.resolve(STATIC + "views/views.css")), "views/views.css must be emitted");

        String index = Files.readString(out.resolve(STATIC + "index.html"));
        assertTrue(index.contains("./views/registry.js"));
        assertTrue(index.indexOf("./views/registry.js") < index.indexOf("./app.js"),
                "registry.js must load before app.js");
        assertTrue(index.indexOf("/shell.js") < index.indexOf("./views/registry.js"));
        assertTrue(index.contains("./views/views.css"));
    }

    @Test
    void manifestListsTheTableViewForEveryConcept() throws Exception {
        String manifest = Files.readString(emit().resolve(STATIC + "generated-ui-manifest.json"));
        assertTrue(manifest.contains("\"views\" : [ \"table\" ]"), manifest);
    }

    @Test
    void appJsDispatchesToRegisteredViewsAndDetailSections() throws Exception {
        String appJs = Files.readString(emit().resolve(STATIC + "app.js"));
        assertTrue(appJs.contains("function viewContext(concept, panel)"));
        assertTrue(appJs.contains("NPDevViews.detailSections()"));
        assertTrue(appJs.contains("buildViewSwitcher(concept)"));
    }

    @Test
    void emittedScriptsParseAsJavaScript() throws Exception {
        Assumptions.assumeTrue(nodeOnPath(), "node is not on PATH");
        Path out = emit();
        Path views = out.resolve(STATIC + "views");
        try (var files = Files.list(views)) {
            for (Path js : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".js"))::iterator) {
                assertEquals(0, run(out, "node", "--check", js.toString()), "node --check failed for " + js);
            }
        }
        assertEquals(0, run(out, "node", "--check", out.resolve(STATIC + "app.js").toString()),
                "emitted app.js must parse");
    }

    @Test
    void registryRegistersViewsAndDetailSections() throws Exception {
        Assumptions.assumeTrue(nodeOnPath(), "node is not on PATH");
        Path out = emit();
        Path script = out.resolve("registry-check.js");
        Files.writeString(script, """
                const vm = require("vm"); const fs = require("fs"); const assert = require("assert");
                globalThis.window = globalThis;
                vm.runInThisContext(fs.readFileSync(process.argv[2], "utf8"));
                assert.deepStrictEqual(NPDevViews.names(), []);
                NPDevViews.register("board", { render: function () { return null; } });
                assert.deepStrictEqual(NPDevViews.names(), ["board"]);
                assert.ok(NPDevViews.get("board"));
                assert.throws(function () { NPDevViews.register("bad", {}); });
                NPDevViews.registerDetailSection("a", { render: function () { return null; } });
                NPDevViews.registerDetailSection("a", { render: function () { return null; } });
                assert.strictEqual(NPDevViews.detailSections().length, 1);
                """);
        assertEquals(0, run(out, "node", script.toString(), out.resolve(STATIC + "views/registry.js").toString()));
    }
}
