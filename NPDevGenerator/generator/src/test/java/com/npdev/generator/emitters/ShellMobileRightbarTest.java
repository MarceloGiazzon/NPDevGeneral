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

/** UI-7: below the mobile breakpoint the right gadget rail is a drawer, not hidden. */
public class ShellMobileRightbarTest {

    private static Path emitShell() throws Exception {
        Path modelPath = Files.createTempFile("npdev-mobile-rail-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "mobile.rail.demo", "dslVersion": "1.0.0", "version": "1.0",
                  "concepts": [
                    { "name": "Widget",
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                                  { "name": "name", "type": "string" } ] }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-mobile-rail-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out.resolve("src/main/resources/static/shell.js");
    }

    @Test
    void rightRailBecomesADrawerOnPhonesAndIsNoLongerHiddenOutright() throws Exception {
        String shell = Files.readString(emitShell());
        assertTrue(shell.contains("npdev-shell-rightbar-mobile-open"));
        assertFalse(shell.contains(".npdev-shell-rightbar{display:none;}"), "rail must not be hidden outright");
        assertFalse(shell.contains("\".npdev-shell-topbar button.npdev-shell-rightbar-toggle{display:none;}"),
                "toggle must stay visible on phones");
        // only a page with no right region loses the toggle
        assertTrue(shell.contains("body.npdev-shell-no-right .npdev-shell-topbar button.npdev-shell-rightbar-toggle{display:none;}"));
        assertTrue(shell.contains("closeMobileDrawers"));
    }

    @Test
    void emittedShellStillParsesAsJavaScript() throws Exception {
        Path shell = emitShell();
        boolean node;
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            node = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            node = false;
        }
        Assumptions.assumeTrue(node, "node not on PATH");
        Process check = new ProcessBuilder("node", "--check", shell.toString()).redirectErrorStream(true).start();
        String output = new String(check.getInputStream().readAllBytes());
        assertTrue(check.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, check.exitValue(), output);
    }
}
