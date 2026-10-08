package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledAgentAccessExposure;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P8: {@code agentAccess.expose[].aggregate} -- parse, compile-time checks, canonical JSON round trip. */
class AgentAggregateExposureValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static List<String> exposeErrors(String json) throws Exception {
        return new SemanticValidator().validate(parse(json)).stream()
                .filter(e -> e.contains("agentAccess.expose")).toList();
    }

    private static String model(String expose) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "demo.mosaic", "version": "1.0",
              "concepts": [
                { "name": "Mosaic", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "title", "type": "string", "required": true } ] },
                { "name": "MosaicCell", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "mosaicId", "type": "reference", "required": true, "reference": { "target": "Mosaic" } },
                  { "name": "row", "type": "integer", "required": true } ] }
              ],
              "aggregates": [
                { "name": "MosaicAggregate", "root": "Mosaic",
                  "collections": [ { "name": "cells", "concept": "MosaicCell", "childField": "mosaicId", "ownership": "owned" } ] }
              ],
              "agentAccess": {
                "channels": { "mcp": { "enabled": true } },
                "expose": %s
              }
            }
            """.formatted(expose);
    }

    @Test
    void validAggregateExposurePassesAndRoundTripsThroughCanonicalJson() throws Exception {
        String json = model("[ { \"aggregate\": \"MosaicAggregate\", \"operations\": [\"get\", \"save\"] } ]");
        assertEquals(List.of(), exposeErrors(json));

        CompiledModel compiled = new ModelCompiler().compile(parse(json));
        CompiledAgentAccessExposure exposure = CompiledModelCanonicalJsonReader
                .fromJson(CompiledModelCanonicalJson.toJson(compiled)).getAgentAccess().getExpose().get(0);
        assertEquals("MosaicAggregate", exposure.getAggregate());
        assertNull(exposure.getConcept());
        assertNull(exposure.getFlow());
        assertEquals(List.of("get", "save"), exposure.getOperations());
    }

    @Test
    void aggregateExposureDefaultsToReadOnlyGet() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model("[ { \"aggregate\": \"MosaicAggregate\" } ]")));
        assertEquals(List.of("get"), compiled.getAgentAccess().getExpose().get(0).getOperations());
    }

    @Test
    void refusesUnknownAggregateDuplicateAndConceptOnlyKeys() throws Exception {
        assertTrue(exposeErrors(model("[ { \"aggregate\": \"Nope\" } ]")).stream()
                .anyMatch(e -> e.contains("aggregate 'Nope' does not resolve")));
        assertTrue(exposeErrors(model("[ { \"aggregate\": \"MosaicAggregate\" }, { \"aggregate\": \"MosaicAggregate\" } ]"))
                .stream().anyMatch(e -> e.contains("is exposed more than once")));
        List<String> errors = exposeErrors(model(
                "[ { \"aggregate\": \"MosaicAggregate\", \"operations\": [\"list\"], \"fields\": [\"title\"] } ]"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("operation 'list' is not valid on an aggregate exposure")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("fields is concept-only, not valid on an aggregate")));
    }

    @Test
    void refusesTwoTargetsAndSaveOnAConcept() throws Exception {
        // Two targets never reach the semantic pass: the schema's oneOf refuses them at parse time.
        assertThrows(ModelSchemaValidationException.class,
                () -> parse(model("[ { \"aggregate\": \"MosaicAggregate\", \"concept\": \"Mosaic\" } ]")));
        assertTrue(exposeErrors(model("[ { \"concept\": \"Mosaic\", \"operations\": [\"save\"] } ]")).stream()
                .anyMatch(e -> e.contains("operation 'save' is aggregate-only")));
    }
}
