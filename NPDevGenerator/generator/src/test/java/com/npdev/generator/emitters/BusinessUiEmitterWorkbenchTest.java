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
        assertTrue(html.contains("/api/runtime/metadata/ui/panels/"), "page fetches loadWorkbench");
        assertTrue(html.contains("/api/runtime/aggregate/"), "page commits via the aggregate POST");
        assertFalse(html.contains("{{"), "no unrendered mustache placeholders");
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
