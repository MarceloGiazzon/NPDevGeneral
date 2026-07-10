package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingScope;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GuidePage (declared frame/shell definitions) always resolves to at least the three platform
 * built-ins (Default/Minimal/None) even when an app declares none, and an app-declared GuidePage
 * overrides the built-in of the same name. A concept's {@code ui.frame.mode} setting still maps
 * onto a built-in GuidePage name for back-compat, while an explicit {@code ui.guidePage} override
 * naming an unknown GuidePage fails generation instead of silently falling back.
 */
public class BusinessUiEmitterGuidePageManifestTest {

    private static Path writeModel(String json) throws IOException {
        Path modelPath = Files.createTempFile("npdev-guidepage-manifest-", ".json");
        Files.writeString(modelPath, json);
        return modelPath;
    }

    private static CompiledModel compile(String json) throws Exception {
        return new ModelCompiler().compile(new JsonModelParser().parse(writeModel(json)));
    }

    private static String emitAndReadManifest(CompiledModel model, SettingResolver settingResolver) throws Exception {
        Path out = Files.createTempDirectory("npdev-guidepage-manifest-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", settingResolver);
        return Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
    }

    @Test
    void builtinGuidePagesAlwaysAppearWhenModelDeclaresNone() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "guidepage.manifest.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """);

        String manifest = emitAndReadManifest(model, new SettingResolver(SettingStore.empty()));
        assertTrue(manifest.contains("\"name\" : \"Default\""), manifest);
        assertTrue(manifest.contains("\"name\" : \"Minimal\""), manifest);
        assertTrue(manifest.contains("\"name\" : \"None\""), manifest);
        assertTrue(manifest.contains("\"defaultGuidePage\" : \"Default\""), manifest);
        assertTrue(manifest.contains("\"guidePage\" : \"Default\""), manifest);
    }

    @Test
    void declaredGuidePageOverridesBuiltinDefaultAndAppearsOnConcept() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "guidepage.manifest.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ],
                  "guidePages": [
                    {
                      "name": "Default",
                      "default": true,
                      "theme": { "mode": "light", "accent": "#0b5fff" },
                      "regions": { "top": true, "left": { "enabled": true }, "right": { "enabled": true, "width": 280 } },
                      "gadgets": [ { "name": "recent", "type": "recent-items", "title": "Recentes" } ]
                    }
                  ]
                }
                """);

        String manifest = emitAndReadManifest(model, new SettingResolver(SettingStore.empty()));
        assertTrue(manifest.contains("\"accent\" : \"#0b5fff\""), manifest);
        assertTrue(manifest.contains("\"type\" : \"recent-items\""), manifest);
        assertTrue(manifest.contains("\"defaultGuidePage\" : \"Default\""), manifest);
    }

    @Test
    void frameModeMinimalMapsToBuiltinMinimalGuidePage() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "guidepage.manifest.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """);

        SettingResolver resolver = new SettingResolver(SettingStore.builder()
                .layer(SettingScope.CONCEPT, "concept:Order", java.util.Map.of("ui.frame.mode", "minimal"), "test")
                .build());
        String manifest = emitAndReadManifest(model, resolver);
        assertTrue(manifest.contains("\"frameMode\" : \"minimal\""), manifest);
        assertTrue(manifest.contains("\"guidePage\" : \"Minimal\""), manifest);
    }

    @Test
    void unknownExplicitGuidePageOverrideFailsGeneration() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "guidepage.manifest.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """);

        SettingResolver resolver = new SettingResolver(SettingStore.builder()
                .layer(SettingScope.CONCEPT, "concept:Order", java.util.Map.of("ui.guidePage", "NoSuchGuidePage"), "test")
                .build());
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> {
                    try {
                        emitAndReadManifest(model, resolver);
                    } catch (Exception wrapped) {
                        throw (IllegalStateException) wrapped;
                    }
                });
        assertTrue(exception.getMessage().contains("guidePage not found"), exception.getMessage());
    }
}
