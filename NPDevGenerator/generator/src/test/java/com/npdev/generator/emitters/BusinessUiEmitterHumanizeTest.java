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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-194 — the generated business UI's {@code humanize()} helper split on EVERY capital
 * ({@code /([A-Z])/g, " $1"}), which is correct for camelCase field names and wrong for anything
 * already upper-case: an ALL-CAPS enum value ("HIGH") rendered as "H I G H", and an acronym inside an
 * identifier ("ID"/"URL"/"SKU") was exploded letter by letter. ALL-CAPS is the ordinary convention for
 * enum values in this platform's corpus, so the wrong case was not exotic.
 *
 * <p>The fix is the same split the sibling templates ({@code model-surface.mustache},
 * {@code model-authoring.mustache}) already use: only split a capital that follows a lower-case letter
 * or digit ({@code /([a-z0-9])([A-Z])/g, "$1 $2"}), leaving runs of capitals intact ("HIGH" stays
 * "HIGH", "URLValue" stays "URLValue").</p>
 *
 * <p>Asserted against the emitted asset, not the template — this bundle reproduces into every generated
 * app, so what matters is what ships.</p>
 */
public class BusinessUiEmitterHumanizeTest {

    private static String emitAppJs() throws Exception {
        Path modelPath = Files.createTempFile("npdev-humanize-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "humanize.demo",
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
        Path out = Files.createTempDirectory("npdev-humanize-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return readAppJs(out);
    }

    private static String readAppJs(Path out) throws IOException {
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
    void humanizeSplitsOnlyAtCamelCaseBoundariesNotEveryCapital() throws Exception {
        String appJs = emitAppJs();

        // The corrected split must be present in the emitted bundle.
        assertTrue(appJs.contains(".replace(/([a-z0-9])([A-Z])/g, \"$1 $2\")"),
                "humanize must split only at lower/digit-before-capital boundaries");

        // The buggy every-capital split must be gone — its presence is what exploded ALL-CAPS enum
        // values ("HIGH" -> "H I G H") and acronym tokens.
        assertEquals(-1, appJs.indexOf(".replace(/([A-Z])/g, \" $1\")"),
                "humanize must not split on every capital");
    }
}
