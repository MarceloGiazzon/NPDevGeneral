package com.finalexec.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P8: {@code agentAccess.expose[].aggregate} -- get_/save_ tools whose save schema carries the root's
 * fields plus every collection as an array, routed to {@code /api/runtime/aggregate}, with results
 * compacted for the LLM (child ids and parent links dropped; a save answers with row counts).
 */
class AgentAggregateToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private HttpServer app;

    static CompiledModel model(String operations) throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "demo.mosaic", "version": "1.0",
              "concepts": [
                { "name": "Mosaic", "ui": { "label": "Mosaic" }, "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "title", "type": "string", "required": true },
                  { "name": "rows", "type": "integer", "required": true },
                  { "name": "cellCount", "type": "integer", "derivedExpression": "rows * 2" },
                  { "name": "photo", "type": "file" },
                  { "name": "pin", "type": "string", "sensitive": true } ] },
                { "name": "MosaicCell", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "mosaicId", "type": "reference", "required": true, "reference": { "target": "Mosaic" } },
                  { "name": "row", "type": "integer", "required": true },
                  { "name": "capId", "type": "string" } ] }
              ],
              "aggregates": [
                { "name": "MosaicAggregate", "root": "Mosaic",
                  "collections": [ { "name": "cells", "concept": "MosaicCell", "childField": "mosaicId", "ownership": "owned" } ] }
              ],
              "agentAccess": {
                "channels": { "mcp": { "enabled": true } },
                "expose": [ { "aggregate": "MosaicAggregate", "operations": %s, "description": "Cap mosaics." } ]
              }
            }
            """.formatted(operations);
        return new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(json)));
    }

    @BeforeEach
    void startFakeApp() throws Exception {
        app = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        app.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            byte[] in = exchange.getRequestBody().readAllBytes();
            if (in.length > 0) {
                bodies.add(MAPPER.readTree(in));
            }
            byte[] out = """
                {"aggregate":"MosaicAggregate","id":"m1","title":"Flag","rows":2,
                 "cells":[{"id":"c1","mosaicId":"m1","row":0,"capId":"k1"},{"id":"c2","mosaicId":"m1","row":1,"capId":null}]}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        app.start();
    }

    @AfterEach
    void stopFakeApp() {
        app.stop(0);
    }

    private AgentApiExecutor executor() {
        return new AgentApiExecutor(MAPPER, () -> app.getAddress().getPort());
    }

    private static final AgentApiExecutor.Credentials CREDS =
            new AgentApiExecutor.Credentials("Authorization", "Bearer t", "mcp");

    @Test
    void offersGetAndSaveWithTheWholeTreeInTheSaveSchema() throws Exception {
        List<AgentToolCatalog.AgentTool> tools = AgentToolCatalog.toolsFor(model("[\"get\", \"save\"]"), Set.of());
        assertEquals(List.of("get_MosaicAggregate", "save_MosaicAggregate"),
                tools.stream().map(AgentToolCatalog.AgentTool::name).toList());

        AgentToolCatalog.AgentTool save = tools.get(1);
        assertTrue(save.write());
        assertTrue(save.description().contains("every row you leave out of a list is deleted"));
        JsonNode schema = MAPPER.valueToTree(save.inputSchema());
        JsonNode props = schema.get("properties");
        assertTrue(props.has("id") && props.has("title") && props.has("rows") && props.has("cells"));
        assertFalse(props.has("cellCount"), "derived field is not writable");
        assertFalse(props.has("photo"), "file fields are never offered");
        assertFalse(props.has("pin"), "sensitive fields are never offered");
        assertEquals(Set.of("title", "rows"), Set.copyOf(MAPPER.convertValue(schema.get("required"), List.class)));

        JsonNode cell = props.get("cells").get("items");
        assertEquals("array", props.get("cells").get("type").asText());
        assertTrue(cell.get("properties").has("row") && cell.get("properties").has("capId"));
        assertFalse(cell.get("properties").has("mosaicId"), "the parent link is set by the commit");
        assertFalse(cell.get("properties").has("id"));
    }

    @Test
    void defaultsToGetOnly() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(
                new JsonModelParser().parse(MAPPER.readTree(modelJsonWithoutOperations())));
        assertEquals(List.of("get_MosaicAggregate"),
                AgentToolCatalog.toolsFor(compiled, Set.of()).stream().map(AgentToolCatalog.AgentTool::name).toList());
    }

    private static String modelJsonWithoutOperations() {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "demo.mosaic", "version": "1.0",
              "concepts": [ { "name": "Mosaic", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
              "aggregates": [ { "name": "MosaicAggregate", "root": "Mosaic" } ],
              "agentAccess": { "channels": { "mcp": { "enabled": true } }, "expose": [ { "aggregate": "MosaicAggregate" } ] }
            }
            """;
    }

    @Test
    void getLoadsTheTreeAndDropsChildIdsAndParentLinks() throws Exception {
        AgentToolCatalog.AgentTool get = AgentToolCatalog.toolsFor(model("[\"get\"]"), Set.of()).get(0);
        AgentApiExecutor.Outcome outcome = executor().execute(get, Map.of("id", "m1"), CREDS);

        assertEquals(List.of("GET /api/runtime/aggregate/MosaicAggregate/m1"), requests);
        JsonNode body = MAPPER.readTree(outcome.body());
        assertEquals("m1", body.get("id").asText());
        assertEquals("Flag", body.get("title").asText());
        JsonNode first = body.get("cells").get(0);
        assertFalse(first.has("id"));
        assertFalse(first.has("mosaicId"));
        assertEquals("k1", first.get("capId").asText());
        assertEquals(2, body.get("cells").size());
    }

    @Test
    void savePostsTheWholeDraftOnceAndAnswersWithRowCounts() throws Exception {
        AgentToolCatalog.AgentTool save = AgentToolCatalog.toolsFor(model("[\"save\"]"), Set.of()).get(0);
        Map<String, Object> draft = Map.of("title", "Flag", "rows", 2,
                "cells", List.of(Map.of("row", 0, "capId", "k1"), Map.of("row", 1)));
        AgentApiExecutor.Outcome outcome = executor().execute(save, draft, CREDS);

        assertTrue(outcome.ok());
        assertEquals(List.of("POST /api/runtime/aggregate/MosaicAggregate"), requests);
        assertEquals(2, bodies.get(0).get("cells").size());
        JsonNode body = MAPPER.readTree(outcome.body());
        assertEquals("m1", body.get("id").asText());
        assertEquals("2 rows saved", body.get("cells").asText());
    }

    @Test
    void replacingKeepsOmittedRootFieldsButNeverRefillsLists() throws Exception {
        AgentToolCatalog.AgentTool save = AgentToolCatalog.toolsFor(model("[\"save\"]"), Set.of()).get(0);
        executor().execute(save, Map.of("id", "m1", "title", "Renamed",
                "cells", List.of(Map.of("row", 0, "capId", "k9"))), CREDS);

        assertEquals(List.of("GET /api/runtime/aggregate/MosaicAggregate/m1",
                "POST /api/runtime/aggregate/MosaicAggregate"), requests);
        JsonNode posted = bodies.get(0);
        assertEquals("Renamed", posted.get("title").asText(), "what the agent sent wins");
        assertEquals(2, posted.get("rows").asInt(), "an omitted root field keeps its stored value");
        assertFalse(posted.has("aggregate"));
        assertEquals(1, posted.get("cells").size(), "children are replaced, never merged");
    }
}
