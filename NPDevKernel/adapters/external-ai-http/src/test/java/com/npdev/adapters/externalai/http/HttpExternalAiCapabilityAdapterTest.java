package com.npdev.adapters.externalai.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ports.ExternalAiChatTurn;
import com.npdev.kernel.ports.ExternalAiEgressDeniedException;
import com.npdev.kernel.ports.ExternalAiGenerationRequest;
import com.npdev.kernel.ports.ExternalAiGenerationResult;
import com.npdev.kernel.ports.ExternalAiPackSubmission;
import com.npdev.kernel.ports.ExternalAiRunResult;
import com.npdev.kernel.ports.ExternalAiStructuredRequest;
import com.npdev.kernel.ports.ExternalAiStructuredResult;
import com.npdev.kernel.ports.ExternalAiToolCall;
import com.npdev.kernel.ports.ExternalAiToolChatRequest;
import com.npdev.kernel.ports.ExternalAiToolChatResult;
import com.npdev.kernel.ports.ExternalAiToolSpec;
import com.npdev.kernel.ports.ExternalAiVendorSummary;
import com.npdev.kernel.ports.ExternalAiVerdictRecord;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the adapter's request/response wiring against a loopback-only stub server -- never a real
 * vendor. No API key, network egress, or vendor account is required to run this suite (ADR-0009:
 * D3/D4/D5 are still pending; this test exercises transport plumbing only).
 */
class HttpExternalAiCapabilityAdapterTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void submitPackDeniesWhenNoVendorIsConfigured() {
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(List.of());
        ExternalAiPackSubmission submission = new ExternalAiPackSubmission(
                "M1-SEC-GENCODE", "nvidia", "c".repeat(64), "{}");

