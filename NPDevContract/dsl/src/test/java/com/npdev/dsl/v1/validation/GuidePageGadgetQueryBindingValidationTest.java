package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Move 10 B2 (LC-B2, MOVE10_AI_LOWCODE_PLAN Part B): compile-time validation of a chart/KPI
 * gadget's binding to a named aggregate query -- "a dashboard that validates and renders empty is
 * the failure mode to design against," so every one of these must be a NAMED error, not a runtime
 * blank chart.
 */
class GuidePageGadgetQueryBindingValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static String modelWithQueryAndGadget(String queryJson, String gadgetJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.gadget", "version": "1.0",
              "concepts": [
                { "name": "ShipmentEvent", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "warehouseId", "type": "string" },
                  { "name": "shippedAt", "type": "date" },
                  { "name": "unitsShipped", "type": "integer" } ] }
              ],
              "queries": [ %s ],
              "guidePages": [
                { "name": "Dashboard", "gadgets": [ %s ] }
              ]
            }
            """.formatted(queryJson, gadgetJson);
    }

    private static final String AGGREGATE_QUERY = """
        { "name": "UnitsByWarehouse", "concept": "ShipmentEvent",
          "groupBy": [ "warehouseId" ],
          "aggregates": [ { "name": "total", "fn": "sum", "field": "unitsShipped" } ] }
        """;

    private static final String NO_GROUPBY_AGGREGATE_QUERY = """
        { "name": "TotalUnits", "concept": "ShipmentEvent",
          "aggregates": [ { "name": "total", "fn": "sum", "field": "unitsShipped" } ] }
        """;

    private static final String PLAIN_QUERY = """
        { "name": "AllShipments", "concept": "ShipmentEvent", "where": "unitsShipped > 0" }
        """;

    @Test
    void validKpiGadgetOnANoGroupByAggregateQueryPasses() throws Exception {
        List<String> errors = validate(modelWithQueryAndGadget(NO_GROUPBY_AGGREGATE_QUERY, """
            { "name": "kpi1", "type": "kpi", "query": "TotalUnits", "y": "total" }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("gadget")), "unexpected: " + errors);
    }

    @Test
    void validBarGadgetOnAGroupByAggregateQueryPasses() throws Exception {
        List<String> errors = validate(modelWithQueryAndGadget(AGGREGATE_QUERY, """
            { "name": "bar1", "type": "bar", "query": "UnitsByWarehouse", "x": "warehouseId", "y": "total" }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("gadget")), "unexpected: " + errors);
    }

    @Test
    void validTableGadgetNeedsNoAxesAndPasses() throws Exception {
        List<String> errors = validate(modelWithQueryAndGadget(AGGREGATE_QUERY, """
            { "name": "table1", "type": "table", "query": "UnitsByWarehouse" }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("gadget")), "unexpected: " + errors);
    }

    @Test
    void gadgetWithNoQueryIsRejected() throws Exception {
        List<String> errors = validate(modelWithQueryAndGadget(AGGREGATE_QUERY, """
            { "name": "kpi1", "type": "kpi", "y": "total" }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("query-bound gadgets must declare a query")),
                "expected: " + errors);
    }

    @Test
    void gadgetBoundToNonexistentQueryIsRejected() throws Exception {
        List<String> errors = validate(modelWithQueryAndGadget(AGGREGATE_QUERY, """
            { "name": "kpi1", "type": "kpi", "query": "DoesNotExist", "y": "total" }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("query not found: DoesNotExist")),
                "expected: " + errors);
    }

    @Test
    void gadgetBoundToANonAggregateQueryIsRejected() throws Exception {
        List<String> errors = validate(modelWithQueryAndGadget(PLAIN_QUERY, """
            { "name": "kpi1", "type": "kpi", "query": "AllShipments", "y": "total" }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("has no groupBy/aggregates")),
                "expected: " + errors);
    }

    @Test
    void barGadgetOnAQueryWithNoGroupByIsRejected() throws Exception {
        List<String> errors = validate(modelWithQueryAndGadget(NO_GROUPBY_AGGREGATE_QUERY, """
            { "name": "bar1", "type": "bar", "query": "TotalUnits", "x": "warehouseId", "y": "total" }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("has no groupBy")), "expected: " + errors);
    }

    @Test
    void xNamingAFieldTheQueryDoesNotProduceIsRejected() throws Exception {
        List<String> errors = validate(modelWithQueryAndGadget(AGGREGATE_QUERY, """
            { "name": "bar1", "type": "bar", "query": "UnitsByWarehouse", "x": "bogusField", "y": "total" }
            """));
        assertTrue(errors.stream().anyMatch(e ->
                        e.contains("x \"bogusField\" does not name a groupBy field of query UnitsByWarehouse")),
                "expected: " + errors);
    }

    @Test
    void yNamingAFieldTheQueryDoesNotProduceIsRejected() throws Exception {
        List<String> errors = validate(modelWithQueryAndGadget(AGGREGATE_QUERY, """
            { "name": "bar1", "type": "bar", "query": "UnitsByWarehouse", "x": "warehouseId", "y": "bogusOutput" }
            """));
        assertTrue(errors.stream().anyMatch(e ->
                        e.contains("y \"bogusOutput\" does not name an aggregates output of query UnitsByWarehouse")),
                "expected: " + errors);
    }

    @Test
    void seriesNamingAFieldTheQueryDoesNotProduceIsRejected() throws Exception {
        List<String> errors = validate(modelWithQueryAndGadget(AGGREGATE_QUERY, """
            { "name": "bar1", "type": "bar", "query": "UnitsByWarehouse", "x": "warehouseId", "y": "total",
              "series": "bogusSeries" }
            """));
        assertTrue(errors.stream().anyMatch(e ->
                        e.contains("series \"bogusSeries\" does not name a groupBy field of query UnitsByWarehouse")),
                "expected: " + errors);
    }

    @Test
    void kpiMissingYIsRejected() throws Exception {
        List<String> errors = validate(modelWithQueryAndGadget(NO_GROUPBY_AGGREGATE_QUERY, """
            { "name": "kpi1", "type": "kpi", "query": "TotalUnits" }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("y is required for this gadget type")),
                "expected: " + errors);
    }

    @Test
    void railGadgetTypesAreUnaffectedByQueryBindingValidation() throws Exception {
        List<String> errors = validate(modelWithQueryAndGadget(AGGREGATE_QUERY, """
            { "name": "recent", "type": "recent-items" }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("gadget")), "unexpected: " + errors);
    }
}
