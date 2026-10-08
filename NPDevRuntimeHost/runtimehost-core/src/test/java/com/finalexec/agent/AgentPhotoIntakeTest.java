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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P8 (G5): {@code agentAccess.photoIntake} -- the draft precedence (defaults < procedure answer <
 * caption < image), and the service's REST round trips against a fake app API: upload, procedure
 * invoke over the aggregate, confirm-before-create, cancel, and the "send a caption" refusal.
 */
class AgentPhotoIntakeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> HANDLE = Map.of("storeId", "s", "key", "dev/k", "contentType", "image/jpeg");

    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<JsonNode> jsonBodies = new CopyOnWriteArrayList<>();
    private final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
    private final List<byte[]> uploads = new CopyOnWriteArrayList<>();
    private HttpServer app;
    private volatile String invokeAnswer;

    static CompiledModel model(String intake) throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "demo.photo", "version": "1.0",
              "concepts": [
                { "name": "Cap", "ui": { "label": "Beer cap" }, "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "label", "type": "string", "required": true },
                  { "name": "image", "type": "file", "file": { "contentTypes": ["image/jpeg"], "maxSizeBytes": 204800 } },
                  { "name": "dominantColor", "type": "string", "required": true },
                  { "name": "status", "type": "string", "required": true },
                  { "name": "submittedBy", "type": "string" } ] }
              ],
              "procedures": [ { "name": "Identify", "steps": [ { "name": "done", "type": "return", "value": "$input" } ] } ],
              "aggregates": [ { "name": "CapAggregate", "root": "Cap" } ],
              "agentAccess": {
                "channels": { "telegram": { "enabled": true } },
                "expose": [ { "concept": "Cap", "operations": ["list"] } ]
                %s
              }
            }
            """.formatted(intake == null ? "" : ", \"photoIntake\": " + intake);
        return new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(json)));
    }

    private static final String INTAKE = """
        { "concept": "Cap", "imageField": "image", "captionField": "label", "procedure": "Identify",
          "defaults": { "status": "SUBMITTED", "submittedBy": "$user.username", "dominantColor": "#000000" } }""";

    @BeforeEach
    void startFakeApp() throws Exception {
        app = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        app.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            byte[] body = exchange.getRequestBody().readAllBytes();
            String answer;
            if (path.startsWith("/api/files/")) {
                uploads.add(body);
                answer = MAPPER.writeValueAsString(HANDLE);
            } else if (path.contains("/invoke/")) {
                jsonBodies.add(MAPPER.readTree(body));
                answer = invokeAnswer;
            } else {
                jsonBodies.add(MAPPER.readTree(body));
                idempotencyKeys.add(exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));
                answer = "{\"id\":\"new\"}";
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer.startsWith("{\"message\"") ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        app.start();
    }

    @AfterEach
    void stopFakeApp() {
        app.stop(0);
    }

    private AgentConversationService service(CompiledModel model) {
        AgentLinkService links = mock(AgentLinkService.class);
        when(links.mintShortLivedToken(any(), any())).thenReturn(Optional.of("tok"));
        AgentApiExecutor executor = new AgentApiExecutor(MAPPER, () -> app.getAddress().getPort());
        return new AgentConversationService(() -> model, null, executor, links, MAPPER, "gemini", null);
    }

    private static AgentConversationService.Speaker tavo() {
        return new AgentConversationService.Speaker("telegram", "42", "dev", "tavo", Set.of("MEMBER"));
    }

    @Test
    void draftPrecedenceIsDefaultsThenProcedureThenCaptionThenImage() throws Exception {
        AgentPhotoIntake.Target target = AgentPhotoIntake.target(model(INTAKE)).orElseThrow();
        assertEquals("CapAggregate", target.aggregate());
        Map<String, Object> state = Map.of(
                "label", "AI label", "id", "ignored", "notAField", "x",
                "suggestion", Map.of("dominantColor", "#C0C0C0", "image", "not-a-handle"));

        Map<String, Object> draft = AgentPhotoIntake.draft(target, "tavo", HANDLE, " Lech Pils ", state);

        assertEquals("Lech Pils", draft.get("label"), "the caption beats the AI's label");
        assertEquals("#C0C0C0", draft.get("dominantColor"), "the AI's nested answer beats the default");
        assertEquals("SUBMITTED", draft.get("status"));
        assertEquals("tavo", draft.get("submittedBy"), "$user.username is the linked user");
        assertEquals(HANDLE, draft.get("image"), "the uploaded handle always wins");
        assertFalse(draft.containsKey("id"));
        assertFalse(draft.containsKey("notAField"));
        assertEquals(List.of(), AgentPhotoIntake.missingRequired(target, draft));
    }

    @Test
    void photoBecomesARowOnlyAfterConfirm() throws Exception {
        invokeAnswer = "{\"label\":\"Telegram probe\",\"suggestion\":{\"dominantColor\":\"#123456\"}}";
        AgentConversationService service = service(model(INTAKE));

        AgentConversationService.Reply reply = service.handlePhoto(tavo(), new byte[] {1, 2, 3}, "image/jpeg", "cap.jpg", "Lech");

        assertNotNull(reply.confirmId(), reply.text());
        assertTrue(reply.text().contains("New Beer cap from your photo"), reply.text());
        assertTrue(reply.text().contains("dominantColor: #123456"), reply.text());
        assertEquals(List.of("/api/files/Cap/image", "/api/runtime/aggregate/CapAggregate/invoke/Identify"), paths,
                "nothing is created before Confirm");
        assertEquals("Lech", jsonBodies.get(0).path("label").asText(), "the procedure sees the caption");
        assertEquals("dev/k", jsonBodies.get(0).path("image").path("key").asText(), "and the uploaded handle");
        assertTrue(new String(uploads.get(0), StandardCharsets.ISO_8859_1).contains("filename=\"cap.jpg\""));

        AgentConversationService.Reply saved = service.handleConfirmation(tavo(), reply.confirmId(), true);

        assertTrue(saved.text().startsWith("Saved"), saved.text());
        assertTrue(paths.get(2).startsWith("/api/concepts/"), paths.toString());
        JsonNode created = jsonBodies.get(1);
        assertEquals("Lech", created.path("label").asText());
        assertEquals("#123456", created.path("dominantColor").asText());
        assertEquals("SUBMITTED", created.path("status").asText());
        assertEquals("tavo", created.path("submittedBy").asText());
        assertNotNull(idempotencyKeys.get(0));
        assertTrue(service.handleConfirmation(tavo(), reply.confirmId(), true).text().contains("expired"),
                "a confirm button works once");
    }

    @Test
    void cancelCreatesNothingAndAFailedProcedureStillOffersTheDefaults() throws Exception {
        invokeAnswer = "{\"message\":\"Procedure Identify failed: EXTERNAL_AI_INVALID_OUTPUT\"}";
        AgentConversationService service = service(model(INTAKE));

        AgentConversationService.Reply reply = service.handlePhoto(tavo(), new byte[] {1}, "image/jpeg", null, "Lech");

        assertNotNull(reply.confirmId(), reply.text());
        assertTrue(reply.text().contains("Identify did not answer: Procedure Identify failed"), reply.text());
        assertEquals("Discarded.", service.handleConfirmation(tavo(), reply.confirmId(), false).text());
        assertEquals(2, paths.size(), "cancel never reaches the create endpoint");
    }

    @Test
    void aPhotoWithoutTheRequiredCaptionAsksForOne() throws Exception {
        invokeAnswer = "{}";
        AgentConversationService.Reply reply = service(model(INTAKE))
                .handlePhoto(tavo(), new byte[] {1}, "image/jpeg", null, "");

        assertNull(reply.confirmId());
        assertTrue(reply.text().contains("missing [label]"), reply.text());
        assertTrue(reply.text().contains("Send the photo again with a caption"), reply.text());
    }

    @Test
    void withoutPhotoIntakeAPhotoGetsATextHintAndNoUpload() throws Exception {
        AgentConversationService service = service(model(null));
        assertNull(service.photoMaxBytes());
        AgentConversationService.Reply reply = service.handlePhoto(tavo(), new byte[] {1}, "image/jpeg", null, "x");
        assertTrue(reply.text().contains("can't do anything with photos"), reply.text());
        assertTrue(paths.isEmpty());
        assertEquals(204800L, service(model(INTAKE)).photoMaxBytes());
    }
}
