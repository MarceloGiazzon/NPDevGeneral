package com.npdev.dsl.v1.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies a standalone selector expands into a reusable picker panel (P2 slice 3). */
class SelectorExpansionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CompiledModel compile(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new ModelCompiler().compile(ast);
    }

    @Test
    @SuppressWarnings("unchecked")
    void selectorExpandsToPickerPanel() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.sel", "version": "1.0",
              "concepts": [
                { "name": "LocalArmazenagem", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "rua", "type": "string" },
                  { "name": "disponivelQtd", "type": "integer" } ] }
              ],
              "selectors": [
                { "name": "SelecionaRuas", "concept": "LocalArmazenagem", "multiSelect": true,
                  "filters": ["area", "rua"], "columns": ["rua", "disponivelQtd"],
                  "returnMapping": { "local": "rua" } }
              ]
            }
            """;
        CompiledModel model = compile(json);

        Optional<CompiledPanel> found = model.getPanels().stream()
                .filter(p -> "SelecionaRuas".equals(p.name())).findFirst();
        assertTrue(found.isPresent(), "selector should expand to a panel named SelecionaRuas");
        CompiledPanel picker = found.get();

        assertEquals("table", picker.layout().type());
        assertEquals("/select/selecionaruas", picker.route());
        assertEquals("LocalArmazenagem", picker.dataSources().get(0).concept());
        assertEquals(List.of("rua", "disponivelQtd"), picker.layout().fields());
        assertEquals("selector", picker.metadata().get("surface"));
        assertEquals(Boolean.TRUE, picker.metadata().get("multiSelect"));
        assertEquals(List.of("area", "rua"), picker.metadata().get("filters"));
        assertEquals(Map.of("local", "rua"), picker.metadata().get("returnMapping"));
    }

    @Test
    void selectorColumnsDefaultToConceptFields() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.sel", "version": "1.0",
              "concepts": [
                { "name": "Local", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "rua", "type": "string" } ] }
              ],
              "selectors": [ { "name": "PickLocal", "concept": "Local" } ]
            }
            """;
        CompiledModel model = compile(json);
        CompiledPanel picker = model.getPanels().stream()
                .filter(p -> "PickLocal".equals(p.name())).findFirst().orElseThrow();
        // No columns declared -> defaults to the concept's (normalized) field order.
        assertEquals(List.of("id", "rua"), picker.layout().fields());
        assertEquals(Boolean.FALSE, picker.metadata().get("multiSelect"));
    }
}
