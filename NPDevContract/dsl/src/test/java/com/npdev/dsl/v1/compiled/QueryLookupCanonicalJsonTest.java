package com.npdev.dsl.v1.compiled;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Friction #5: query.lookups[] / computed[] survive parse -> compile -> canonical write -> read. */
class QueryLookupCanonicalJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "mosaic.lookup", "version": "1.0",
          "concepts": [
            { "name": "Cell", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "capId", "type": "string" } ] },
            { "name": "Owned", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "ownerUsername", "type": "string" },
              { "name": "capId", "type": "string" },
              { "name": "quantity", "type": "integer" } ] }
          ],
          "queries": [
            { "name": "ToDrink", "concept": "Cell", "groupBy": [ "capId" ],
              "aggregates": [ { "name": "needed", "fn": "count" } ],
              "lookups": [ { "name": "owned", "concept": "Owned", "fn": "sum", "field": "quantity",
                             "on": { "capId": "capId" }, "where": "ownerUsername == $user.id" } ],
              "computed": [ { "name": "toDrink", "expression": "needed - owned" } ] },
            { "name": "Needed", "concept": "Cell", "groupBy": [ "capId" ],
              "aggregates": [ { "name": "needed", "fn": "count" } ] }
          ]
        }
        """;

    @Test
    void lookupsAndComputedRoundTripAndAreOmittedWhenAbsent() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(MODEL)));
        String first = CompiledModelCanonicalJson.toJson(compiled);
        CompiledModel read = CompiledModelCanonicalJsonReader.fromJson(first);
        assertEquals(first, CompiledModelCanonicalJson.toJson(read));

        CompiledQuery toDrink = read.getQueries().stream().filter(q -> q.name().equals("ToDrink")).findFirst().orElseThrow();
        assertEquals(1, toDrink.lookups().size());
        CompiledQueryLookup owned = toDrink.lookups().get(0);
        assertEquals("owned", owned.name());
        assertEquals("Owned", owned.concept());
        assertEquals("sum", owned.fn());
        assertEquals("quantity", owned.field());
        assertEquals(Map.of("capId", "capId"), owned.on());
        assertEquals("ownerUsername == $user.id", owned.where());
        assertEquals(new CompiledQueryComputedColumn("toDrink", "needed - owned"), toDrink.computed().get(0));

        for (JsonNode query : MAPPER.readTree(first).get("queries")) {
            if ("Needed".equals(query.get("name").asText())) {
                assertFalse(query.has("lookups"), "a query without lookups must keep its canonical bytes");
                assertFalse(query.has("computed"), "a query without computed columns must keep its canonical bytes");
            }
        }
    }
}
