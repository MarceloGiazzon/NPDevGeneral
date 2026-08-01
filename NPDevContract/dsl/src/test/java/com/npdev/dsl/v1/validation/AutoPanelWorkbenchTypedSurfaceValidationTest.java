package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 7 W1 (docs/MOVE7_IMPLEMENTATION_SPEC.md): the typed transaction.actions/visibleWhen/
 * bandPickers replacements for the untyped transaction.metadata equivalents get real semantic
 * validation -- a declared procedure must exist, a visibleWhen/bandPickers key must name a real
 * address derived from the aggregate's own composition tree.
 */
class AutoPanelWorkbenchTypedSurfaceValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static String modelWithTransaction(String transactionJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.typed7.validation", "version": "1.0",
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
              "procedures": [
                { "name": "RealProcedure", "steps": [ { "name": "ret", "type": "return", "value": "$input" } ] }
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
                "transaction": %s } ]
            }
            """.formatted(transactionJson);
    }

    @Test
    void realProcedureNamesAndAddressesPassCleanly() throws Exception {
        List<String> errors = validate(modelWithTransaction("""
                {
                  "actions": [ { "procedure": "RealProcedure", "afterAction": "RealProcedure" } ],
                  "visibleWhen": { "itens": "$root.id != null", "itens.posicoes": "$root.id != null" },
                  "bandPickers": { "posicoes": { "panel": "PosicaoSelection" } }
                }
                """));
        assertTrue(errors.isEmpty(), "expected no validation errors, got: " + errors);
    }

    @Test
    void actionProcedureNotFoundIsRejected() throws Exception {
        List<String> errors = validate(modelWithTransaction(
                "{ \"actions\": [ { \"procedure\": \"NoSuchProcedure\" } ] }"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("transaction.actions")
                        && e.contains("procedure not found: NoSuchProcedure")),
                "expected a procedure-not-found error, got: " + errors);
    }

    @Test
    void actionAfterActionNotFoundIsRejected() throws Exception {
        List<String> errors = validate(modelWithTransaction(
                "{ \"actions\": [ { \"procedure\": \"RealProcedure\", \"afterAction\": \"NoSuchProcedure\" } ] }"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("transaction.actions")
                        && e.contains("afterAction names a procedure not found: NoSuchProcedure")),
                "expected an afterAction-not-found error, got: " + errors);
    }

    @Test
    void visibleWhenUnrecognizedAddressIsRejected() throws Exception {
        List<String> errors = validate(modelWithTransaction(
                "{ \"visibleWhen\": { \"itens.naoExiste\": \"$root.id != null\" } }"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("transaction.visibleWhen")
                        && e.contains("unrecognized address") && e.contains("itens.naoExiste")),
                "expected an unrecognized-address error, got: " + errors);
    }

    @Test
    void bandPickersUnrecognizedBandIsRejected() throws Exception {
        List<String> errors = validate(modelWithTransaction(
                "{ \"bandPickers\": { \"naoExiste\": { \"panel\": \"X\" } } }"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("transaction.bandPickers")
                        && e.contains("unrecognized band") && e.contains("naoExiste")),
                "expected an unrecognized-band error, got: " + errors);
    }
}
