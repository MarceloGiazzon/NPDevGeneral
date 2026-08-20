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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-192(b) — the empty-state "Load sample data" seed CTA had no deterministic selector, so a browser
 * routine addressed it by its rendered label ("Demo users"). When REG-189 made a single-seed manifest
 * render correctly, that label became "Load sample data" and the routine silently stopped matching.
 *
 * <p>The durable answer (the same one MON-15's coverage matching and R3.3's generated routines rely on)
 * is a stable hook: the seed button now carries {@code data-seed-id=<seed.id>}, so a routine addresses
 * the CTA by the seed's identity rather than its display text.</p>
 *
 * <p>Asserted against the emitted asset, not the template — this bundle reproduces into every generated
 * app, so what matters is what ships.</p>
 */
public class BusinessUiEmitterSeedCtaHookTest {

    private static String emitAppJs() throws Exception {
        Path modelPath = Files.createTempFile("npdev-seed-cta-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "seedcta.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Customer",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "displayName", "type": "string", "required": true }
                      ]
                    }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-seed-cta-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        Path assets = out.resolve("src/main/resources/static/npdev-business-ui");
        try (var files = Files.walk(assets)) {
            Path appJs = files.filter(path -> path.getFileName().toString().endsWith(".js"))
                    .filter(path -> path.getFileName().toString().contains("app"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no app js emitted under " + assets));
            return Files.readString(appJs);
        }
    }

    @Test
    void seedCtaCarriesADeterministicDataSeedIdHook() throws Exception {
        String appJs = emitAppJs();

        // A routine must be able to address the seed CTA by the seed's identity, not its rendered label
        // ("Demo users" vs "Load sample data" changed under REG-189).
        assertTrue(appJs.contains("seedButton.dataset.seedId = seed.id"),
                "the seed CTA must expose data-seed-id so routines are not coupled to its label");
    }
}
