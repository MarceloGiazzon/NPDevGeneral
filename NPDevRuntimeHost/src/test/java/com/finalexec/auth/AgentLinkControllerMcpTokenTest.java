package com.finalexec.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P8: {@code POST /api/agent/mcp-token} hands the caller everything needed to plug the app into an
 * MCP client -- the token, the endpoint, a {@code claude mcp add} line, and a Claude Desktop config
 * that bridges the HTTP endpoint through {@code mcp-remote} with the header carried in an env var.
 */
class AgentLinkControllerMcpTokenTest {

    private static final String MODEL = """
            {
              "namespace": "pigment.shop",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "concepts": [
                { "name": "Pigment", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);

    @SuppressWarnings("unchecked")
    private AgentLinkController controller(String privateKeyPath) throws Exception {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("acme", "ann").withRoles(Set.of("Staff")));
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(mapper.readTree(MODEL)));
        return new AgentLinkController(runtimeContextService, new ModelHolder(model), null, null, null,
                Mockito.mock(ObjectProvider.class), mapper, privateKeyPath, "npdev-test", "npdev-test",
                7, "", "");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/mcp-token");
        request.setServerName("shop.local");
        request.setServerPort(8080);
        return request;
    }

    @Test
    void mintsATokenWithEveryClientRecipe() throws Exception {
        ResponseEntity<Map<String, Object>> response =
                controller("classpath:npdev/security/test-jwt-private.pem").mintMcpToken(request());

        Map<String, Object> body = response.getBody();
        String token = (String) body.get("token");
        assertEquals(3, token.split("\\.").length, "a compact JWT");
        assertEquals("http://shop.local:8080/api/mcp", body.get("mcpUrl"));
        assertTrue(body.get("expiresAt").toString().endsWith("Z"));
        assertEquals("claude mcp add --transport http pigment-shop http://shop.local:8080/api/mcp"
                + " --header \"Authorization: Bearer " + token + "\"", body.get("claudeCodeCommand"));

        JsonNode server = mapper.readTree((String) body.get("claudeDesktopConfig")).path("mcpServers").path("pigment-shop");
        assertEquals("npx", server.path("command").asText());
        assertEquals("mcp-remote", server.path("args").get(1).asText());
        assertEquals("Authorization:${NPDEV_MCP_AUTH}", server.path("args").get(4).asText(),
                "no space inside an args entry -- Windows mangles it");
        assertEquals("Bearer " + token, server.path("env").path("NPDEV_MCP_AUTH").asText());
    }

    @Test
    void withoutASigningKeyTheEndpointIsUnavailableNotBroken() throws Exception {
        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> controller("").mintMcpToken(request()));
        assertEquals(503, refused.getStatusCode().value());
        assertFalse(refused.getReason().isBlank());
    }
}