        ExternalAiEgressDeniedException thrown = assertThrows(
                ExternalAiEgressDeniedException.class, () -> adapter.submitPack(submission));
        assertEquals("EGRESS_DENIED_NO_VENDOR", thrown.code());
    }

    @Test
    void submitPackDeniesWhenApiKeyEnvVarIsUnset() {
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(ExternalAiVendorProfile.nvidiaBuild("NPDEV_TEST_MISSING_KEY", "meta/llama-3.1-405b-instruct")),
                HttpClient.newHttpClient(),
                env -> null);
        ExternalAiPackSubmission submission = new ExternalAiPackSubmission(
                "M1-SEC-GENCODE", "nvidia", "c".repeat(64), "{}");

        ExternalAiEgressDeniedException thrown = assertThrows(
                ExternalAiEgressDeniedException.class, () -> adapter.submitPack(submission));
        assertEquals("EGRESS_DENIED_NO_API_KEY", thrown.code());
    }

    @Test
    void submitPackRoundTripsAnOpenAiCompatibleShapedResponse() throws IOException {
        String verdictJson = "{\"recordKind\":\"external-ai-verdict\",\"noRepoAccess\":true,"
                + "\"autoApplied\":false,\"findings\":[]}";
        server = startStubServer("/v1/chat/completions", exchange -> {
            String responseBody = "{\"choices\":[{\"message\":{\"content\":" + jsonQuote(verdictJson) + "}}]}";
            writeJson(exchange, responseBody);
        });
        ExternalAiVendorProfile profile = new ExternalAiVendorProfile(
                "nvidia", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "meta/llama-3.1-405b-instruct", "NPDEV_TEST_NVIDIA_KEY", ExternalAiRequestFormat.OPENAI_CHAT);
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(profile), HttpClient.newHttpClient(), env -> "test-key");
        ExternalAiPackSubmission submission = new ExternalAiPackSubmission(
                "M1-SEC-GENCODE", "nvidia", "d".repeat(64), "{\"missionId\":\"M1-SEC-GENCODE\"}");

        ExternalAiRunResult result = adapter.submitPack(submission);

        assertEquals("RUN", result.runStatus());
        ExternalAiVerdictRecord record = adapter.verdictFor("M1-SEC-GENCODE").orElseThrow();
        assertEquals("external-ai-verdict", ExternalAiVerdictRecord.RECORD_KIND);
        assertEquals(verdictJson, record.verdictJson());
        assertEquals("meta/llama-3.1-405b-instruct", record.model());
    }

    @Test
    void submitPackRoundTripsAGeminiShapedResponse() throws IOException {
        String verdictJson = "{\"recordKind\":\"external-ai-verdict\",\"noRepoAccess\":true,"
                + "\"autoApplied\":false,\"findings\":[]}";
        server = startStubServer("/models/gemini-3-pro:generateContent", exchange -> {
            String responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                    + jsonQuote(verdictJson) + "}]}}]}";
            writeJson(exchange, responseBody);
        });
        ExternalAiVendorProfile profile = new ExternalAiVendorProfile(
                "gemini", "http://127.0.0.1:" + server.getAddress().getPort(),
                "gemini-3-pro", "NPDEV_TEST_GEMINI_KEY", ExternalAiRequestFormat.GEMINI_GENERATE_CONTENT);
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(profile), HttpClient.newHttpClient(), env -> "test-key");
        ExternalAiPackSubmission submission = new ExternalAiPackSubmission(
                "M3-SEC-TENANT", "gemini", "e".repeat(64), "{\"missionId\":\"M3-SEC-TENANT\"}");

        ExternalAiRunResult result = adapter.submitPack(submission);

        assertEquals("RUN", result.runStatus());
        assertEquals(verdictJson, adapter.verdictFor("M3-SEC-TENANT").orElseThrow().verdictJson());
    }

    @Test
    void submitPackRejectsAVendorResponseThatFailsTheHonestyChecks() throws IOException {
        String dishonestVerdict = "{\"recordKind\":\"independent-human-review\",\"noRepoAccess\":true,"
                + "\"autoApplied\":false}";
        server = startStubServer("/v1/chat/completions", exchange -> {
            String responseBody = "{\"choices\":[{\"message\":{\"content\":" + jsonQuote(dishonestVerdict) + "}}]}";
            writeJson(exchange, responseBody);
        });
        ExternalAiVendorProfile profile = new ExternalAiVendorProfile(
                "nvidia", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "meta/llama-3.1-405b-instruct", "NPDEV_TEST_NVIDIA_KEY", ExternalAiRequestFormat.OPENAI_CHAT);
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(profile), HttpClient.newHttpClient(), env -> "test-key");
        ExternalAiPackSubmission submission = new ExternalAiPackSubmission(
                "M1-SEC-GENCODE", "nvidia", "f".repeat(64), "{}");

        assertThrows(IllegalArgumentException.class, () -> adapter.submitPack(submission));
    }

    @Test
    void generateTextDeniesWhenTheVendorIsNotConfigured() {
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(List.of());

        ExternalAiEgressDeniedException thrown = assertThrows(
                ExternalAiEgressDeniedException.class,
                () -> adapter.generateText(new ExternalAiGenerationRequest("anthropic", null, null, "hello")));
        assertEquals("EGRESS_DENIED_NO_VENDOR", thrown.code());
    }

    @Test
    void generateTextDeniesWhenTheApiKeyEnvVarIsUnset() {
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(ExternalAiVendorProfile.anthropic("NPDEV_TEST_MISSING_KEY", "claude-opus-5")),
                HttpClient.newHttpClient(),
                env -> null);

        ExternalAiEgressDeniedException thrown = assertThrows(
                ExternalAiEgressDeniedException.class,
                () -> adapter.generateText(new ExternalAiGenerationRequest("anthropic", null, null, "hello")));
        assertEquals("EGRESS_DENIED_NO_API_KEY", thrown.code());
        // The DENIAL may name the env var -- that is a name, not a value, and an operator who cannot
        // see it has no way to tell "unconfigured" from "misconfigured".
        assertTrue(thrown.getMessage().contains("NPDEV_TEST_MISSING_KEY"));
    }

    @Test
    void generateTextSendsTheAnthropicShapeAndReadsBackTheFirstTextBlock() throws IOException {
        AtomicReference<String> seenBody = new AtomicReference<>();
        AtomicReference<String> seenApiKeyHeader = new AtomicReference<>();
        AtomicReference<String> seenVersionHeader = new AtomicReference<>();
        AtomicReference<String> seenAuthorizationHeader = new AtomicReference<>();
        server = startStubServer("/v1/messages", exchange -> {
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seenApiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            seenVersionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            seenAuthorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            // A THINKING block first, then the text -- the default shape on current models, and the
            // one that makes a naive content[0].text read return nothing.
            writeJson(exchange, "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"},"
                    + "{\"type\":\"text\",\"text\":\"add a priority field\"}]}");
        });
        ExternalAiVendorProfile profile = new ExternalAiVendorProfile(
                "anthropic", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages",
                "claude-opus-5", "NPDEV_TEST_ANTHROPIC_KEY", ExternalAiRequestFormat.ANTHROPIC_MESSAGES);
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(profile), HttpClient.newHttpClient(), env -> "test-key");

        ExternalAiGenerationResult result = adapter.generateText(
                new ExternalAiGenerationRequest("anthropic", "claude-sonnet-5", "high", "what should I change?"));

        assertEquals("add a priority field", result.text());
        assertEquals("test-key", seenApiKeyHeader.get());
        assertEquals("2023-06-01", seenVersionHeader.get());
        assertNull(seenAuthorizationHeader.get(), "Anthropic authenticates with x-api-key, never a bearer token");
        // The caller's model wins over the profile default, effort rides in output_config, and
        // max_tokens is present because the Messages API rejects a request without it.
        assertTrue(seenBody.get().contains("\"model\":\"claude-sonnet-5\""), seenBody.get());
        assertTrue(seenBody.get().contains("\"output_config\":{\"effort\":\"high\"}"), seenBody.get());
        assertTrue(seenBody.get().contains("\"max_tokens\":"), seenBody.get());
    }

    @Test
    void generateTextSendsTheOpenAiShapeWithoutAnEffortParameter() throws IOException {
        AtomicReference<String> seenBody = new AtomicReference<>();
        AtomicReference<String> seenAuthorizationHeader = new AtomicReference<>();
        server = startStubServer("/v1/chat/completions", exchange -> {
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seenAuthorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            writeJson(exchange, "{\"choices\":[{\"message\":{\"content\":\"use a lookup table\"}}]}");
        });
        ExternalAiVendorProfile profile = new ExternalAiVendorProfile(
                "openai", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "gpt-4o-mini", "NPDEV_TEST_OPENAI_KEY", ExternalAiRequestFormat.OPENAI_CHAT);
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(profile), HttpClient.newHttpClient(), env -> "test-key");

        ExternalAiGenerationResult result = adapter.generateText(
                new ExternalAiGenerationRequest("openai", null, "high", "what should I change?"));

        assertEquals("use a lookup table", result.text());
        assertEquals("gpt-4o-mini", result.model(), "an omitted model falls back to the profile default");
        assertEquals("Bearer test-key", seenAuthorizationHeader.get());
        // `high` was accepted by the request record and then DROPPED, because reasoning_effort is a
        // 400 on the chat models this shape defaults to. Silently ignoring it beats failing the send.
        assertFalse(seenBody.get().contains("effort"), seenBody.get());
    }

    @Test
    void configuredVendorsReportsKeyPresenceAndEffortSupportWithoutLeakingTheKey() {
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(
                        ExternalAiVendorProfile.anthropic("NPDEV_TEST_ANTHROPIC_KEY", "claude-opus-5"),
                        ExternalAiVendorProfile.openai("NPDEV_TEST_OPENAI_KEY", "gpt-4o-mini")),
                HttpClient.newHttpClient(),
                env -> "NPDEV_TEST_ANTHROPIC_KEY".equals(env) ? "sk-ant-secret-value" : "   ");

        List<ExternalAiVendorSummary> vendors = adapter.configuredVendors();

        assertEquals(2, vendors.size());
        // Order is the CONFIGURED order, not the vendor map's. The map is a Map.copyOf, whose
        // iteration order is randomized per JVM -- reading vendors off it would re-shuffle which
        // provider a page pre-selects on every restart.
        ExternalAiVendorSummary anthropic = vendors.get(0);
        assertEquals("anthropic", anthropic.vendorId());
        assertTrue(anthropic.keyPresent());
        assertTrue(anthropic.effortSupported());
        assertEquals("NPDEV_TEST_ANTHROPIC_KEY", anthropic.keyEnvVarName());
        assertFalse(anthropic.toString().contains("sk-ant-secret-value"),
                "the summary carries the env var NAME; the value must never reach it");

        ExternalAiVendorSummary openai = vendors.get(1);
        // A whitespace-only env var is NOT a key. Treating it as present would produce a send that
        // fails at the vendor instead of a deny that names the problem.
        assertFalse(openai.keyPresent());
        assertFalse(openai.effortSupported());
    }

    /**
     * R8d (RUN-4) live proof: a request-level deadline that actually fires, and a bounded retry
     * that actually happens -- against a REAL local server that accepts the TCP connection and then
     * never answers (never a mocked timeout). No external network dependency: the "unreachable
     * endpoint" is a loopback {@link ServerSocket} this test owns.
     */
    @Test
    void requestTimesOutAndRetriesTheConfiguredNumberOfTimesAgainstAHangingServer() throws Exception {
        try (ServerSocket hangingServer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger acceptedConnections = new AtomicInteger();
            Thread acceptor = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Socket socket = hangingServer.accept();
                        acceptedConnections.incrementAndGet();
                        // Deliberately never write a response -- the client must hit its own
                        // requestTimeout, not a server-side close.
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "hanging-server-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            ExternalAiVendorProfile profile = new ExternalAiVendorProfile(
                    "hangy", "http://127.0.0.1:" + hangingServer.getLocalPort() + "/v1/chat/completions",
                    "some-model", "NPDEV_TEST_HANGY_KEY", ExternalAiRequestFormat.OPENAI_CHAT);
            // requestTimeout=300ms, maxRetries=1 (2 total attempts), retryBackoff=50ms -- short enough
            // that the whole test resolves in well under a second if the deadline is real, and would
            // hang the JUnit run (previously: forever) if it were not.
            HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                    List.of(profile),
                    HttpClient.newHttpClient(),
                    env -> "test-key",
                    Duration.ofMillis(300),
                    1,
                    Duration.ofMillis(50));

            long startedAt = System.nanoTime();
            UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                    () -> adapter.generateText(new ExternalAiGenerationRequest("hangy", null, null, "hello")));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            acceptor.interrupt();
            hangingServer.close();

            assertTrue(elapsedMs < 5000,
                    "expected the adapter's own requestTimeout to bound the call well under 5s, took " + elapsedMs + "ms");
            assertEquals(2, acceptedConnections.get(),
                    "expected exactly maxRetries+1 = 2 attempts (2 real TCP connections) against the hanging server");
            assertTrue(thrown.getMessage().contains("attempt 2/2"), thrown.getMessage());
        }
    }

    @Test
    void chatWithToolsDeniesWhenNoVendorIsConfigured() {
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(List.of());

        assertThrows(ExternalAiEgressDeniedException.class, () -> adapter.chatWithTools(
                new ExternalAiToolChatRequest("gemini", null, null, List.of(), List.of(ExternalAiChatTurn.user("hi")))));
    }

    @Test
    void chatWithToolsSendsTheGeminiShapeAndParsesFunctionCalls() throws IOException {
        AtomicReference<String> seenBody = new AtomicReference<>();
        server = startStubServer("/models/gemini-3-pro:generateContent", exchange -> {
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
                    + "{\"text\":\"checking stock\"},"
                    + "{\"functionCall\":{\"name\":\"list_StockEntry\",\"args\":{\"limit\":5}}}]}}]}");
        });
        ExternalAiVendorProfile profile = new ExternalAiVendorProfile(
                "gemini", "http://127.0.0.1:" + server.getAddress().getPort(),
                "gemini-3-pro", "NPDEV_TEST_GEMINI_KEY", ExternalAiRequestFormat.GEMINI_GENERATE_CONTENT);
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(profile), HttpClient.newHttpClient(), env -> "test-key");

        ExternalAiToolChatResult result = adapter.chatWithTools(new ExternalAiToolChatRequest(
                "gemini", null, "be brief", List.of(stockTool()), List.of(
                        ExternalAiChatTurn.user("how much red?"),
                        assistantRaw("{\"role\":\"model\",\"parts\":[{\"thoughtSignature\":\"sig\"}]}"),
                        ExternalAiChatTurn.toolResult("gemini-0", "list_StockEntry", "{\"rows\":[]}"),
                        ExternalAiChatTurn.toolResult("gemini-1", "get_Pigment", null),
                        assistantText("none left"),
                        ExternalAiChatTurn.user(null))));

        JsonNode body = new ObjectMapper().readTree(seenBody.get());
        assertEquals("be brief", body.at("/systemInstruction/parts/0/text").asText());
        JsonNode contents = body.path("contents");
        assertEquals(5, contents.size(), "two consecutive tool turns merge into ONE user content: " + contents);
        assertEquals("sig", contents.at("/1/parts/0/thoughtSignature").asText(), "vendorRaw replays verbatim");
        assertEquals(2, contents.at("/2/parts").size());
        assertEquals("list_StockEntry", contents.at("/2/parts/0/functionResponse/name").asText());
        assertTrue(contents.at("/2/parts/1/functionResponse/response").isObject(), "a null result becomes {}");
        assertEquals("model", contents.at("/3/role").asText());
        assertEquals("none left", contents.at("/3/parts/0/text").asText());
        assertEquals("", contents.at("/4/parts/0/text").asText());
        assertEquals("list_StockEntry", body.at("/tools/0/functionDeclarations/0/name").asText());
        assertEquals("AUTO", body.at("/toolConfig/functionCallingConfig/mode").asText());

        assertEquals("gemini-3-pro", result.model(), "an omitted model falls back to the profile default");
        assertTrue(result.wantsTools());
        ExternalAiToolCall call = result.assistantTurn().toolCalls().get(0);
        assertEquals("gemini-0", call.id());
        assertEquals("list_StockEntry", call.name());
        assertEquals(5, ((Number) call.arguments().get("limit")).intValue());
        assertEquals("checking stock", result.assistantTurn().text());
        assertTrue(result.assistantTurn().vendorRaw().contains("functionCall"));
    }

    @Test
    void chatWithToolsSendsTheOpenAiShapeAndRejectsMalformedArguments() throws IOException {
        AtomicReference<String> seenBody = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        server = startStubServer("/v1/chat/completions", exchange -> {
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String arguments = calls.incrementAndGet() == 1 ? "{\\\"id\\\":\\\"p1\\\"}" : "not json";
            writeJson(exchange, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                    + "\"tool_calls\":[{\"id\":\"call_9\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"get_Pigment\",\"arguments\":\"" + arguments + "\"}}]}}]}");
        });
        ExternalAiVendorProfile profile = new ExternalAiVendorProfile(
                "openai", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "gpt-4o-mini", "NPDEV_TEST_OPENAI_KEY", ExternalAiRequestFormat.OPENAI_CHAT);
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(profile), HttpClient.newHttpClient(), env -> "test-key");
        ExternalAiToolChatRequest request = new ExternalAiToolChatRequest(
                "openai", "gpt-5", "be brief", List.of(stockTool()), List.of(
                        ExternalAiChatTurn.user("show p1"),
                        assistantText(null),
                        assistantRaw("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[]}"),
                        ExternalAiChatTurn.toolResult("call_8", "get_Pigment", null)));

        ExternalAiToolChatResult result = adapter.chatWithTools(request);

        JsonNode messages = new ObjectMapper().readTree(seenBody.get()).path("messages");
        assertEquals("system", messages.at("/0/role").asText());
        assertTrue(messages.at("/2/content").isNull(), "an assistant turn with no text sends content:null");
        assertTrue(messages.at("/3/tool_calls").isArray(), "vendorRaw replays verbatim");
        assertEquals("tool", messages.at("/4/role").asText());
        assertEquals("call_8", messages.at("/4/tool_call_id").asText());
        assertEquals("{}", messages.at("/4/content").asText());
        assertTrue(seenBody.get().contains("\"model\":\"gpt-5\""), seenBody.get());
        assertTrue(seenBody.get().contains("\"type\":\"function\""), seenBody.get());

        ExternalAiToolCall call = result.assistantTurn().toolCalls().get(0);
        assertEquals("call_9", call.id());
        assertEquals("p1", call.arguments().get("id"));
        assertNull(result.assistantTurn().text());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> adapter.chatWithTools(request));
        assertTrue(thrown.getMessage().contains("not valid JSON"), thrown.getMessage());
    }

    @Test
    void chatWithToolsSendsTheAnthropicShapeAndParsesToolUseBlocks() throws IOException {
        AtomicReference<String> seenBody = new AtomicReference<>();
        server = startStubServer("/v1/messages", exchange -> {
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, "{\"content\":[{\"type\":\"text\",\"text\":\"one sec\"},"
                    + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"list_StockEntry\",\"input\":{\"limit\":2}}]}");
        });
        ExternalAiVendorProfile profile = new ExternalAiVendorProfile(
                "anthropic", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages",
                "claude-opus-5", "NPDEV_TEST_ANTHROPIC_KEY", ExternalAiRequestFormat.ANTHROPIC_MESSAGES);
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(profile), HttpClient.newHttpClient(), env -> "test-key");

        ExternalAiToolChatResult result = adapter.chatWithTools(new ExternalAiToolChatRequest(
                "anthropic", null, "be brief", List.of(stockTool()), List.of(
                        ExternalAiChatTurn.user("stock?"),
                        assistantRaw("[{\"type\":\"tool_use\",\"id\":\"toolu_0\",\"name\":\"x\",\"input\":{}}]"),
                        ExternalAiChatTurn.toolResult("toolu_0", "x", "{\"ok\":true}"),
                        ExternalAiChatTurn.toolResult("toolu_00", "x", null),
                        assistantText("done"),
                        ExternalAiChatTurn.user(null))));

        JsonNode body = new ObjectMapper().readTree(seenBody.get());
        assertEquals("be brief", body.path("system").asText());
        assertTrue(body.path("max_tokens").isNumber());
        assertEquals("list_StockEntry", body.at("/tools/0/name").asText());
        assertTrue(body.at("/tools/0/input_schema").isObject());
        JsonNode messages = body.path("messages");
        assertEquals(5, messages.size(), "two consecutive tool turns merge into ONE user message: " + messages);
        assertEquals("toolu_0", messages.at("/1/content/0/id").asText(), "vendorRaw replays verbatim");
        assertEquals(2, messages.at("/2/content").size());
        assertEquals("tool_result", messages.at("/2/content/0/type").asText());
        assertEquals("{}", messages.at("/2/content/1/content").asText());
        assertEquals("done", messages.at("/3/content/0/text").asText());
        assertEquals("", messages.at("/4/content").asText());

        assertEquals("one sec", result.assistantTurn().text());
        ExternalAiToolCall call = result.assistantTurn().toolCalls().get(0);
        assertEquals("toolu_1", call.id());
        assertEquals(2, ((Number) call.arguments().get("limit")).intValue());
    }

    private static ExternalAiToolSpec stockTool() {
        return new ExternalAiToolSpec("list_StockEntry", "List stock entries.",
                Map.of("type", "object", "properties", Map.of("limit", Map.of("type", "integer"))));
    }

    private static ExternalAiChatTurn assistantRaw(String vendorRaw) {
        return new ExternalAiChatTurn("assistant", null, List.of(), vendorRaw, null, null, null);
    }

    private static ExternalAiChatTurn assistantText(String text) {
        return new ExternalAiChatTurn("assistant", text, List.of(), null, null, null, null);
    }

    private interface StubHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    @Test
    void generateStructuredSendsGeminiJsonModeWithInlineImageAndReadsUsage() throws IOException {
        AtomicReference<String> seenBody = new AtomicReference<>();
        server = startStubServer("/models/gemini-3.5-flash:generateContent", exchange -> {
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, "{\"candidates\":[{\"content\":{\"parts\":["
                    + "{\"text\":\"thinking...\",\"thought\":true},"
                    + "{\"text\":\"{\\\"tags\\\":\"},{\"text\":\"[\\\"red\\\"]}\"}]}}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":120,\"candidatesTokenCount\":8,\"thoughtsTokenCount\":30}}");
        });
        ExternalAiVendorProfile profile = new ExternalAiVendorProfile(
                "gemini", "http://127.0.0.1:" + server.getAddress().getPort(),
                "gemini-3.5-flash", "NPDEV_TEST_GEMINI_KEY", ExternalAiRequestFormat.GEMINI_GENERATE_CONTENT);
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(profile), HttpClient.newHttpClient(), env -> "test-key");

        ExternalAiStructuredResult result = adapter.generateStructured(new ExternalAiStructuredRequest(
                "gemini", null, "tag this cap", "{\"type\":\"object\",\"required\":[\"tags\"]}",
                new byte[] {1, 2, 3}, "image/png", 300));

        JsonNode body = new ObjectMapper().readTree(seenBody.get());
        assertEquals("tag this cap", body.at("/contents/0/parts/0/text").asText());
        assertEquals("image/png", body.at("/contents/0/parts/1/inline_data/mime_type").asText());
        assertEquals("AQID", body.at("/contents/0/parts/1/inline_data/data").asText());
        assertEquals("application/json", body.at("/generationConfig/responseMimeType").asText());
        assertEquals("object", body.at("/generationConfig/responseJsonSchema/type").asText());
        assertEquals(300, body.at("/generationConfig/maxOutputTokens").asInt());

        assertEquals("{\"tags\":[\"red\"]}", result.json(), "thought parts are skipped, text parts joined");
        assertEquals("gemini-3.5-flash", result.model());
        assertEquals(120, result.inputTokens());
        assertEquals(38, result.outputTokens(), "thinking tokens bill as output");
    }

    @Test
    void generateStructuredSendsOpenAiJsonSchemaAndStripsACodeFence() throws IOException {
        AtomicReference<String> seenBody = new AtomicReference<>();
        server = startStubServer("/v1/chat/completions", exchange -> {
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, "{\"choices\":[{\"message\":{\"content\":"
                    + "\"```json\\n{\\\"ok\\\":true}\\n```\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4}}");
        });
        ExternalAiVendorProfile profile = new ExternalAiVendorProfile(
                "openai", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "gpt-test", "NPDEV_TEST_OPENAI_KEY", ExternalAiRequestFormat.OPENAI_CHAT);
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(profile), HttpClient.newHttpClient(), env -> "test-key");

        ExternalAiStructuredResult result = adapter.generateStructured(new ExternalAiStructuredRequest(
                "openai", "gpt-override", "say ok", "{\"type\":\"object\"}", null, null, null));

        JsonNode body = new ObjectMapper().readTree(seenBody.get());
        assertEquals("gpt-override", body.path("model").asText());
        assertEquals("json_schema", body.at("/response_format/type").asText());
        assertEquals("object", body.at("/response_format/json_schema/schema/type").asText());
        assertEquals("say ok", body.at("/messages/0/content").asText());
        assertEquals("{\"ok\":true}", result.json());
        assertEquals(10, result.inputTokens());
        assertEquals(4, result.outputTokens());
    }

    @Test
    void generateStructuredDeniesWhenTheApiKeyEnvVarIsUnset() {
        HttpExternalAiCapabilityAdapter adapter = new HttpExternalAiCapabilityAdapter(
                List.of(ExternalAiVendorProfile.gemini("NPDEV_TEST_UNSET_KEY", "gemini-3.5-flash")),
                HttpClient.newHttpClient(), env -> null);
        ExternalAiEgressDeniedException denied = assertThrows(ExternalAiEgressDeniedException.class,
                () -> adapter.generateStructured(new ExternalAiStructuredRequest(
                        "gemini", null, "x", "{\"type\":\"object\"}", null, null, null)));
        assertEquals("EGRESS_DENIED_NO_API_KEY", denied.code());
    }

    private HttpServer startStubServer(String path, StubHandler handler) throws IOException {
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        stub.start();
        return stub;
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Minimal JSON string-quoting for building a stub response body inline in a test. */
    private static String jsonQuote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
