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

import static org.junit.jupiter.api.Assertions.assertTrue;

public class EscapeHatchGuidePageTest {
    private static Path writeModel(String json) throws IOException {
        Path modelPath = Files.createTempFile("npdev-escape-hatch-", ".json");
        Files.writeString(modelPath, json);
        return modelPath;
    }

    @Test
    void frameModeMinimalConceptResolvesToBuiltinMinimalGuidePageInManifest() throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(writeModel("""
                {
                  "namespace": "escape.hatch.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Book", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                    { "name": "Author", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """)));
        SettingResolver resolver = new SettingResolver(SettingStore.builder()
                .layer(SettingScope.CONCEPT, "concept:Book", java.util.Map.of("ui.frame.mode", "minimal"), "test")
                .build());
        Path out = Files.createTempDirectory("npdev-escape-hatch-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", resolver);
        String manifest = Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
        assertTrue(manifest.contains("\"frameMode\" : \"minimal\""), manifest);
        // Book is minimal -> guidePage resolves to the built-in "Minimal" (top bar only, no
        // sidebar) -- this is what makes the escape-hatch link (rendered client-side whenever
        // concept.frameMode === "minimal") actually necessary: with the sidebar hidden, it's the
        // only way back without a full reload.
        assertTrue(manifest.contains("\"guidePage\" : \"Minimal\""), manifest);
        // Author has no override -> falls through to the app's default GuidePage, confirming the
        // escape hatch's own target-selection logic (first non-minimal/non-none concept) has
        // somewhere real to land.
        assertTrue(manifest.contains("\"guidePage\" : \"Default\""), manifest);
    }
}
