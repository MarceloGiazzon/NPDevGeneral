package com.npdev.adapters.webhook.http;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves R6.1's three-part definition of done separately, never collapsed into one happy-path
 * test: (a) a signed POST reaches a real local server and the signature verifies, driven through
 * the same {@link CapabilityCall}/{@code invoke} entry point a flow's {@code capabilityCall} step
 * uses; (b) a hanging destination is bounded by the adapter's own deadline and retried exactly
 * {@code maxRetries + 1} times, proven the RUN-4 way (a real loopback socket that accepts and never
 * answers); (c) an unlisted host is denied fail-closed with a named, stable error code.
 */
class HttpWebhookCapabilityAdapterTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------ (a) signed delivery

    @Test
    void aFlowCapabilityCallDeliversASignedPostToARealLocalServerAndTheSignatureVerifies() throws IOException {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedSignature = new AtomicReference<>();
        AtomicReference<String> receivedDeliveryId = new AtomicReference<>();
        server = startStubServer("/hooks/expense-approved", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedSignature.set(exchange.getRequestHeaders().getFirst("X-Npdev-Webhook-Signature"));
            receivedDeliveryId.set(exchange.getRequestHeaders().getFirst("X-Npdev-Webhook-Delivery"));
            byte[] bytes = "{\"received\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        HttpWebhookCapabilityAdapter adapter = new HttpWebhookCapabilityAdapter(
                List.of(WebhookDestinationProfile.of("127.0.0.1", "NPDEV_TEST_WEBHOOK_SECRET")),
                HttpClient.newHttpClient(),
                env -> "NPDEV_TEST_WEBHOOK_SECRET".equals(env) ? "s3cr3t-hmac-key" : null);

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hooks/expense-approved";
        CapabilityCall call = new CapabilityCall(
                "webhook", "WebhookCapability", "webhook-http", "post",
                Map.of("url", url, "expenseId", "exp-1", "approved", true));

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertTrue(result.ok(), "expected the capability call to succeed: " + result);
        Map<?, ?> value = (Map<?, ?>) result.value();
        assertEquals("delivered", value.get("status"));
        assertEquals(200, value.get("httpStatus"));
        assertNotNull(value.get("requestId"));
        assertEquals(value.get("requestId"), receivedDeliveryId.get(),
                "the X-Npdev-Webhook-Delivery header must carry the same id the caller gets back");

        // The server actually received the body -- "url" is routing metadata and must NOT leak into it.
        assertNotNull(receivedBody.get());
        assertFalse(receivedBody.get().contains("\"url\""), receivedBody.get());
        assertTrue(receivedBody.get().contains("\"expenseId\":\"exp-1\""), receivedBody.get());
        assertTrue(receivedBody.get().contains("\"approved\":true"), receivedBody.get());

        // Independently recompute the HMAC the way a receiver would, over the exact bytes it saw, and
        // confirm it verifies against the header the adapter sent.
        assertEquals("sha256=" + hmacHex("s3cr3t-hmac-key", receivedBody.get()), receivedSignature.get());
    }

    @Test
    void aDifferentSecretFailsVerification() throws IOException {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedSignature = new AtomicReference<>();
        server = startStubServer("/hooks/x", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedSignature.set(exchange.getRequestHeaders().getFirst("X-Npdev-Webhook-Signature"));
            exchange.sendResponseHeaders(200, -1);
        });
        HttpWebhookCapabilityAdapter adapter = new HttpWebhookCapabilityAdapter(
                List.of(WebhookDestinationProfile.of("127.0.0.1", "NPDEV_TEST_WEBHOOK_SECRET")),
                HttpClient.newHttpClient(),
                env -> "correct-secret");

        adapter.post(Map.of("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/hooks/x", "n", 1));

        assertEquals("sha256=" + hmacHex("correct-secret", receivedBody.get()), receivedSignature.get());
        assertFalse(receivedSignature.get().equals("sha256=" + hmacHex("wrong-secret", receivedBody.get())),
                "a receiver checking against the wrong secret must NOT see the signature verify");
    }

    // ------------------------------------------------------------------ (b) retry-on-hang, RUN-4 way

    /**
     * Cloned from {@code HttpExternalAiCapabilityAdapterTest
     * .requestTimesOutAndRetriesTheConfiguredNumberOfTimesAgainstAHangingServer} (ledger RUN-4): the
     * "hanging endpoint" is a loopback {@link ServerSocket} this test owns, which accepts the TCP
     * connection and deliberately never answers -- so the call can only return if the ADAPTER'S OWN
     * per-request deadline fires, not because the server closed anything.
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
            }, "hanging-webhook-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            HttpWebhookCapabilityAdapter adapter = new HttpWebhookCapabilityAdapter(
                    List.of(WebhookDestinationProfile.of("127.0.0.1", "NPDEV_TEST_WEBHOOK_SECRET")),
                    HttpClient.newHttpClient(),
                    env -> "test-secret",
                    // requestTimeout=300ms, maxRetries=1 (2 total attempts), retryBackoff=50ms -- short
                    // enough that the whole test resolves in well under a second if the deadline is
                    // real, and would hang the JUnit run (previously: forever) if it were not.
                    Duration.ofMillis(300),
                    1,
                    Duration.ofMillis(50),
                    null,
                    null);

            String url = "http://127.0.0.1:" + hangingServer.getLocalPort() + "/hooks/never-answers";
            long startedAt = System.nanoTime();
            UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                    () -> adapter.post(Map.of("url", url, "n", 1)));
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

    // ------------------------------------------------------------------ (c) fail-closed allowlist

    @Test
    void anUnlistedHostIsDeniedFailClosedWithANamedError() {
        HttpWebhookCapabilityAdapter adapter = new HttpWebhookCapabilityAdapter(
                List.of(WebhookDestinationProfile.of("allowed.example.com", "NPDEV_TEST_WEBHOOK_SECRET")),
                HttpClient.newHttpClient(),
                env -> "test-secret");

        WebhookEgressDeniedException thrown = assertThrows(WebhookEgressDeniedException.class,
                () -> adapter.post(Map.of("url", "https://not-allowed.example.com/hooks", "n", 1)));

        assertEquals("WEBHOOK_EGRESS_DENIED_HOST_NOT_ALLOWED", thrown.code());
        assertTrue(thrown.getMessage().contains("not-allowed.example.com"), thrown.getMessage());
    }

    @Test
    void everyDestinationIsDeniedWhenNoAllowlistIsConfiguredAtAll() {
        HttpWebhookCapabilityAdapter adapter = new HttpWebhookCapabilityAdapter(List.of());

        WebhookEgressDeniedException thrown = assertThrows(WebhookEgressDeniedException.class,
                () -> adapter.post(Map.of("url", "https://anything.example.com/hooks", "n", 1)));

        assertEquals("WEBHOOK_EGRESS_DENIED_NO_ALLOWLIST", thrown.code());
    }

    @Test
    void deniesFailClosedWhenTheHmacSecretEnvVarIsUnset() {
        HttpWebhookCapabilityAdapter adapter = new HttpWebhookCapabilityAdapter(
                List.of(WebhookDestinationProfile.of("allowed.example.com", "NPDEV_TEST_MISSING_WEBHOOK_SECRET")),
                HttpClient.newHttpClient(),
                env -> null);

        WebhookEgressDeniedException thrown = assertThrows(WebhookEgressDeniedException.class,
                () -> adapter.post(Map.of("url", "https://allowed.example.com/hooks", "n", 1)));

        assertEquals("WEBHOOK_EGRESS_DENIED_NO_SECRET", thrown.code());
        // The DENIAL may name the env var -- that is a name, not a value.
        assertTrue(thrown.getMessage().contains("NPDEV_TEST_MISSING_WEBHOOK_SECRET"), thrown.getMessage());
    }

    @Test
    void invokeReturnsAStructuredFailureForAnUnsupportedOperation() {
        HttpWebhookCapabilityAdapter adapter = new HttpWebhookCapabilityAdapter(List.of());
        CapabilityCall call = new CapabilityCall("webhook", "WebhookCapability", "webhook-http", "resend", Map.of());

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertFalse(result.ok());
        assertEquals("WEBHOOK_OPERATION_UNSUPPORTED", result.error().code());
    }

    // ------------------------------------------------------------------ helpers

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

    private static String hmacHex(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
