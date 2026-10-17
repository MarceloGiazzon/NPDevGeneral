package com.npdev.dsl.v1.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AutoPanel surface override: {@code <surface>.panel} replaces ONE synthesized surface with a declared panel. */
class AutoPanelSurfaceOverrideTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TEMPLATE = """
        {
          "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
          "concepts": [
            { "name": "Pedido", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "cliente", "type": "string" } ] },
            { "name": "Item", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "nome", "type": "string" } ] }
          ],
          "aggregates": [ { "name": "Pedido", "root": "Pedido" } ],
          "panels": [
            { "name": "PedidoPickerPanel", "route": "/pedido-picker", "title": "Picker",
              "dataSources": [ { "name": "rows", "concept": "Pedido" } ] },
            { "name": "ItemPanel", "route": "/item-panel", "title": "Items",
              "dataSources": [ { "name": "rows", "concept": "Item" } ] }
          ],
          "autoPanels": [ %s ]
        }
        """;

    private static ModelAst parse(String autoPanel) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(TEMPLATE.formatted(autoPanel)));
    }

    private static List<String> panelNames(CompiledModel model) {
        return model.getPanels().stream().map(CompiledPanel::name).toList();
    }

    @Test
    void aggregateSelectionOverrideSkipsTheSynthesizedSelectionAndKeepsTheWorkbench() throws Exception {
        String autoPanel = """
            { "aggregate": "Pedido", "selection": { "panel": "PedidoPickerPanel" } }""";
        assertEquals(List.of(), new SemanticValidator().validate(parse(autoPanel)));

        CompiledModel model = new ModelCompiler().compile(parse(autoPanel));
        List<String> names = panelNames(model);
        assertTrue(names.contains("PedidoPickerPanel"), names.toString());
        assertTrue(names.contains("PedidoWorkbench"), names.toString());
        assertFalse(names.contains("PedidoSelection"), "the overridden surface must not be synthesized: " + names);
        CompiledPanel workbench = model.getPanels().stream()
                .filter(p -> "PedidoWorkbench".equals(p.name())).findFirst().orElseThrow();
        assertEquals("PedidoPickerPanel", workbench.metadata().get("selectionPanel"));
    }

    @Test
    void aggregateTransactionOverrideKeepsTheSelectionPointingAtTheDeclaredPanel() throws Exception {
        String autoPanel = """
            { "aggregate": "Pedido", "transaction": { "panel": "PedidoPickerPanel" } }""";
        CompiledModel model = new ModelCompiler().compile(parse(autoPanel));
        List<String> names = panelNames(model);
        assertFalse(names.contains("PedidoWorkbench"), names.toString());
        CompiledPanel selection = model.getPanels().stream()
                .filter(p -> "PedidoSelection".equals(p.name())).findFirst().orElseThrow();
        assertEquals("PedidoPickerPanel", selection.metadata().get("workbenchPanel"));
    }

    @Test
    void conceptBoundOverrideSkipsOnlyThatSurface() throws Exception {
        String autoPanel = """
            { "concept": "Pedido", "name": "Pedidos", "route": "/pedidos",
              "selection": { "panel": "PedidoPickerPanel" } }""";
        assertEquals(List.of(), new SemanticValidator().validate(parse(autoPanel)));
        List<String> names = panelNames(new ModelCompiler().compile(parse(autoPanel)));
        assertFalse(names.contains("PedidosSelection"), names.toString());
        assertTrue(names.containsAll(List.of("PedidosDetail", "PedidosForm", "PedidosPrompt")), names.toString());
    }

    @Test
    void overriddenPromptSurfaceIsWhatOtherFormsPointAt() throws Exception {
        String json = TEMPLATE.formatted("""
            { "concept": "Item", "name": "Itens", "prompt": { "panel": "ItemPanel" } },
            { "concept": "Pedido", "name": "Pedidos" }""")
                .replace("{ \"name\": \"cliente\", \"type\": \"string\" }",
                        "{ \"name\": \"cliente\", \"type\": \"reference\", \"reference\": { \"target\": \"Item\" } }");
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(json)));
        List<String> names = panelNames(model);
        assertFalse(names.contains("ItensPrompt"), names.toString());
        String serialized = CompiledModelCanonicalJson.toJson(model);
        assertTrue(serialized.contains("ItemPanel"), "form FK should open the declared prompt panel");
    }

    @Test
    void unknownOrMisboundPanelIsARefusalWithASuggestedFix() throws Exception {
        List<String> missing = new SemanticValidator().validate(parse("""
            { "aggregate": "Pedido", "selection": { "panel": "Ghost" } }"""));
        assertTrue(missing.stream().anyMatch(e -> e.contains("selection.panel: panel not found: Ghost")
                && e.contains("suggestedFix") && e.contains("PedidoPickerPanel")), missing.toString());

        List<String> misbound = new SemanticValidator().validate(parse("""
            { "aggregate": "Pedido", "selection": { "panel": "ItemPanel" } }"""));
        assertTrue(misbound.stream().anyMatch(e -> e.contains("is bound to concept Item but this surface is bound to Pedido")
                && e.contains("PedidoPickerPanel")), misbound.toString());

        List<String> noDetail = new SemanticValidator().validate(parse("""
            { "aggregate": "Pedido", "detail": { "panel": "PedidoPickerPanel" } }"""));
        assertTrue(noDetail.stream().anyMatch(e -> e.contains("no detail surface")), noDetail.toString());
    }

    @Test
    void panelOverrideRoundTripsThroughCanonicalJsonAndIsOmittedWhenUndeclared() throws Exception {
        CompiledModel declared = new ModelCompiler().compile(parse("""
            { "aggregate": "Pedido", "selection": { "panel": "PedidoPickerPanel" } }"""));
        String json = CompiledModelCanonicalJson.toJson(declared);
        assertEquals("PedidoPickerPanel",
                CompiledModelCanonicalJsonReader.fromJson(json).getAutoPanels().get(0).selection().panel());

        CompiledModel plain = new ModelCompiler().compile(parse("""
            { "aggregate": "Pedido", "selection": { "layout": "grid" } }"""));
        assertFalse(CompiledModelCanonicalJson.toJson(plain).contains("\"panel\":"),
                "an undeclared panel must not be written, so existing outputs stay byte-identical");
    }
}
