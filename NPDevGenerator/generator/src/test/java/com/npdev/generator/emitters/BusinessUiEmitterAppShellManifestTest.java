package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path A W1.1 (NPDEV_ROADMAP_2026-09-12.md Wave 1): appShell was compiled/resolved/threaded
 * through the model but never reached the generator at all -- {@code generated-ui-manifest.json}
 * carried no trace of it, so business-ui-app.mustache's {@code deriveNativeGroups}/bootstrap had
 * nothing to consume. This proves BusinessUiEmitter actually emits it, and that a model declaring
 * none still emits a manifest with no {@code appShell} key (the same nullable-not-defaulted
 * contract {@link com.npdev.dsl.v1.compiled.CompiledModel#getAppShell()} itself documents).
 */
public class BusinessUiEmitterAppShellManifestTest {

    private static Path writeModel(String json) throws IOException {
        Path modelPath = Files.createTempFile("npdev-appshell-manifest-", ".json");
        Files.writeString(modelPath, json);
        return modelPath;
    }

    private static CompiledModel compile(String json) throws Exception {
        return new ModelCompiler().compile(new JsonModelParser().parse(writeModel(json)));
    }

    private static String emitAndReadManifest(CompiledModel model) throws Exception {
        Path out = Files.createTempDirectory("npdev-appshell-manifest-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
    }

    @Test
    void declaredAppShellIsEmittedIntoTheManifest() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "appshell.manifest.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "WidgetOrder", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                    { "name": "WidgetCatalogEntry", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ],
                  "appShell": {
                    "defaultRoute": "WidgetOrder",
                    "navigation": [
                      { "label": "Widget Orders", "target": "WidgetOrder", "group": "Operations" },
                      { "label": "Widget Catalog", "target": "WidgetCatalogEntry", "group": "Operations" },
                      { "label": "Reference Data" }
                    ]
                  }
                }
                """);

        String manifest = emitAndReadManifest(model);
        assertTrue(manifest.contains("\"defaultRoute\" : \"WidgetOrder\""), manifest);
        assertTrue(manifest.contains("\"label\" : \"Widget Orders\""), manifest);
        assertTrue(manifest.contains("\"target\" : \"WidgetCatalogEntry\""), manifest);
        assertTrue(manifest.contains("\"group\" : \"Operations\""), manifest);
        assertTrue(manifest.contains("\"label\" : \"Reference Data\""), manifest);
    }

    @Test
    void aModelWithNoAppShellEmitsNoAppShellKey() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "appshell.manifest.none.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """);

        String manifest = emitAndReadManifest(model);
        assertFalse(manifest.contains("\"defaultRoute\""), manifest);
    }
}
