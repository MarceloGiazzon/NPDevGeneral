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

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the generator emits a served Workbench page per aggregate-bound AutoPanel (ADR-0005 / P4 client). */
public class BusinessUiEmitterWorkbenchTest {

    private static CompiledModel compile(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-wb-", ".json");
        Files.writeString(modelPath, json);
        return new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
    }

    private static Path emit(CompiledModel model) throws Exception {
        Path out = Files.createTempDirectory("npdev-wb-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    @Test
    void aggregateAutoPanelEmitsAServedWorkbenchPage() throws Exception {
        CompiledModel model = compile("""
            {
              "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
              "concepts": [
                { "name": "Expedicao", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "cliente", "type": "string" } ] },
                { "name": "ExpedicaoItem", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "expedicaoId", "type": "uuid" } ] }
              ],
              "aggregates": [
                { "name": "Expedicao", "root": "Expedicao",
                  "collections": [ { "name": "itens", "concept": "ExpedicaoItem", "childField": "expedicaoId", "ownership": "owned" } ] }
              ],
              "autoPanels": [ { "aggregate": "Expedicao" } ]
            }
            """);

        Path out = emit(model);
        Path page = out.resolve("src/main/resources/static/npdev-workbench/ExpedicaoWorkbench.html");
        assertTrue(Files.exists(page), "expected a served workbench page for the aggregate AutoPanel");
        String html = Files.readString(page);
        assertTrue(html.contains("var PANEL = \"ExpedicaoWorkbench\""), "page targets its panel");
        assertTrue(html.contains("var SELECTION_PANEL = \"ExpedicaoSelection\""), "page knows its root Selection list panel");
        assertTrue(html.contains("var FILTERS ="), "page has the client filter list");
        assertTrue(html.contains("stateInfo"), "page has lifecycle gating (status + editability)");
        assertTrue(html.contains("lc.transitions"), "page renders lifecycle transition actions (Confirmar …)");
        assertTrue(html.contains("descriptor.actions"), "page renders procedure-over-aggregate action buttons");
        assertTrue(html.contains("/invoke/"), "page invokes procedures over the aggregate draft");
        assertTrue(html.contains("scheduleRecompute"), "page has debounced reactive recompute (P3)");
        assertTrue(html.contains("openBandPicker"), "page has the C6 band row picker modal");
        assertTrue(html.contains("revertRegion"), "page has per-region edit-buffer revert (C8)");
        assertTrue(html.contains("/api/runtime/metadata/ui/panels/"), "page fetches loadWorkbench");
        assertTrue(html.contains("/api/runtime/aggregate/"), "page commits via the aggregate POST");
        assertFalse(html.contains("{{"), "no unrendered mustache placeholders");

        // Move 6 Move D: every generated workbench page ships the mounted-component machinery
        // unconditionally (an app using none of it never even calls these), so a model that later
        // adds transaction.regions gets it without a generator change.
        assertTrue(html.contains("window.npdev.regions"), "page carries the global region-mount registry");
        assertTrue(html.contains("function mountRegion("), "page can mount a region's declared component");
        assertTrue(html.contains("function regionApi("), "page builds the narrowed mount api");
        assertTrue(html.contains("function loadRegionScripts("), "page auto-injects app-owned web/regions/<name>.js");

        // The nav manifest links the workbench panel straight to its served page.
        String manifest = Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
        assertTrue(manifest.contains("\"workbenchUrl\":\"/npdev-workbench/ExpedicaoWorkbench.html\"")
                        || manifest.contains("\"workbenchUrl\" : \"/npdev-workbench/ExpedicaoWorkbench.html\""),
                "manifest should link the workbench panel to its page; got: " + manifest);
    }

    /** Move 6 Move A: the workbench page's STRINGS catalogue merges the platform's English defaults
     * with any app-declared {@code settings.strings} override -- proving the fault line named in
     * docs/MOVE6_TYPED_SURFACE_PLAN.md §2 (a generated app rendering "Save" beside a hardcoded
     * "Adicionar") is closed: nothing is hardcoded in the template anymore. */
    @Test
    void settingsStringsOverridesRenderIntoWorkbenchPage() throws Exception {
        CompiledModel model = compile("""
            {
              "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
              "concepts": [
                { "name": "Expedicao", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "cliente", "type": "string" } ] },
                { "name": "ExpedicaoItem", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "expedicaoId", "type": "uuid" } ] }
              ],
              "aggregates": [
                { "name": "Expedicao", "root": "Expedicao",
                  "collections": [ { "name": "itens", "concept": "ExpedicaoItem", "childField": "expedicaoId", "ownership": "owned" } ] }
              ],
              "autoPanels": [ { "aggregate": "Expedicao" } ],
              "settings": { "locale": "pt-BR", "strings": { "action.save": "Salvar" } }
            }
            """);

        Path out = emit(model);
        String html = Files.readString(out.resolve("src/main/resources/static/npdev-workbench/ExpedicaoWorkbench.html"));
        assertTrue(html.contains("var STRINGS ="), "page carries the resolved string catalogue");
        assertTrue(html.contains("Salvar"), "app override for action.save renders into the page");
        assertTrue(html.contains("Select"), "an id the app didn't override keeps the platform English default");
        assertFalse(html.contains("Selecionar"),
                "no hardcoded Portuguese fallback should be baked into the template anymore");
    }

    @Test
    void appWithoutAggregateAutoPanelEmitsNoWorkbenchPage() throws Exception {
        CompiledModel model = compile("""
            {
              "dslVersion": "1.0.0", "namespace": "plain", "version": "1.0",
              "concepts": [ { "name": "Thing", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
            }
            """);
        Path out = emit(model);
        assertFalse(Files.exists(out.resolve("src/main/resources/static/npdev-workbench")),
                "no aggregate AutoPanel -> no workbench pages");
    }
}
