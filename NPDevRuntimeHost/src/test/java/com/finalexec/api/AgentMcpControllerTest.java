package com.finalexec.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.agent.AgentApiExecutor;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AGENT-1 (A5): the MCP endpoint's JSON-RPC surface against a REAL compiled model with an
 * {@code agentAccess} block -- protocol negotiation, the per-caller tool list (role-filtered), the
 * tool call forwarding the caller's OWN credentials, and every refusal (MCP disabled, a foreign
 * Origin, bad JSON, batching, unknown method/tool). The loopback REST call itself is
 * {@link AgentApiExecutor}'s job; here it is a mock that records what it was asked to forward.
 */
class AgentMcpControllerTest {

    private static final String MODEL = """
            {
              "namespace": "mcp.demo",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "agentAccess": {
                "assistant": { "name": "Shop Assistant", "instructions": "Answer briefly." },
                "channels": { "mcp": { "enabled": true } },
                "expose": [
                  { "concept": "Pigment", "operations": ["list", "get"], "description": "Pigments for sale." },
                  { "concept": "StockEntry", "operations": ["list", "update"], "roles": ["Staff"] }
                ]
              },
              "concepts": [
                {
                  "name": "Pigment",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "name", "type": "string", "required": true }
                  ]
                },
                {
                  "name": "StockEntry",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "kg", "type": "decimal", "precision": 10, "scale": 2 }
                  ]
                }
              ]
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
    private final AgentApiExecutor executor = Mockito.mock(AgentApiExecutor.class);

    private AgentMcpController controller(String modelJson, String... roles) throws Exception {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("acme", "ann").withRoles(Set.of(roles)));
        return new AgentMcpController(runtimeContextService, new ModelHolder(compile(modelJson)), executor, mapper);
    }

    private JsonNode post(AgentMcpController controller, MockHttpServletRequest request, String body) {
        ResponseEntity<?> response = controller.handle(request, body);
        assertEquals(200, response.getStatusCode().value(), "JSON-RPC answers ride on HTTP 200");
        return mapper.valueToTree(response.getBody());
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        request.setServerName("shop.example");
        return request;
    }

    @Test
    void initializeNegotiatesTheProtocolAndCarriesTheAssistantInstructions() throws Exception {
        AgentMcpController controller = controller(MODEL);

        JsonNode known = post(controller, request(),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}");
        assertEquals("2025-03-26", known.at("/result/protocolVersion").asText(), "a supported version is echoed");
        assertEquals("mcp.demo", known.at("/result/serverInfo/name").asText());
        assertEquals("Answer briefly.", known.at("/result/instructions").asText());
        assertFalse(known.at("/result/capabilities/tools/listChanged").asBoolean());

        JsonNode unknown = post(controller, request(),
                "{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
        assertEquals("2025-06-18", unknown.at("/result/protocolVersion").asText(), "otherwise the latest is offered");
        assertEquals("a", unknown.path("id").asText(), "a string id round-trips");

        JsonNode ping = post(controller, request(), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");
        assertTrue(ping.path("result").isObject());
    }

    @Test
    void theToolListIsFilteredByTheCallersOwnRoles() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}";

        List<String> customer = toolNames(post(controller(MODEL, "Customer"), request(), body));
        assertTrue(customer.stream().anyMatch(name -> name.startsWith("list_") && name.contains("Pigment")), customer.toString());
        assertTrue(customer.stream().noneMatch(name -> name.contains("Stock")), "StockEntry is Staff-only: " + customer);

        List<String> staff = toolNames(post(controller(MODEL, "staff"), request(), body));
        assertTrue(staff.stream().anyMatch(name -> name.startsWith("update_") && name.contains("Stock")),
                "role match is case-insensitive: " + staff);
        JsonNode first = post(controller(MODEL, "Staff"), request(), body).at("/result/tools/0");
        assertTrue(first.path("inputSchema").isObject());
        assertFalse(first.path("description").asText().isBlank());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aToolCallForwardsTheCallersOwnCredentialsAndReportsTheOutcome() throws Exception {
        AgentMcpController controller = controller(MODEL, "Staff");
        String tool = toolNames(post(controller, request(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).get(0);
        when(executor.execute(any(), any(), any()))
                .thenReturn(new AgentApiExecutor.Outcome(200, "{\"rows\":[]}"))
                .thenReturn(new AgentApiExecutor.Outcome(403, null));
        ArgumentCaptor<AgentApiExecutor.Credentials> credentials = ArgumentCaptor.forClass(AgentApiExecutor.Credentials.class);
        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);

        MockHttpServletRequest bearer = request();
        bearer.addHeader("Authorization", "Bearer user-jwt");
        JsonNode ok = post(controller, bearer, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":{\"limit\":5}}}");
        assertEquals("{\"rows\":[]}", ok.at("/result/content/0/text").asText());
        assertFalse(ok.at("/result/isError").asBoolean());

        MockHttpServletRequest apiKey = request();
        apiKey.addHeader("X-Api-Key", "key-123");
        JsonNode denied = post(controller, apiKey, "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\"}}");
        assertTrue(denied.at("/result/isError").asBoolean(), "a non-2xx loopback answer is a tool error, not a crash");
        assertEquals("", denied.at("/result/content/0/text").asText());

        verify(executor, Mockito.times(2)).execute(any(), arguments.capture(), credentials.capture());
        List<AgentApiExecutor.Credentials> sent = new ArrayList<>(credentials.getAllValues());
        assertEquals(new AgentApiExecutor.Credentials("Authorization", "Bearer user-jwt", "mcp"), sent.get(0));
        assertEquals(new AgentApiExecutor.Credentials("X-Api-Key", "key-123", "mcp"), sent.get(1));
        assertEquals(5, arguments.getAllValues().get(0).get("limit"));
        assertTrue(arguments.getAllValues().get(1).isEmpty());

        when(executor.execute(any(), any(), any())).thenReturn(new AgentApiExecutor.Outcome(200, "{}"));
        post(controller, request(), "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\"}}");
        verify(executor, Mockito.times(3)).execute(any(), any(), credentials.capture());
        AgentApiExecutor.Credentials none = credentials.getValue();
        assertNull(none.headerName(), "no credentials in means none forwarded -- never a server-side identity");
    }

    @Test
    void refusalsAndProtocolErrors() throws Exception {
        AgentMcpController controller = controller(MODEL, "Customer");

        assertEquals(405, controller.noStream().getStatusCode().value(), "no server-initiated SSE stream");

        JsonNode unknownTool = post(controller, request(),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"update_StockEntry\"}}");
        assertEquals(-32602, unknownTool.at("/error/code").asInt(), "a tool this caller is not offered is unknown");
        verify(executor, never()).execute(any(), any(), any());

        assertEquals(-32601, post(controller, request(), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\"}")
                .at("/error/code").asInt());
        assertEquals(-32700, post(controller, request(), "{not json").at("/error/code").asInt());
        assertEquals(-32600, post(controller, request(), "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]")
                .at("/error/code").asInt());
        assertEquals(202, controller.handle(request(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .getStatusCode().value(), "a notification (no id) is accepted with no body");

        MockHttpServletRequest local = request();
        local.addHeader("Origin", "http://localhost:3000");
        assertEquals(200, controller.handle(local, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}").getStatusCode().value());
        MockHttpServletRequest sameHost = request();
        sameHost.addHeader("Origin", "https://shop.example");
        assertEquals(200, controller.handle(sameHost, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}").getStatusCode().value());
        MockHttpServletRequest foreign = request();
        foreign.addHeader("Origin", "https://evil.example");
        assertEquals(403, controller.handle(foreign, "{}").getStatusCode().value(), "DNS-rebinding guard");
        MockHttpServletRequest garbage = request();
        garbage.addHeader("Origin", "http://bad host");
        assertEquals(403, controller.handle(garbage, "{}").getStatusCode().value());

        String disabled = MODEL.replace("\"mcp\": { \"enabled\": true }", "\"mcp\": { \"enabled\": false }");
        assertEquals(404, controller(disabled).handle(request(), "{}").getStatusCode().value(),
                "an app whose model does not enable MCP has no MCP endpoint at all");
    }

    private static List<String> toolNames(JsonNode response) {
        List<String> names = new ArrayList<>();
        response.at("/result/tools").forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    private static CompiledModel compile(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-mcp-controller-", ".json");
        Files.writeString(modelPath, json);
        return new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
    }
}
