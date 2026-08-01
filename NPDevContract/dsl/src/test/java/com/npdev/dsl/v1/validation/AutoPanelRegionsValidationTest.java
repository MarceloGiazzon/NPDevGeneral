package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 6 Move D (docs/MOVE6_TYPED_SURFACE_PLAN.md §5): a transaction.regions key must name a real
 * address derived from the aggregate's own composition tree; render:"component" must also declare
 * a component name.
 */
class AutoPanelRegionsValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static String modelWithRegions(String regionsJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.regions.validation", "version": "1.0",
              "concepts": [
                { "name": "Movimento", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "MovimentoItem", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "movimentoId", "type": "uuid" } ] },
                { "name": "Posicao", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "itemId", "type": "uuid" } ] }
              ],
              "aggregates": [
                { "name": "Movimento", "root": "Movimento",
                  "collections": [
                    { "name": "itens", "concept": "MovimentoItem", "childField": "movimentoId", "ownership": "owned",
                      "collections": [
                        { "name": "posicoes", "concept": "Posicao", "childField": "itemId", "ownership": "owned" }
                      ] }
                  ] }
              ],
              "autoPanels": [ { "aggregate": "Movimento",
                "transaction": { "regions": %s } } ]
            }
            """.formatted(regionsJson);
    }

    @Test
    void realAddressesWithComponentNamesPassCleanly() throws Exception {
        List<String> errors = validate(modelWithRegions("""
                {
                  "header": { "render": "component", "component": "movimento-header" },
                  "itens": { "render": "component", "component": "itens-grid" },
                  "itens.posicoes": { "render": "component", "component": "posicao-grid" }
                }
                """));
        assertTrue(errors.isEmpty(), "expected no validation errors, got: " + errors);
    }

    @Test
    void unrecognizedAddressIsRejected() throws Exception {
        List<String> errors = validate(modelWithRegions(
                "{ \"itens.naoExiste\": { \"render\": \"component\", \"component\": \"x\" } }"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("unrecognized region address") && e.contains("itens.naoExiste")),
                "expected an unrecognized-address error, got: " + errors);
    }

    @Test
    void componentRenderWithNoComponentNameIsRejected() throws Exception {
        List<String> errors = validate(modelWithRegions(
                "{ \"itens\": { \"render\": \"component\" } }"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("transaction.regions.itens")
                        && e.contains("no component name is declared")),
                "expected a missing-component-name error, got: " + errors);
    }
}
