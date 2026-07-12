package com.npdev.dsl.v1.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies an aggregate-bound AutoPanel expands into a multi-level Workbench descriptor (P4 slice 1). */
class AggregateWorkbenchExpansionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CompiledModel compile(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new ModelCompiler().compile(ast);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregateAutoPanelExpandsToWorkbenchDescriptor() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
              "concepts": [
                { "name": "Expedicao", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "cliente", "type": "string" } ] },
                { "name": "ExpedicaoItem", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "expedicaoId", "type": "uuid" },
                  { "name": "produtoId", "type": "string" } ] },
                { "name": "MovtoOrigem", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "itemSeq", "type": "uuid" }, { "name": "local", "type": "string" } ] },
                { "name": "MovtoDestino", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "itemSeq", "type": "uuid" }, { "name": "local", "type": "string" } ] }
              ],
              "aggregates": [
                { "name": "Expedicao", "root": "Expedicao",
                  "collections": [
                    { "name": "itens", "concept": "ExpedicaoItem", "childField": "expedicaoId", "ownership": "owned",
                      "collections": [
                        { "name": "origens", "concept": "MovtoOrigem", "childField": "itemSeq", "ownership": "owned" },
                        { "name": "destinos", "concept": "MovtoDestino", "childField": "itemSeq", "ownership": "owned" }
                      ] }
                  ] }
              ],
              "autoPanels": [ { "aggregate": "Expedicao" } ]
            }
            """;
        CompiledModel model = compile(json);

        CompiledPanel workbench = model.getPanels().stream()
                .filter(p -> "ExpedicaoWorkbench".equals(p.name())).findFirst()
                .orElseThrow(() -> new AssertionError("expected ExpedicaoWorkbench among "
                        + model.getPanels().stream().map(CompiledPanel::name).toList()));

        assertEquals("stack", workbench.layout().type());
        assertEquals("/expedicao/{id}", workbench.route());
        assertEquals("Expedicao", workbench.dataSources().get(0).concept());
        assertEquals("aggregate", workbench.metadata().get("dataVia"));

        Map<String, Object> wb = (Map<String, Object>) workbench.metadata().get("workbench");
        assertNotNull(wb);
        assertEquals("Expedicao", wb.get("aggregate"));
        assertEquals("Expedicao", wb.get("root"));
        assertEquals(List.of("cliente", "id"), ((Map<String, Object>) wb.get("header")).get("fields"));

        List<Map<String, Object>> sections = (List<Map<String, Object>>) wb.get("sections");
        assertEquals(1, sections.size(), "one first-level collection -> one section");
        Map<String, Object> itens = sections.get(0);
        assertEquals("itens", itens.get("collection"));
        assertEquals("ExpedicaoItem", itens.get("concept"));
        assertEquals("expedicaoId", itens.get("childField"));

        // The section's two child collections become its parallel bands.
        List<Map<String, Object>> bands = (List<Map<String, Object>>) itens.get("bands");
        assertEquals(List.of("origens", "destinos"), bands.stream().map(b -> b.get("collection")).toList());
        assertEquals("MovtoOrigem", bands.get(0).get("concept"));
        assertEquals("itemSeq", bands.get(0).get("childField"));
        assertEquals(List.of("id", "itemSeq", "local"), bands.get(1).get("columns"));
    }
}
