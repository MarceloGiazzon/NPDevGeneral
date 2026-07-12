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
                { "name": "Expedicao",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "cliente", "type": "string" },
                    { "name": "estagio", "type": "string" } ],
                  "lifecycle": {
                    "statusField": "estagio",
                    "states": [
                      { "value": "aberta", "label": "Aberta", "initial": true },
                      { "value": "confirmada", "label": "Confirmada", "terminal": true } ],
                    "transitions": [ { "from": "aberta", "to": "confirmada", "actionLabel": "Confirmar" } ]
                  } },
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
              "autoPanels": [ { "aggregate": "Expedicao", "selection": { "filters": ["cliente"] },
                "transaction": { "metadata": {
                  "recompute": "Recalcular",
                  "bandPickers": {
                    "origens": { "panel": "MovtoOrigemSelection", "label": "Seleciona Ruas", "columns": ["local"] },
                    "semvias": { "label": "ignored — no panel" }
                  },
                  "actions": [
                  { "label": "Gerar Demanda", "procedure": "GerarDemanda" },
                  { "procedure": "Recalcular" },
                  { "label": "no-op" } ] } } } ]
            }
            """;
        CompiledModel model = compile(json);

        // The aggregate AutoPanel also emits a root Selection (list) panel for the workbench page.
        CompiledPanel selection = model.getPanels().stream()
                .filter(p -> "ExpedicaoSelection".equals(p.name())).findFirst()
                .orElseThrow(() -> new AssertionError("expected a root ExpedicaoSelection panel"));
        assertEquals("table", selection.layout().type());
        assertEquals("/expedicao", selection.route());

        CompiledPanel workbench = model.getPanels().stream()
                .filter(p -> "ExpedicaoWorkbench".equals(p.name())).findFirst()
                .orElseThrow(() -> new AssertionError("expected ExpedicaoWorkbench among "
                        + model.getPanels().stream().map(CompiledPanel::name).toList()));

        assertEquals("stack", workbench.layout().type());
        assertEquals("/expedicao/{id}", workbench.route());
        assertEquals("Expedicao", workbench.dataSources().get(0).concept());
        assertEquals("aggregate", workbench.metadata().get("dataVia"));
        assertEquals("ExpedicaoSelection", workbench.metadata().get("selectionPanel"));
        assertEquals(List.of("cliente"), workbench.metadata().get("filters"));

        Map<String, Object> wb = (Map<String, Object>) workbench.metadata().get("workbench");
        assertNotNull(wb);
        assertEquals("Expedicao", wb.get("aggregate"));
        assertEquals("Expedicao", wb.get("root"));
        assertEquals(List.of("cliente", "estagio", "id"), ((Map<String, Object>) wb.get("header")).get("fields"));

        // The root concept's lifecycle drives the status chip + per-state editability gating.
        Map<String, Object> lifecycle = (Map<String, Object>) wb.get("lifecycle");
        assertNotNull(lifecycle, "workbench descriptor should carry the root lifecycle");
        assertEquals("estagio", lifecycle.get("statusField"));
        List<Map<String, Object>> states = (List<Map<String, Object>>) lifecycle.get("states");
        Map<String, Object> aberta = states.stream().filter(s -> "aberta".equals(s.get("value"))).findFirst().orElseThrow();
        Map<String, Object> confirmada = states.stream().filter(s -> "confirmada".equals(s.get("value"))).findFirst().orElseThrow();
        assertEquals("Aberta", aberta.get("label"));
        assertEquals(Boolean.TRUE, aberta.get("editable"), "non-terminal state is editable");
        assertEquals(Boolean.FALSE, confirmada.get("editable"), "terminal state is read-only");

        // Procedure-over-aggregate actions (P6): declared under transaction.metadata.actions; the entry
        // without a procedure is dropped, and a missing label defaults to the procedure name.
        List<Map<String, Object>> actions = (List<Map<String, Object>>) wb.get("actions");
        assertNotNull(actions, "workbench descriptor should carry declared invoke actions");
        assertEquals(2, actions.size(), "the label-only entry (no procedure) is skipped");
        assertEquals("Gerar Demanda", actions.get(0).get("label"));
        assertEquals("GerarDemanda", actions.get(0).get("procedure"));
        assertEquals("Recalcular", actions.get(1).get("label"), "label defaults to the procedure name");
        assertEquals("Recalcular", actions.get(1).get("procedure"));

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

        // Reactive recompute (P3): the declared procedure surfaces on the workbench descriptor.
        assertEquals("Recalcular", wb.get("recompute"), "recompute procedure projected onto the workbench");

        // Per-band picker (C6): the origens band gets a picker; the entry without a panel is dropped;
        // destinos (undeclared) has none.
        Map<String, Object> origens = bands.get(0);
        Map<String, Object> picker = (Map<String, Object>) origens.get("picker");
        assertNotNull(picker, "origens band should carry its declared picker");
        assertEquals("MovtoOrigemSelection", picker.get("panel"));
        assertEquals("Seleciona Ruas", picker.get("label"));
        assertEquals(List.of("local"), picker.get("columns"));
        assertNull(bands.get(1).get("picker"), "undeclared band has no picker");
    }
}
