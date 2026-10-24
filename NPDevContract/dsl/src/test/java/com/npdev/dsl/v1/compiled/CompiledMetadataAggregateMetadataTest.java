package com.npdev.dsl.v1.compiled;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ENF-1: an aggregate's free-form metadata is served on its three invocations-catalog entries. */
class CompiledMetadataAggregateMetadataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aggregateMetadataIsServedOnEveryAggregateInvocation() throws Exception {
        JsonNode model = MAPPER.readTree("{\"dslVersion\":\"1.0.0\",\"namespace\":\"aggmeta.test\",\"version\":\"1.0\","
                + "\"concepts\":[{\"name\":\"Order\",\"fields\":[{\"name\":\"id\",\"type\":\"uuid\",\"id\":true,\"required\":true}]}],"
                + "\"aggregates\":[{\"name\":\"OrderAggregate\",\"root\":\"Order\","
                + "\"metadata\":{\"purpose\":\"one order tree\"}}]}");
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(model));

        JsonNode invocations = MAPPER.readTree(CompiledMetadataCanonicalJson.toJson(model, compiled))
                .path("catalogs").path("invocations");
        for (String id : new String[] {"aggregateRead:OrderAggregate", "aggregateCommit:OrderAggregate",
                "aggregateInvoke:OrderAggregate"}) {
            JsonNode entry = null;
            for (JsonNode candidate : invocations) {
                if (id.equals(candidate.path("id").asText())) {
                    entry = candidate;
                }
            }
            assertEquals("one order tree", entry == null ? null : entry.path("metadata").path("purpose").asText(), id);
        }
    }
}
