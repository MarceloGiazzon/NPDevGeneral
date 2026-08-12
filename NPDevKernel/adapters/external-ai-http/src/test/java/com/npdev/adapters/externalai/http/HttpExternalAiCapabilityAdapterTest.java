package com.npdev.adapters.externalai.http;

import com.npdev.kernel.ports.ExternalAiEgressDeniedException;
import com.npdev.kernel.ports.ExternalAiGenerationRequest;
import com.npdev.kernel.ports.ExternalAiGenerationResult;
import com.npdev.kernel.ports.ExternalAiPackSubmission;
import com.npdev.kernel.ports.ExternalAiRunResult;
import com.npdev.kernel.ports.ExternalAiVendorSummary;
import com.npdev.kernel.ports.ExternalAiVerdictRecord;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    private interface StubHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
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
