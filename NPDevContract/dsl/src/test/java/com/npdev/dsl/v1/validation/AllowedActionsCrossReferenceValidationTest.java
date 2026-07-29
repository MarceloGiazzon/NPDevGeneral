package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** REG-62/F9 (docs/FINAL_OPEN_ITEMS_PLAN.md): allowedActions (C8, a typed array) previously accepted
 * any well-formed string, so a typo silently produced a state where that action never appears in the
 * action rail -- the original bug's actual failure mode, still open after C8 typed the array.
 * LifecycleValidation now cross-references each entry against the concept's own AutoPanel workbench
 * actions (transaction.metadata.actions[].procedure). Same aggregate/autoPanel fixture shape as
 * AggregateWorkbenchExpansionTest (the mechanism only exists for aggregate-bound workbenches). */
class AllowedActionsCrossReferenceValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String allowedActionsJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.allowedactions", "version": "1.0",
              "concepts": [
                { "name": "Expedicao",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "estagio", "type": "enum", "enumValues": ["aberta", "confirmada"] } ],
                  "lifecycle": {
                    "statusField": "estagio",
                    "states": [
                      { "value": "aberta", "label": "Aberta", "initial": true%s },
                      { "value": "confirmada", "label": "Confirmada", "terminal": true } ],
                    "transitions": [ { "from": "aberta", "to": "confirmada" } ]
                  } },
                { "name": "ExpedicaoItem", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "expedicaoId", "type": "uuid" } ] }
              ],
              "aggregates": [
                { "name": "Expedicao", "root": "Expedicao",
                  "collections": [
                    { "name": "itens", "concept": "ExpedicaoItem", "childField": "expedicaoId", "ownership": "owned" }
                  ] }
              ],
              "autoPanels": [ { "aggregate": "Expedicao",
                "transaction": { "metadata": { "actions": [
                  { "label": "Gerar Demanda", "procedure": "GerarDemanda" },
                  { "procedure": "Recalcular" }
                ] } } } ]
            }
            """.formatted(allowedActionsJson);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    @Test
    void allowedActionsMatchingADeclaredWorkbenchActionValidatesClean() throws Exception {
        List<String> errors = validate(modelJson(", \"allowedActions\": [\"GerarDemanda\", \"Recalcular\"]"));
        assertTrue(errors.isEmpty(), "unexpected errors: " + errors);
    }

    @Test
    void allowedActionsWithNoUnknownEntriesLeavesNoStateUnrestricted() throws Exception {
        // confirmada declares no allowedActions at all -- must not be flagged.
        List<String> errors = validate(modelJson(", \"allowedActions\": [\"GerarDemanda\"]"));
        assertTrue(errors.isEmpty(), "unexpected errors: " + errors);
    }

    @Test
    void misspelledAllowedActionIsRejectedNamingTheValidOnes() throws Exception {
        List<String> errors = validate(modelJson(", \"allowedActions\": [\"GerarDemand\"]"));
        assertTrue(errors.stream().anyMatch(e ->
                        e.contains("allowedActions references unknown action 'GerarDemand'")
                                && e.contains("GerarDemanda") && e.contains("Recalcular")),
                "expected an unknown-action error naming the real declared actions, got: " + errors);
    }

    @Test
    void allowedActionsWithNoAutoPanelAtAllIsRejected() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.noautopanel", "version": "1.0",
              "concepts": [
                { "name": "Widget",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "status", "type": "enum", "enumValues": ["open", "closed"] } ],
                  "lifecycle": {
                    "statusField": "status",
                    "states": [
                      { "value": "open", "initial": true, "allowedActions": ["Anything"] },
                      { "value": "closed", "terminal": true } ],
                    "transitions": [ { "from": "open", "to": "closed" } ]
                  } }
              ]
            }
            """;
        List<String> errors = validate(json);
        assertTrue(errors.stream().anyMatch(e ->
                        e.contains("allowedActions references unknown action 'Anything'") && e.contains("(none)")),
                "expected an unknown-action error naming '(none)' declared, got: " + errors);
    }
}
