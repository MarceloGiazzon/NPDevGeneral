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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unsaved-changes guard + double-submit guard on the generic create/edit form, and the standalone
 * workbench's tab-close guard. Asserted against the emitted assets, like the neighbouring
 * BusinessUiEmitter*Test classes; the live behaviour is driven by
 * unsaved-guard-verify-routine.json in run-shell-scenarios.ps1.
 */
public class BusinessUiEmitterUnsavedGuardTest {

    private static Path emit() throws Exception {
        Path modelPath = Files.createTempFile("npdev-unsaved-guard-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "unsavedguard.demo",
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
        Path out = Files.createTempDirectory("npdev-unsaved-guard-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    @Test
    void formsAreGuardedAgainstLosingUnsavedChanges() throws Exception {
        Path out = emit();
        String appJs = CarvedUiModules.appJsWithModules(out);
        assertTrue(appJs.contains("window.addEventListener(\"beforeunload\""),
                "a dirty form must ask before the tab is closed");
        assertTrue(appJs.contains("function confirmDiscard()"), "confirmDiscard() dialog helper");
        assertTrue(appJs.contains("function confirmIfDirty(form)"), "per-form dirty check helper");
        assertTrue(appJs.contains("function anyFormDirty()"), "registry-wide dirty check");
        assertTrue(appJs.contains("function requestCloseModal(root)"), "guarded modal close helper");
        assertTrue(appJs.contains("_npdevCloseGuard"), "optional close guard on the modal root");
        assertTrue(appJs.contains("You have unsaved changes. Discard them?"), "confirmation text");
        assertTrue(appJs.contains("Keep editing") && appJs.contains("\"Discard\""), "dialog buttons");
        assertTrue(appJs.contains("el.confirmRoot"), "confirm dialog uses its own third modal root");
        assertTrue(appJs.contains("committedHash"), "hash navigation is reverted while a form is dirty");
        assertTrue(appJs.contains("hashRevertPending"), "the revert's own hashchange is skipped");
    }

    @Test
    void indexShipsTheConfirmRoot() throws Exception {
        String index = Files.readString(emit().resolve("src/main/resources/static/npdev-business-ui/index.html"));
        assertTrue(index.contains("id=\"confirmRoot\""), "index.html must carry the confirmRoot mount");
    }

    @Test
    void submitIgnoresReentrantCallsAndShowsBusyState() throws Exception {
        String appJs = CarvedUiModules.appJsWithModules(emit());
        assertTrue(appJs.contains("form.dataset.inFlight"), "re-entrant submits are ignored via dataset.inFlight");
        assertTrue(appJs.contains("aria-busy"), "the submit button is marked busy while saving");
        assertTrue(appJs.contains("Saving"), "the submit button shows a Saving label");
        assertTrue(appJs.contains("} finally {"), "the button is restored in a finally block");
    }

    @Test
    void workbenchWarnsBeforeTabCloseWithOnlyUserEdits() {
        String js = new TemplateEngine("npdev-templates/").render("workbench-page.html.mustache", Map.of());
        assertTrue(js.contains("addEventListener(\"beforeunload\""), "workbench tab-close guard");
        assertTrue(js.contains("store.hasUserChanges()"), "guard is driven by user edits, not load-time patches");
        assertTrue(js.contains("store.markLoaded();"), "the onLoad patch must not count as an unsaved change");
    }
}
