package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): validation for aggregate.balances[],
 *  aggregateCollection.lookupFields[] and workbenchAction.checkBalances. */
class AggregateBalanceValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    /** A minimal, otherwise-valid model: StockTransfer -> items -> positions (depth-2), one
     *  AvailableQuantityQuery, one balance rule, one lookupField, and a typed workbench action.
     *  Each parameter lets a test corrupt exactly one thing while leaving everything else valid. */
    private static String model(
            String balanceName, String balanceCollection, String leftValue, String rightValue,
            String lookupQuery, String actionProcedure, String actionCheckBalances) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.balance.test", "version": "1.0",
              "concepts": [
                { "name": "StockTransfer", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Item", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "stockTransferId", "type": "uuid", "required": true } ] },
                { "name": "Position", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "itemId", "type": "uuid", "required": true },
                  { "name": "role", "type": "string" },
                  { "name": "quantity", "type": "integer" },
                  { "name": "localId", "type": "string" } ] },
                { "name": "StockLocation", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "disponivel", "type": "integer" } ] }
              ],
              "queries": [
                { "name": "AvailableQuantityQuery", "concept": "StockLocation", "where": "disponivel >= 0" }
              ],
              "aggregates": [
                {
                  "name": "StockTransferAggregate",
                  "root": "StockTransfer",
                  "collections": [
                    { "name": "items", "concept": "Item", "childField": "stockTransferId", "ownership": "owned",
                      "collections": [
                        { "name": "positions", "concept": "Position", "childField": "itemId", "ownership": "owned",
                          "lookupFields": [
                            { "name": "maxQuantity", "query": "%s", "joinField": "localId", "valueField": "disponivel" }
                          ]
                        }
                      ]
                    }
                  ],
                  "balances": [
                    { "name": "%s", "collection": "%s", "discriminatorField": "role",
                      "leftValue": "%s", "rightValue": "%s", "quantityField": "quantity" }
                  ]
                }
              ],
              "autoPanels": [
                {
                  "name": "StockTransferWorkbench",
                  "aggregate": "StockTransferAggregate",
                  "route": "/stock-transfers",
                  "transaction": {
                    "actions": [
                      { "label": "Action"%s%s }
                    ]
                  }
                }
              ]
            }
            """.formatted(
                lookupQuery, balanceName, balanceCollection, leftValue, rightValue,
                actionProcedure == null ? "" : ", \"procedure\": \"" + actionProcedure + "\"",
                actionCheckBalances == null ? "" : ", \"checkBalances\": [\"" + actionCheckBalances + "\"]");
    }

    private static String validModel() {
        return model("OrigemDestinoBalance", "items.positions", "Origem", "Destino",
                "AvailableQuantityQuery", null, "OrigemDestinoBalance");
    }

    @Test
    void validBalanceLookupFieldAndCheckBalancesActionProduceNoRelatedErrors() throws Exception {
        List<String> errors = validate(validModel());
        assertTrue(errors.isEmpty(), "expected no errors, got: " + errors);
    }

    // Note: no JSON-level test for "balance name is required" -- the schema's own minLength:1 on
    // aggregateBalance.name rejects a blank name before SemanticValidator ever runs (a
    // ModelSchemaValidationException, not a semantic error list), the same redundant-but-harmless
    // layering aggregate.invariants' own name check already has.

    @Test
    void balanceUnresolvableCollectionPathIsRejected() throws Exception {
        List<String> errors = validate(
                model("OrigemDestinoBalance", "items.nope", "Origem", "Destino",
                        "AvailableQuantityQuery", null, "OrigemDestinoBalance"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("collection not found: items.nope")),
                "got: " + errors);
    }

    @Test
    void balanceLeftAndRightValueCannotBeTheSame() throws Exception {
        List<String> errors = validate(
                model("OrigemDestinoBalance", "items.positions", "Origem", "Origem",
                        "AvailableQuantityQuery", null, "OrigemDestinoBalance"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("cannot both be the same value")),
                "got: " + errors);
    }

    @Test
    void lookupFieldUnresolvableQueryIsRejected() throws Exception {
        List<String> errors = validate(
                model("OrigemDestinoBalance", "items.positions", "Origem", "Destino",
                        "NoSuchQuery", null, "OrigemDestinoBalance"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("query not found: NoSuchQuery")),
                "got: " + errors);
    }

    @Test
    void workbenchActionWithBothProcedureAndCheckBalancesIsRejected() throws Exception {
        List<String> errors = validate(
                model("OrigemDestinoBalance", "items.positions", "Origem", "Destino",
                        "AvailableQuantityQuery", "SomeProcedure", "OrigemDestinoBalance"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("exactly one of procedure or checkBalances")),
                "got: " + errors);
    }

    @Test
    void workbenchActionWithNeitherProcedureNorCheckBalancesIsRejected() throws Exception {
        List<String> errors = validate(
                model("OrigemDestinoBalance", "items.positions", "Origem", "Destino",
                        "AvailableQuantityQuery", null, null));
        assertTrue(errors.stream().anyMatch(e -> e.contains("exactly one of procedure or checkBalances")),
                "got: " + errors);
    }

    @Test
    void workbenchActionCheckBalancesNamingAnUndeclaredRuleIsRejected() throws Exception {
        List<String> errors = validate(
                model("OrigemDestinoBalance", "items.positions", "Origem", "Destino",
                        "AvailableQuantityQuery", null, "NoSuchBalanceRule"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("checkBalances names a balance rule not found")),
                "got: " + errors);
    }

    @Test
    void duplicateBalanceNameWithinAnAggregateIsRejected() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.balance.duptest", "version": "1.0",
              "concepts": [
                { "name": "StockTransfer", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Position", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "stockTransferId", "type": "uuid", "required": true },
                  { "name": "role", "type": "string" },
                  { "name": "quantity", "type": "integer" } ] }
              ],
              "aggregates": [
                {
                  "name": "StockTransferAggregate",
                  "root": "StockTransfer",
                  "collections": [
                    { "name": "positions", "concept": "Position", "childField": "stockTransferId", "ownership": "owned" }
                  ],
                  "balances": [
                    { "name": "SameName", "collection": "positions", "discriminatorField": "role",
                      "leftValue": "Origem", "rightValue": "Destino", "quantityField": "quantity" },
                    { "name": "SameName", "collection": "positions", "discriminatorField": "role",
                      "leftValue": "Origem", "rightValue": "Destino", "quantityField": "quantity" }
                  ]
                }
              ]
            }
            """;
        List<String> errors = validate(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains("duplicate balance name")), "got: " + errors);
    }
}
