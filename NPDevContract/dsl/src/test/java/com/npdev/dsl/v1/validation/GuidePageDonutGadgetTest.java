package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** VIEW-2: the {@code donut} dashboard gadget is a query-bound share-of-total chart. */
class GuidePageDonutGadgetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String gadgetJson, String queryJson) throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.donut", "version": "1.0",
              "concepts": [
                { "name": "ShipmentEvent", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "warehouseId", "type": "string" },
                  { "name": "unitsShipped", "type": "integer" } ] }
              ],
              "queries": [ %s ],
              "guidePages": [ { "name": "Dashboard", "gadgets": [ %s ] } ]
            }
            """.formatted(queryJson, gadgetJson);
        return new SemanticValidator().validate(new JsonModelParser().parse(MAPPER.readTree(json)));
    }

    private static final String GROUPED = """
        { "name": "UnitsByWarehouse", "concept": "ShipmentEvent", "groupBy": [ "warehouseId" ],
          "aggregates": [ { "name": "total", "fn": "sum", "field": "unitsShipped" } ] }
        """;

    private static final String UNGROUPED = """
        { "name": "TotalUnits", "concept": "ShipmentEvent",
          "aggregates": [ { "name": "total", "fn": "sum", "field": "unitsShipped" } ] }
        """;

    @Test
    void aDonutOverAGroupedAggregateQueryPasses() throws Exception {
        List<String> errors = validate("""
            { "name": "share", "type": "donut", "query": "UnitsByWarehouse", "x": "warehouseId", "y": "total" }
            """, GROUPED);
        assertTrue(errors.stream().noneMatch(e -> e.contains("gadget")), "unexpected: " + errors);
    }

    @Test
    void aDonutWithoutAQueryIsRejected() throws Exception {
        List<String> errors = validate("""
            { "name": "share", "type": "donut", "x": "warehouseId", "y": "total" }
            """, GROUPED);
        assertTrue(errors.stream().anyMatch(e -> e.contains("query-bound gadgets must declare a query")), "got: " + errors);
    }

    @Test
    void aDonutNeedsTheSliceLabelAndTheSliceSize() throws Exception {
        List<String> errors = validate("""
            { "name": "share", "type": "donut", "query": "UnitsByWarehouse" }
            """, GROUPED);
        assertTrue(errors.stream().anyMatch(e -> e.contains("x (the slice label) is required")), "got: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("y (the slice size) is required")), "got: " + errors);
    }

    @Test
    void aDonutRejectsAnXThatIsNotAGroupByFieldAndAYThatIsNotAnAggregate() throws Exception {
        List<String> errors = validate("""
            { "name": "share", "type": "donut", "query": "UnitsByWarehouse", "x": "nope", "y": "alsoNope" }
            """, GROUPED);
        assertTrue(errors.stream().anyMatch(e -> e.contains("does not name a groupBy field")), "got: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("does not name an aggregates output")), "got: " + errors);
    }

    @Test
    void aDonutOverAQueryWithoutGroupByIsRejected() throws Exception {
        List<String> errors = validate("""
            { "name": "share", "type": "donut", "query": "TotalUnits", "x": "warehouseId", "y": "total" }
            """, UNGROUPED);
        assertTrue(errors.stream().anyMatch(e -> e.contains("a donut gadget needs one categorical field")), "got: " + errors);
    }

    @Test
    void aDonutDoesNotSupportSeries() throws Exception {
        List<String> errors = validate("""
            { "name": "share", "type": "donut", "query": "UnitsByWarehouse", "x": "warehouseId", "y": "total",
              "series": "warehouseId" }
            """, GROUPED);
        assertTrue(errors.stream().anyMatch(e -> e.contains("does not support series")), "got: " + errors);
    }
}
