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

    /**
     * S4 (roadmap B27, ADR-0011 D1): groupBy join validation -- {@code ShipmentEvent.warehouse} is a
     * reference field pointing at {@code Warehouse}, which has its own {@code region} field.
     * {@code warehouseAccessJson} lets a test declare {@code access.read} on the JOIN TARGET (not
     * the base concept) to prove C3's widened guard.
     */
    private static String modelWithJoinAndQuery(String warehouseAccessJson, String queryJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.aggregate.join", "version": "1.0",
              "concepts": [
                { "name": "Warehouse", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "region", "type": "string" },
                  { "name": "openedAt", "type": "date" } ]%s },
                { "name": "ShipmentEvent", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "warehouse", "type": "reference", "reference": { "target": "Warehouse" } },
                  { "name": "unitsShipped", "type": "integer" } ] }
              ],
              "queries": [ %s ]
            }
            """.formatted(warehouseAccessJson == null ? "" : ", \"access\": " + warehouseAccessJson, queryJson);
    }

    @Test
    void groupByJoinThroughAReferenceFieldResolves() throws Exception {
        List<String> errors = validate(modelWithJoinAndQuery(null, """
            { "name": "UnitsByRegion", "concept": "ShipmentEvent",
              "groupBy": [ "warehouse.region" ],
              "aggregates": [ { "name": "total", "fn": "sum", "field": "unitsShipped" } ] }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("groupBy") || e.contains("aggregate")),
                "unexpected error for a valid groupBy join, got: " + errors);
    }

    @Test
    void groupByJoinBucketValidatesAgainstTheTargetFieldsType() throws Exception {
        List<String> errors = validate(modelWithJoinAndQuery(null, """
            { "name": "UnitsByOpenMonth", "concept": "ShipmentEvent",
              "groupBy": [ { "field": "warehouse.openedAt", "bucket": "month" } ],
              "aggregates": [ { "name": "total", "fn": "sum", "field": "unitsShipped" } ] }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("groupBy") || e.contains("aggregate")),
                "unexpected error for a valid bucketed groupBy join, got: " + errors);
    }

    @Test
    void groupByJoinThroughANonReferenceFieldIsRejected() throws Exception {
        List<String> errors = validate(modelWithJoinAndQuery(null, """
            { "name": "BadJoin", "concept": "ShipmentEvent",
              "groupBy": [ "unitsShipped.region" ],
              "aggregates": [ { "name": "count", "fn": "count" } ] }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("is not a reference field")),
                "expected a not-a-reference-field error, got: " + errors);
    }

    @Test
    void groupByJoinThroughAnUnknownReferenceFieldIsRejected() throws Exception {
        List<String> errors = validate(modelWithJoinAndQuery(null, """
            { "name": "BadJoin", "concept": "ShipmentEvent",
              "groupBy": [ "bogusRef.region" ],
              "aggregates": [ { "name": "count", "fn": "count" } ] }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("groupBy join field not found") && e.contains("bogusRef")),
                "expected an unknown-join-field error, got: " + errors);
    }

    @Test
    void groupByJoinToAnUnknownTargetFieldIsRejected() throws Exception {
        List<String> errors = validate(modelWithJoinAndQuery(null, """
            { "name": "BadJoin", "concept": "ShipmentEvent",
              "groupBy": [ "warehouse.bogusField" ],
              "aggregates": [ { "name": "count", "fn": "count" } ] }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("groupBy join target field not found") && e.contains("bogusField")),
                "expected an unresolvable-target-field error, got: " + errors);
    }

    /** S8 W1.1: two hops used to be refused by the GRAMMAR outright; now the grammar accepts it, so
     *  this path is refused instead by SEMANTIC resolution -- {@code region} (the mid-chain hop) is
     *  a plain string field, not a reference, so the chain cannot be walked through it. */
    @Test
    void midChainNonReferenceFieldIsRejected() throws Exception {
        List<String> errors = validate(modelWithJoinAndQuery(null, """
            { "name": "BadJoin", "concept": "ShipmentEvent",
              "groupBy": [ "warehouse.region.extra" ],
              "aggregates": [ { "name": "count", "fn": "count" } ] }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("is not a reference field") && e.contains("region")),
                "expected a mid-chain not-a-reference-field rejection, got: " + errors);
    }

    /** S8 W1.1 (roadmap deferred item #1): a join chain longer than
     *  {@code GroupByJoinGrammar.MAX_JOIN_HOPS} is a named grammar-level compile error, never
     *  silently truncated -- independent of whether the path's fields even exist on any concept. */
    @Test
    void moreJoinHopsThanTheCapAreRejectedAsAnUnsupportedPath() throws Exception {
        List<String> errors = validate(modelWithJoinAndQuery(null, """
            { "name": "BadJoin", "concept": "ShipmentEvent",
              "groupBy": [ "a.b.c.d.e" ],
              "aggregates": [ { "name": "count", "fn": "count" } ] }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("groupBy field cannot be parsed")
                        && e.contains("exceeds the cap of 3")),
                "expected a hop-cap-exceeded rejection, got: " + errors);
    }

    /** S8 W1.1: a genuine 2-hop join (both hops real reference fields) resolves cleanly, and the
     *  widened C3 access.read guard checks the FAR hop's target concept (Country), not just the
     *  first hop's (Warehouse) -- proven by the paired refusal test right below. */
    @Test
    void twoHopGroupByJoinResolves() throws Exception {
        List<String> errors = validate(modelWithTwoHopJoinAndQuery(null, """
            { "name": "UnitsByCountry", "concept": "ShipmentEvent",
              "groupBy": [ "warehouse.country.name" ],
              "aggregates": [ { "name": "total", "fn": "sum", "field": "unitsShipped" } ] }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("groupBy") || e.contains("aggregate")),
                "unexpected error for a valid two-hop groupBy join, got: " + errors);
    }

    /** C3 RED, widened to a SECOND hop: the FAR concept in the chain (Country) declares access.read
     *  while the NEAR one (Warehouse) does not -- the guard must still fire, proving the loop checks
     *  every hop, not just the first. */
    @Test
    void twoHopGroupByJoinCrossingIntoAConceptDeclaringAccessReadAtTheFarHopIsRefused() throws Exception {
        List<String> errors = validate(modelWithTwoHopJoinAndQuery(
                "{\"read\": \"name == $user.region\"}", """
            { "name": "UnitsByCountry", "concept": "ShipmentEvent",
              "groupBy": [ "warehouse.country.name" ],
              "aggregates": [ { "name": "total", "fn": "sum", "field": "unitsShipped" } ] }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("crosses into concept Country")
                        && e.contains("access.read")),
                "expected the widened access.read hard stop to fire for the FAR join hop, got: " + errors);
    }

    /**
     * S8 W1.1: same fixture family as {@link #modelWithJoinAndQuery}, extended with a THIRD concept
     * ({@code Country}) so {@code Warehouse.country -> Country} gives a genuine two-hop chain
     * ({@code "warehouse.country.name"}) with two REAL reference-field hops, distinct from the
     * single-hop fixture above. {@code countryAccessJson} lets a test declare {@code access.read} on
     * the FAR hop's target (Country, not Warehouse) to prove C3 widens to every hop, not just the
     * first.
     */
    private static String modelWithTwoHopJoinAndQuery(String countryAccessJson, String queryJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.aggregate.join.twohop", "version": "1.0",
              "concepts": [
                { "name": "Country", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "name", "type": "string" } ]%s },
                { "name": "Warehouse", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "region", "type": "string" },
                  { "name": "country", "type": "reference", "reference": { "target": "Country" } } ] },
                { "name": "ShipmentEvent", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "warehouse", "type": "reference", "reference": { "target": "Warehouse" } },
                  { "name": "unitsShipped", "type": "integer" } ] }
              ],
              "queries": [ %s ]
            }
            """.formatted(countryAccessJson == null ? "" : ", \"access\": " + countryAccessJson, queryJson);
    }

    /** C3 RED: the access.read hard stop widens to the WHOLE join path -- a join target declaring
     *  access.read must refuse the query even though the BASE concept (ShipmentEvent) declares none. */
    @Test
    void groupByJoinCrossingIntoAConceptDeclaringAccessReadIsRefused() throws Exception {
        List<String> errors = validate(modelWithJoinAndQuery(
                "{\"read\": \"region == $user.region\"}", """
            { "name": "UnitsByRegion", "concept": "ShipmentEvent",
              "groupBy": [ "warehouse.region" ],
              "aggregates": [ { "name": "total", "fn": "sum", "field": "unitsShipped" } ] }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("crosses into concept Warehouse")
                        && e.contains("access.read")),
                "expected the widened access.read hard stop to fire for the join target, got: " + errors);
    }
}
