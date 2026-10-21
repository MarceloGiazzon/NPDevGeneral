package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.templates.TemplateEngine;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * REP-1: the manifest tells the grid toolbar which documents are multi-record reports ({@code kind: list},
 * which also have a CSV form) by stamping {@code kind} on that document's node only; a per-record
 * document gets no {@code kind}, so the toolbar keeps offering only the PDF.
 */
public class BusinessUiEmitterListReportManifestTest {

    private static JsonNode documentNodes() throws Exception {
        Path modelPath = Files.createTempFile("npdev-listreport-manifest-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "listreport.manifest.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "status", "type": "string" },
                      { "name": "total", "type": "decimal" }
                    ] }
                  ],
                  "documents": [
                    { "name": "OrderSummary", "concept": "Order", "title": "Order summary" },
                    { "name": "OrdersByStatus", "concept": "Order", "kind": "list", "title": "Orders by status",
                      "columns": [ { "field": "status", "label": "Status" },
                                   { "field": "total", "label": "Total", "total": "sum" } ],
                      "groupBy": ["status"], "subtotals": true, "grandTotal": true }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-listreport-manifest-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        JsonNode manifest = new ObjectMapper().readTree(Files.readString(
                out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json")));
        for (JsonNode concept : manifest.get("concepts")) {
            if (concept.get("conceptName").asText().equals("Order")) {
                return concept.get("documents");
            }
        }
        return null;
    }

    @Test
    void onlyAListReportDocumentCarriesKindList() throws Exception {
        JsonNode documents = documentNodes();
        assertNotNull(documents, "manifest has no documents node");
        JsonNode perRecord = null;
        JsonNode list = null;
        for (JsonNode document : documents) {
            String name = document.get("name").asText();
            if (name.equals("OrderSummary")) perRecord = document;
            if (name.equals("OrdersByStatus")) list = document;
        }
        assertNotNull(perRecord, documents.toString());
        assertNotNull(list, documents.toString());
        assertFalse(perRecord.has("kind"), "a per-record document must not claim kind: " + perRecord);
        assertEquals("list", list.get("kind").asText());
    }
}
