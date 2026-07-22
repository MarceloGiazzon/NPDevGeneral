package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies PanelRuntime serves an aggregate Workbench: the {@code metadata.workbench} descriptor plus
 * the nested aggregate tree loaded (by root id) via AggregateRuntime (P4 runtime slice / ADR-0005).
 */
class WorkbenchRuntimeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
          "concepts": [
            { "name": "Expedicao", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "cliente", "type": "string" } ] },
            { "name": "ExpedicaoItem", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "expedicaoId", "type": "uuid" }, { "name": "produtoId", "type": "string" } ] },
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

    private static CompiledModel compiledModel() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        return new ModelCompiler().compile(ast);
    }

    private static ConceptGateway inMemoryGateway() {
        List<ConceptRecord> data = new ArrayList<>();
        data.add(new ConceptRecord("Expedicao", "E1", "default", Map.of("cliente", "Cliente Alimentos")));
        data.add(new ConceptRecord("ExpedicaoItem", "I1", "default", Map.of("expedicaoId", "E1", "produtoId", "306")));
        data.add(new ConceptRecord("MovtoOrigem", "O1", "default", Map.of("itemSeq", "I1", "local", "F031")));
        data.add(new ConceptRecord("MovtoDestino", "D1", "default", Map.of("itemSeq", "I1", "local", "EXP01")));
        return new ConceptGateway() {
            @Override
            public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
                return data.stream()
                        .filter(r -> r.conceptName().equals(request.conceptName()) && r.id().equals(request.id()))
                        .findFirst();
            }

            @Override
            public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
                return data.stream()
                        .filter(r -> r.conceptName().equals(request.conceptName()))
                        .filter(r -> request.filterField() == null
                                || String.valueOf(r.data().get(request.filterField())).equals(request.filterValue()))
                        .toList();
            }

            @Override
            public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(ConceptReadRequest request, ExecutionContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    void servesWorkbenchDescriptorAndAggregateTree() throws Exception {
        CompiledModel model = compiledModel();
        ConceptGateway gateway = inMemoryGateway();
        AggregateRuntime aggregateRuntime = new AggregateRuntime(model, gateway);
        PanelRuntime panelRuntime = new PanelRuntime(null, null, model, gateway, null, null, aggregateRuntime);

        Map<String, Object> response = panelRuntime.loadPanel(
                "ExpedicaoWorkbench", Map.of("id", "E1"), ExecutionContext.anonymous());

        assertEquals("loadWorkbench", response.get("operation"));
        assertEquals("Expedicao", response.get("aggregate"));

        // Descriptor: header + itens section + origens/destinos bands.
        Map<String, Object> workbench = (Map<String, Object>) response.get("workbench");
        assertNotNull(workbench);
        List<Map<String, Object>> sections = (List<Map<String, Object>>) workbench.get("sections");
        assertEquals("itens", sections.get(0).get("collection"));

        // Data: the nested aggregate tree loaded by root id.
        Map<String, Object> tree = (Map<String, Object>) response.get("data");
        assertEquals("E1", tree.get("id"));
        assertEquals("Cliente Alimentos", tree.get("cliente"));
        List<Map<String, Object>> itens = (List<Map<String, Object>>) tree.get("itens");
        assertEquals(1, itens.size());
        assertEquals(1, ((List<?>) itens.get(0).get("origens")).size());
        assertEquals(1, ((List<?>) itens.get(0).get("destinos")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutIdReturnsDescriptorOnly() throws Exception {
        CompiledModel model = compiledModel();
        AggregateRuntime aggregateRuntime = new AggregateRuntime(model, inMemoryGateway());
        PanelRuntime panelRuntime = new PanelRuntime(null, null, model, inMemoryGateway(), null, null, aggregateRuntime);

        Map<String, Object> response = panelRuntime.loadPanel(
                "ExpedicaoWorkbench", Map.of(), ExecutionContext.anonymous());

        assertNotNull(response.get("workbench"));
        assertTrue(((Map<String, Object>) response.get("data")).isEmpty(), "no id -> empty data shell");
    }
}
