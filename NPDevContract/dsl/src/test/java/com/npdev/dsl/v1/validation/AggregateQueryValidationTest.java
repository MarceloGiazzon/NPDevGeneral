package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Move 10 B1 (LC-B1, MOVE10_AI_LOWCODE_PLAN Part B): compile-time validation of
 * {@code query.groupBy}/{@code aggregates}/{@code having}, including the security hard stop that
 * refuses an aggregate query on a concept declaring {@code access.read}.
 */
class AggregateQueryValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static String modelWithConceptAndQuery(String accessJson, String queryJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.aggregate", "version": "1.0",
              "concepts": [
                { "name": "ShipmentEvent", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "warehouseId", "type": "string" },
                  { "name": "shippedAt", "type": "date" },
                  { "name": "unitsShipped", "type": "integer" },
                  { "name": "note", "type": "string" } ]%s }
              ],
              "queries": [ %s ]
            }
            """.formatted(accessJson == null ? "" : ", \"access\": " + accessJson, queryJson);
    }

    @Test
    void validGroupByAndAggregatesOnAnUnrestrictedConceptPasses() throws Exception {
        List<String> errors = validate(modelWithConceptAndQuery(null, """
            { "name": "UnitsByWarehouse", "concept": "ShipmentEvent",
              "groupBy": [ "warehouseId" ],
              "aggregates": [ { "name": "total", "fn": "sum", "field": "unitsShipped" } ] }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("groupBy") || e.contains("aggregate")),
                "unexpected aggregate-query error, got: " + errors);
    }

    @Test
    void groupByOnAConceptDeclaringAccessReadIsRefused() throws Exception {
        List<String> errors = validate(modelWithConceptAndQuery(
                "{\"read\": \"warehouseId == $user.id\"}", """
            { "name": "UnitsByWarehouse", "concept": "ShipmentEvent",
              "groupBy": [ "warehouseId" ],
              "aggregates": [ { "name": "total", "fn": "sum", "field": "unitsShipped" } ] }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("groupBy/aggregates are not supported")
                        && e.contains("access.read")),
                "expected an access.read hard-stop error, got: " + errors);
    }

    @Test
    void sumOnANonNumericFieldIsRejected() throws Exception {
        List<String> errors = validate(modelWithConceptAndQuery(null, """
            { "name": "BadSum", "concept": "ShipmentEvent",
              "groupBy": [ "warehouseId" ],
              "aggregates": [ { "name": "total", "fn": "sum", "field": "note" } ] }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("requires a numeric field") && e.contains("note")),
                "expected a numeric-field error, got: " + errors);
    }

    @Test
    void bucketOnANonDateFieldIsRejected() throws Exception {
        List<String> errors = validate(modelWithConceptAndQuery(null, """
            { "name": "BadBucket", "concept": "ShipmentEvent",
              "groupBy": [ { "field": "warehouseId", "bucket": "month" } ],
              "aggregates": [ { "name": "count", "fn": "count" } ] }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("not date/datetime")),
                "expected a bucket-type error, got: " + errors);
    }

    @Test
    void unknownGroupByFieldIsRejected() throws Exception {
        List<String> errors = validate(modelWithConceptAndQuery(null, """
            { "name": "BadField", "concept": "ShipmentEvent",
              "groupBy": [ "bogusField" ],
              "aggregates": [ { "name": "count", "fn": "count" } ] }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("groupBy field not found") && e.contains("bogusField")),
                "expected an unknown-field error, got: " + errors);
    }

    @Test
    void countRequiresNoFieldAndPasses() throws Exception {
        List<String> errors = validate(modelWithConceptAndQuery(null, """
            { "name": "EventCount", "concept": "ShipmentEvent",
              "groupBy": [ "warehouseId" ],
              "aggregates": [ { "name": "eventCount", "fn": "count" } ] }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("groupBy") || e.contains("aggregate")),
                "unexpected error for a bare count, got: " + errors);
    }

    @Test
    void duplicateAggregateOutputNameIsRejected() throws Exception {
        List<String> errors = validate(modelWithConceptAndQuery(null, """
            { "name": "DupeNames", "concept": "ShipmentEvent",
              "groupBy": [ "warehouseId" ],
              "aggregates": [
                { "name": "total", "fn": "sum", "field": "unitsShipped" },
                { "name": "total", "fn": "avg", "field": "unitsShipped" } ] }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("duplicate aggregate output name") && e.contains("total")),
                "expected a duplicate-output-name error, got: " + errors);
    }

    @Test
    void plainQueryWithNoGroupByOrAggregatesIsUnaffected() throws Exception {
        List<String> errors = validate(modelWithConceptAndQuery(null, """
            { "name": "PlainQuery", "concept": "ShipmentEvent", "where": "unitsShipped > 0" }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("groupBy") || e.contains("aggregate")),
                "unexpected error for a non-aggregate query, got: " + errors);
    }
}
