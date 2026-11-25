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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-360 (d): the top-bar logo of a generated app read {@code document.title} once, at shell mount. The
 * host rewrites {@code document.title} to "&lt;page&gt; - &lt;app&gt;" on every render, so a render that ran
 * before the mount left the logo showing the page that happened to be open -- "Drivers" on the Rides and
 * Payments pages of a dispatcher. The host now passes the app's name to {@code NPDevShell.mount} and the shell
 * uses it. Asserted against the emitted assets, like this package's other UI tests: what every generated app
 * ships is what matters. (The behaviour itself was checked in a real browser; see the ledger item.)
 */
public class BusinessUiEmitterShellLogoTest {

    private static Path emit() throws Exception {
        Path modelPath = Files.createTempFile("npdev-shell-logo-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "shelllogo.demo",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Customer", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-shell-logo-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    @Test
    void theHostNamesTheAppAndTheShellPaintsThatNameInTheLogo() throws Exception {
        Path out = emit();
        String shellJs = Files.readString(out.resolve("src/main/resources/static/shell.js"));
        String appJs = CarvedUiModules.appJsWithModules(out);

        assertTrue(appJs.contains("appName: state.manifest.appName"),
                "the host must pass the app's name to NPDevShell.mount");
        assertTrue(shellJs.contains("state.appName = options && options.appName;"),
                "mount() must keep the name it is given");
        assertTrue(shellJs.contains("logo.textContent = state.appName ||"),
                "the logo must prefer the given name over the document title");
        assertTrue(shellJs.contains("var logoText = state.appName || title;"),
                "the refresh after the catalogue loads (applyStaticTranslations) rewrote the logo from document.title "
                        + "and undid the name; it must use the same rule");
    }

    @Test
    void theLogoIsNoLongerReadFromTheDocumentTitleAlone() throws Exception {
        String shellJs = Files.readString(emit().resolve("src/main/resources/static/shell.js"));

        assertFalse(shellJs.contains("logo.textContent = (document.title"),
                "a logo read only from document.title follows whatever page the host last rendered");
        assertTrue(shellJs.contains("(document.title || \"NPDev App\").split(\" - \")[0]"),
                "a hand-written page that mounts no host still falls back to its own title");
    }
}
