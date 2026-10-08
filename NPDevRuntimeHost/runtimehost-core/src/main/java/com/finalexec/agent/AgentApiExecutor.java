package com.finalexec.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AGENT-1 (A4): executes one tool call by calling THIS app's own REST API over loopback, with the
 * caller's credentials. That is the whole security model of Track A: the generated CRUD controller
 * and the flow controller already run {@code checkCrudPermission} + concept access rules + audit for
 * whoever the credentials say -- so the agent can never do more than that user could do in the web UI.
 *
 * <p>{@code update} sends only the changed fields as a raw {@code Map<String,Object>} PUT body.
 * Confirmed (A4.1) this is safe: the generated service's
 * {@code update(UUID, Map<String,Object>)} overload runs {@code applyEntityFields(..., patchMode=true)},
 * which only writes fields present in the submitted map -- a true merge, not a full-record replace.
 * No GET-before-PUT is needed.
 *
 * <p>Two kinds of credentials:
 * <ul>
 *   <li>MCP: forward the caller's own {@code Authorization} / {@code X-Api-Key} header verbatim.</li>
 *   <li>Telegram/WhatsApp: a short-lived JWT minted for the LINKED user
 *       ({@code AgentLinkService#mintShortLivedToken}).</li>
 * </ul>
 */
public final class AgentApiExecutor {

    /** Header name + value to send, e.g. ("Authorization", "Bearer eyJ..."). Null header = no auth
     *  (auth.mode=none). */
    public record Credentials(String headerName, String headerValue, String channel) {
    }

    public record Outcome(int status, String body) {
        public boolean ok() {
            return status / 100 == 2;
        }

        /** What the LLM sees: always a JSON object, bodies truncated so one huge list cannot blow
         *  the context. */
        public String toToolResultJson(ObjectMapper mapper) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", status);
            String text = body == null ? "" : body;
            if (text.length() > MAX_RESULT_CHARS) {
                text = text.substring(0, MAX_RESULT_CHARS) + " ...(truncated; ask for a smaller page or a filter)";
            }
            try {
                out.put("body", mapper.readTree(text));
            } catch (Exception notJson) {
                out.put("body", text);
            }
            try {
                return mapper.writeValueAsString(out);
            } catch (Exception impossible) {
                return "{\"status\":" + status + "}";
            }
        }
    }

    static final int MAX_RESULT_CHARS = 12_000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final java.util.function.IntSupplier localPort;

    public AgentApiExecutor(ObjectMapper mapper, java.util.function.IntSupplier localPort) {
        this.mapper = mapper;
        this.localPort = localPort;
    }

    public Outcome execute(AgentToolCatalog.AgentTool tool, Map<String, Object> args, Credentials credentials) {
        try {
            HttpRequest request = switch (tool.kind()) {
                case CONCEPT -> conceptRequest(tool, args, credentials);
                case FLOW -> json(base() + "/api/v1/flows/" + enc(tool.flowName()) + "/execute", "POST", args,
                        credentials).build();
                case AGGREGATE -> aggregateRequest(tool, args, credentials);
            };
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body();
            if (tool.kind() == AgentToolCatalog.Kind.AGGREGATE && response.statusCode() / 100 == 2) {
                body = compactAggregate(body, "save".equals(tool.operation()));
            }
            return new Outcome(response.statusCode(), body);
        } catch (IllegalArgumentException badArgs) {
            return new Outcome(400, "{\"error\":\"" + badArgs.getMessage().replace("\"", "'") + "\"}");
        } catch (Exception failed) {
            return new Outcome(502, "{\"error\":\"the app's own API could not be reached: "
                    + failed.getClass().getSimpleName() + "\"}");
        }
    }

    /** P8: POST a JSON body to one of this app's own paths (e.g. an aggregate procedure invoke or a
     *  concept create) as {@code credentials}. {@code idempotent} adds a fresh X-Idempotency-Key. */
    public Outcome postJson(String path, Map<String, Object> body, Credentials credentials, boolean idempotent) {
        try {
            // A procedure may call a vision model; 30 s (the tool-call default) is too short for that.
            HttpRequest.Builder request = json(base() + path, "POST", body, credentials).timeout(Duration.ofSeconds(120));
            if (idempotent) {
                request.header("X-Idempotency-Key", UUID.randomUUID().toString());
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Outcome(response.statusCode(), response.body());
        } catch (Exception failed) {
            return new Outcome(502, "{\"error\":\"the app's own API could not be reached: "
                    + failed.getClass().getSimpleName() + "\"}");
        }
    }

    /** P8: multipart upload into {@code /api/files/{concept}/{field}} as {@code credentials} -- the
     *  file controller enforces the field's content types and size limit and the caller's tenant. */
    public Outcome uploadFile(String concept, String field, byte[] bytes, String contentType, String fileName,
            Credentials credentials) {
        String boundary = "npdev-" + UUID.randomUUID();
        String safeName = fileName == null || fileName.isBlank() ? "photo" : fileName.replace("\"", "");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + safeName
                + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + bytes.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(bytes, 0, body, head.length, bytes.length);
        System.arraycopy(tail, 0, body, head.length + bytes.length, tail.length);
        try {
            HttpRequest request = builder(base() + "/api/files/" + enc(concept) + "/" + enc(field), credentials)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Outcome(response.statusCode(), response.body());
        } catch (Exception failed) {
            return new Outcome(502, "{\"error\":\"the app's own API could not be reached: "
                    + failed.getClass().getSimpleName() + "\"}");
        }
    }

    private HttpRequest conceptRequest(AgentToolCatalog.AgentTool tool, Map<String, Object> args, Credentials creds) {
        String collection = base() + "/api/concepts/" + enc(tool.route());
        return switch (tool.operation()) {
            case "list" -> get(collection + listQuery(args), creds);
            case "get" -> get(collection + "/" + enc(requireId(args)), creds);
            case "create" -> json(collection, "POST", onlyExposed(tool, args, false), creds)
                    .header("X-Idempotency-Key", UUID.randomUUID().toString()).build();
            case "update" -> json(collection + "/" + enc(requireId(args)), "PUT", onlyExposed(tool, args, true), creds).build();
            case "delete" -> builder(collection + "/" + enc(requireId(args)), creds).DELETE().build();
            default -> throw new IllegalArgumentException("unknown operation " + tool.operation());
        };
    }

    /** P8: {@code get} loads the tree; {@code save} commits it whole (the workbench's Save path), so a
     *  root plus hundreds of children is one transaction. */
    private HttpRequest aggregateRequest(AgentToolCatalog.AgentTool tool, Map<String, Object> args, Credentials creds) {
        String path = base() + "/api/runtime/aggregate/" + enc(tool.route());
        return switch (tool.operation()) {
            case "get" -> get(path + "/" + enc(requireId(args)), creds);
            case "save" -> json(path, "POST", withCurrentRootFields(path, args, creds), creds)
                    .timeout(Duration.ofSeconds(120)).build();
            default -> throw new IllegalArgumentException("unknown operation " + tool.operation());
        };
    }

    /** Replacing an existing tree: root fields the agent left out keep their stored values (otherwise
     *  the commit would null them, and a defaulted field like a nextNumber code would be re-issued).
     *  Lists are never filled in -- a save replaces children wholesale. */
    private Map<String, Object> withCurrentRootFields(String path, Map<String, Object> args, Credentials creds) {
        Object id = args.get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            return args;
        }
        try {
            HttpResponse<String> current = http.send(get(path + "/" + enc(String.valueOf(id)), creds),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (current.statusCode() / 100 != 2 || !(mapper.readValue(current.body(), Object.class) instanceof Map<?, ?> stored)) {
                return args;
            }
            Map<String, Object> merged = new LinkedHashMap<>(args);
            for (Map.Entry<?, ?> entry : stored.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof List<?>) && !key.equals("aggregate") && !merged.containsKey(key)) {
                    merged.put(key, entry.getValue());
                }
            }
            return merged;
        } catch (Exception unreadable) {
            return args;
        }
    }

    /** A saved/loaded tree, shrunk for an LLM: child rows lose their id and the link back to their
     *  parent (a save never needs either); after a save each list becomes just its row count. */
    String compactAggregate(String body, boolean countsOnly) {
        try {
            Object tree = mapper.readValue(body, Object.class);
            if (!(tree instanceof Map<?, ?> root)) {
                return body;
            }
            return mapper.writeValueAsString(compactNode(root, countsOnly));
        } catch (Exception notJson) {
            return body;
        }
    }

    private static Map<String, Object> compactNode(Map<?, ?> node, boolean countsOnly) {
        Object parentId = node.get("id");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof List<?> rows)) {
                out.put(key, entry.getValue());
                continue;
            }
            if (countsOnly) {
                out.put(key, rows.size() + " rows saved");
                continue;
            }
            List<Object> compacted = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> child) {
                    Map<String, Object> slim = compactNode(child, false);
                    slim.remove("id");
                    slim.values().removeIf(value -> value != null && value.equals(parentId));
                    compacted.add(slim);
                } else {
                    compacted.add(row);
                }
            }
            out.put(key, compacted);
        }
        return out;
    }

    private Map<String, Object> onlyExposed(AgentToolCatalog.AgentTool tool, Map<String, Object> args, boolean dropId) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (dropId && entry.getKey().equals("id")) {
                continue;
            }
            if (tool.exposedFields().contains(entry.getKey())) {
                body.put(entry.getKey(), entry.getValue());
            }
        }
        return body;
    }

    /** {@code where=[{field,op,value}]} -> {@code where=field:op:value;...} (parseWhereClauses in
     *  the CRUD controller). */
    private String listQuery(Map<String, Object> args) {
        List<String> parts = new ArrayList<>();
        Object where = args.get("where");
        if (where instanceof List<?> clauses && !clauses.isEmpty()) {
            List<String> rendered = new ArrayList<>();
            for (Object clause : clauses) {
                if (clause instanceof Map<?, ?> c) {
                    rendered.add(c.get("field") + ":" + c.get("op") + ":" + c.get("value"));
                }
            }
            parts.add("where=" + enc(String.join(";", rendered)));
        }
        if (args.get("search") instanceof String search && !search.isBlank()) {
            parts.add("filter=" + enc(search));
        }
        int size = args.get("size") instanceof Number n ? Math.max(1, Math.min(100, n.intValue())) : 20;
        parts.add("size=" + size);
        if (args.get("page") instanceof Number p) {
            parts.add("page=" + Math.max(0, p.intValue()));
        }
        return "?" + String.join("&", parts);
    }

    private static String requireId(Map<String, Object> args) {
        Object id = args.get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        return String.valueOf(id);
    }

    private HttpRequest get(String url, Credentials creds) {
        return builder(url, creds).GET().build();
    }

    private HttpRequest.Builder json(String url, String method, Map<String, Object> body, Credentials creds) {
        try {
            return builder(url, creds).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body == null ? Map.of() : body)));
        } catch (Exception e) {
            throw new IllegalArgumentException("arguments are not valid JSON");
        }
    }

    private HttpRequest.Builder builder(String url, Credentials creds) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("X-Tag-channel", creds.channel());
        if (creds.headerName() != null) {
            b.header(creds.headerName(), creds.headerValue());
        }
        return b;
    }

    private String base() {
        return "http://127.0.0.1:" + localPort.getAsInt();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
