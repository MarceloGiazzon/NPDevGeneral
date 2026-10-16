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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UI-8: declared locales + per-locale chrome strings in the manifest, X-Tag-locale in the shell. */
public class BusinessUiEmitterLocaleManifestTest {

    private static final String STATIC = "src/main/resources/static/npdev-business-ui/";

    private static Path emit(String modelJson) throws Exception {
        Path modelPath = Files.createTempFile("npdev-locale-", ".json");
        Files.writeString(modelPath, modelJson);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-locale-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    private static final String LOCALIZED = """
            {
              "namespace": "locale.demo", "dslVersion": "1.0.0", "version": "1.0",
              "concepts": [
                { "name": "Order",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "status", "type": "string",
                      "ui": { "label": { "default": "Status", "pt-BR": "Situa\\u00e7\\u00e3o", "es": "Estado" } } }
                  ] }
              ]
            }
            """;

    private static final String PLAIN = """
            {
              "namespace": "locale.plain", "dslVersion": "1.0.0", "version": "1.0",
              "concepts": [
                { "name": "Note", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                                              { "name": "body", "type": "string" } ] }
              ]
            }
            """;

    @Test
    void manifestListsDeclaredLocalesAndChromeStringsPerLanguage() throws Exception {
        String manifest = Files.readString(emit(LOCALIZED).resolve(STATIC + "generated-ui-manifest.json"));
        assertTrue(manifest.contains("\"locales\" : [ \"es\", \"pt-BR\" ]"), manifest);
        assertTrue(manifest.contains("\"stringsByLocale\""), manifest);
        assertTrue(manifest.contains("\"shell.toggleMenu\" : \"Recolher/expandir menu\""), manifest);
        assertTrue(manifest.contains("\"shell.toggleMenu\" : \"Collapse/expand menu\""), manifest);
        // the old single-catalogue key stays for old shells
        assertTrue(manifest.contains("\"strings\""), manifest);
    }

    @Test
    void modelWithoutLocalizedLabelsDeclaresNoLocales() throws Exception {
        String manifest = Files.readString(emit(PLAIN).resolve(STATIC + "generated-ui-manifest.json"));
        assertTrue(manifest.contains("\"locales\" : [ ]"), manifest);
    }

    @Test
    void shellsSendTheLocaleHeaderAndNoLongerHardCodePortugueseChrome() throws Exception {
        Path out = emit(LOCALIZED);
        String appJs = Files.readString(out.resolve(STATIC + "app.js"));
        assertTrue(appJs.contains("X-Tag-locale"));
        assertTrue(appJs.contains("live.label"), "bundle label is copied into the field");
        String shell = Files.readString(out.resolve("src/main/resources/static/shell.js"));
        assertTrue(shell.contains("X-Tag-locale"));
        assertFalse(shell.contains("Recolher/expandir menu"));
        assertFalse(shell.contains("Acesso rapido"));
        assertFalse(shell.contains("Mostrar/ocultar painel lateral"));
        Path locale = out.resolve(STATIC + "views/locale.js");
        assertTrue(Files.exists(locale));
        String index = Files.readString(out.resolve(STATIC + "index.html"));
        assertTrue(index.indexOf("./views/registry.js") < index.indexOf("./views/locale.js"));
        assertTrue(index.indexOf("./views/locale.js") < index.indexOf("./app.js"));
    }

    @Test
    void emittedScriptsParse() throws Exception {
        boolean node;
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            node = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            node = false;
        }
        Assumptions.assumeTrue(node, "node not on PATH");
        Path out = emit(LOCALIZED);
        for (Path js : new Path[] {out.resolve(STATIC + "app.js"), out.resolve("src/main/resources/static/shell.js"),
                out.resolve(STATIC + "views/locale.js")}) {
            Process check = new ProcessBuilder("node", "--check", js.toString()).redirectErrorStream(true).start();
            String output = new String(check.getInputStream().readAllBytes());
            assertTrue(check.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, check.exitValue(), js + ": " + output);
        }
    }
}
