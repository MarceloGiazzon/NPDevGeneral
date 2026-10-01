package com.finalexec.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.agent.AgentApiExecutor;
import com.finalexec.agent.AgentToolCatalog;
import com.finalexec.config.ModelHolder;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AGENT-1 (A5): a standard MCP endpoint (Streamable HTTP, protocol version {@code 2025-06-18}) at
 * {@code /api/mcp}, so Claude Code / Claude Desktop / any MCP client can use this app's data as
 * tools -- as the caller's own user. Stateless (no {@code Mcp-Session-Id}), one JSON response per
 * request (no server-initiated SSE stream). Auth is the app's normal
 * {@code Authorization: Bearer <jwt>} / {@code X-Api-Key}, enforced the same way every other
 * {@code /api/*} route already is; this controller forwards those same credentials to the loopback
 * call, so an MCP caller can never do more than that user could do in the web UI. See
 * {@code helpers/agent/wire-formats.md} section 2 for the exact wire shapes this implements.
 */
@RestController
@RequestMapping("/api/mcp")
public class AgentMcpController {

    private static final Set<String> SUPPORTED_VERSIONS = Set.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final String LATEST = "2025-06-18";

    private final RuntimeContextService runtimeContextService;
    private final ModelHolder modelHolder;
    private final AgentApiExecutor executor;
    private final ObjectMapper mapper;

    public AgentMcpController(RuntimeContextService runtimeContextService, ModelHolder modelHolder,
            AgentApiExecutor executor, ObjectMapper mapper) {
        this.runtimeContextService = runtimeContextService;
        this.modelHolder = modelHolder;
        this.executor = executor;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<Void> noStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handle(HttpServletRequest request, @RequestBody String rawBody) {
        if (!mcpEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if (!originAllowed(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        ExecutionContext context = runtimeContextService.currentContext(request);

        JsonNode message;
        try {
            message = mapper.readTree(rawBody);
        } catch (Exception parse) {
            return ResponseEntity.ok(error(null, -32700, "Parse error"));
        }
        if (message.isArray()) {
            return ResponseEntity.ok(error(null, -32600, "Batching is not supported"));
        }
        if (!message.has("id") || message.get("id").isNull()) {
            return ResponseEntity.accepted().build();
        }
        JsonNode id = message.get("id");
        String method = message.path("method").asText("");
        JsonNode params = message.path("params");
        return ResponseEntity.ok(switch (method) {
            case "initialize" -> result(id, initialize(params));
            case "ping" -> result(id, Map.of());
            case "tools/list" -> result(id, Map.of("tools", toolList(context)));
            case "tools/call" -> callTool(id, params, context, request);
            default -> error(id, -32601, "Method not found: " + method);
        });
    }

    private Map<String, Object> initialize(JsonNode params) {
        String asked = params.path("protocolVersion").asText(LATEST);
        var model = modelHolder.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", SUPPORTED_VERSIONS.contains(asked) ? asked : LATEST);
        out.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        out.put("serverInfo", Map.of("name", model.getNamespace(), "version", String.valueOf(model.getVersion())));
        var access = model.getAgentAccess();
        if (access != null && access.getAssistant() != null && access.getAssistant().getInstructions() != null) {
            out.put("instructions", access.getAssistant().getInstructions());
        }
        return out;
    }

    private List<Map<String, Object>> toolList(ExecutionContext context) {
        return AgentToolCatalog.toolsFor(modelHolder.get(), context.roles()).stream()
                .map(tool -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", tool.name());
                    t.put("title", tool.title());
                    t.put("description", tool.description());
                    t.put("inputSchema", tool.inputSchema());
                    return t;
                }).toList();
    }

    private Map<String, Object> callTool(
            JsonNode id, JsonNode params, ExecutionContext context, HttpServletRequest request) {
        String name = params.path("name").asText("");
        List<AgentToolCatalog.AgentTool> tools = AgentToolCatalog.toolsFor(modelHolder.get(), context.roles());
        var tool = AgentToolCatalog.find(tools, name);
        if (tool.isEmpty()) {
            return error(id, -32602, "Unknown tool: " + name);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> args = params.has("arguments")
                ? mapper.convertValue(params.get("arguments"), Map.class) : Map.of();
        AgentApiExecutor.Outcome outcome = executor.execute(tool.get(), args, forwardedCredentials(request));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", List.of(Map.of("type", "text", "text", outcome.body() == null ? "" : outcome.body())));
        body.put("isError", !outcome.ok());
        return result(id, body);
    }

    /** The MCP client's own credentials, forwarded to the loopback call -- the agent acts exactly
     *  as that user. */
    private static AgentApiExecutor.Credentials forwardedCredentials(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && !authorization.isBlank()) {
            return new AgentApiExecutor.Credentials("Authorization", authorization, "mcp");
        }
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return new AgentApiExecutor.Credentials("X-Api-Key", apiKey, "mcp");
        }
        return new AgentApiExecutor.Credentials(null, null, "mcp");
    }

    private boolean mcpEnabled() {
        var access = modelHolder.get().getAgentAccess();
        return access != null && access.getChannels() != null && access.getChannels().getMcp().isEnabled();
    }

    /** MCP spec: validate Origin to stop DNS-rebinding attacks from a browser page. */
    private static boolean originAllowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(origin).getHost();
            return host != null && (host.equalsIgnoreCase(request.getServerName())
                    || host.equals("localhost") || host.equals("127.0.0.1"));
        } catch (IllegalArgumentException bad) {
            return false;
        }
    }

    private static Map<String, Object> result(JsonNode id, Object result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("result", result);
        return out;
    }

    private static Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("error", Map.of("code", code, "message", message));
        return out;
    }
}
