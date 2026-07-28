package com.npdev.adapters.externalai.http;

import com.npdev.kernel.ports.ExternalAiEgressDeniedException;
import com.npdev.kernel.ports.ExternalAiPackSubmission;
import com.npdev.kernel.ports.ExternalAiRunResult;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
