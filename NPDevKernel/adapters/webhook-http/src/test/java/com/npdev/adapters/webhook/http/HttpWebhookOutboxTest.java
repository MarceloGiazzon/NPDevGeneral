package com.npdev.adapters.webhook.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** INT-4: prepare() + deliverOnce() -- the adapter halves the outbox worker (INT-5) is built from. */
class HttpWebhookOutboxTest {

    private HttpServer stub;

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    private HttpWebhookCapabilityAdapter adapter(String host) {
        return new HttpWebhookCapabilityAdapter(
                List.of(WebhookDestinationProfile.of(host, "OUTBOX_SECRET")),
                HttpClient.newHttpClient(),
                env -> "OUTBOX_SECRET".equals(env) ? "outbox-secret" : null,
                Duration.ofSeconds(5), 0, Duration.ZERO, null, null);
    }

    @Test
    void prepareRejectsAnUnlistedHostAndStripsTheUrlFromTheBody() {
        HttpWebhookCapabilityAdapter adapter = adapter("hooks.example.com");

        assertThrows(WebhookEgressDeniedException.class,
                () -> adapter.prepare(Map.of("url", "https://evil.example.org/hook", "n", 1)));

        HttpWebhookCapabilityAdapter.PreparedDelivery prepared =
                adapter.prepare(Map.of("url", "https://hooks.example.com/hook", "n", 1));
        assertEquals("hooks.example.com", prepared.host());
        assertEquals("https://hooks.example.com/hook", prepared.url());
        assertEquals("{\"n\":1}", prepared.bodyJson());
    }

    @Test
    void prepareFailsClosedWhenNoSecretIsConfigured() {
        HttpWebhookCapabilityAdapter noSecret = new HttpWebhookCapabilityAdapter(
                List.of(WebhookDestinationProfile.of("hooks.example.com", "OUTBOX_SECRET")),
                HttpClient.newHttpClient(), env -> null);
        assertThrows(WebhookEgressDeniedException.class,
                () -> noSecret.prepare(Map.of("url", "https://hooks.example.com/hook")));
    }

    @Test
    void deliverOnceSendsSignedBodyWithDeliveryIdAndEventHeader() throws Exception {
        AtomicReference<com.sun.net.httpserver.Headers> seenHeaders = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        String url = start(200, seenHeaders, seenBody);

        HttpWebhookCapabilityAdapter.DeliveryAttempt attempt =
                adapter("127.0.0.1").deliverOnce("delivery-1", url, "{\"n\":1}", "WidgetCreated");

        assertTrue(attempt.delivered());
        assertEquals(200, attempt.httpStatus());
        assertEquals("delivery-1", seenHeaders.get().getFirst("X-Npdev-Webhook-Delivery"));
        assertEquals("WidgetCreated", seenHeaders.get().getFirst("X-Npdev-Webhook-Event"));
        assertEquals("sha256=" + hmacHex("outbox-secret", "{\"n\":1}"), seenHeaders.get().getFirst("X-Npdev-Webhook-Signature"));
        assertEquals("{\"n\":1}", seenBody.get());
    }

    @Test
    void deliverOnceClassifiesRetryableAndPermanentFailures() throws Exception {
        String url503 = start(503, new AtomicReference<>(), new AtomicReference<>());
        HttpWebhookCapabilityAdapter.DeliveryAttempt transientFailure = adapter("127.0.0.1").deliverOnce("d", url503, "{}", null);
        assertFalse(transientFailure.delivered());
        assertTrue(transientFailure.retryable());
        assertEquals(503, transientFailure.httpStatus());
        stub.stop(0);

        String url400 = start(400, new AtomicReference<>(), new AtomicReference<>());
        HttpWebhookCapabilityAdapter.DeliveryAttempt permanent = adapter("127.0.0.1").deliverOnce("d", url400, "{}", null);
        assertFalse(permanent.delivered());
        assertFalse(permanent.retryable());
        stub.stop(0);

        HttpWebhookCapabilityAdapter.DeliveryAttempt denied =
                adapter("hooks.example.com").deliverOnce("d", "https://elsewhere.example.org/x", "{}", null);
        assertFalse(denied.delivered());
        assertFalse(denied.retryable(), "an egress denial is never retried");

        HttpWebhookCapabilityAdapter.DeliveryAttempt unreachable =
                adapter("127.0.0.1").deliverOnce("d", "http://127.0.0.1:1/x", "{}", null);
        assertFalse(unreachable.delivered());
        assertTrue(unreachable.retryable(), "a transport failure is retryable");
    }

    @Test
    void backoffGrowsAndDeadLettersAfterTheLastAttempt() {
        assertEquals(Duration.ofSeconds(30), WebhookBackoff.delayAfter(1));
        assertEquals(Duration.ofMinutes(2), WebhookBackoff.delayAfter(2));
        assertEquals(Duration.ofHours(6), WebhookBackoff.delayAfter(5));
        assertEquals(Duration.ofHours(6), WebhookBackoff.delayAfter(99));
        assertTrue(WebhookBackoff.hasAttemptsLeft(WebhookBackoff.MAX_ATTEMPTS - 1));
        assertFalse(WebhookBackoff.hasAttemptsLeft(WebhookBackoff.MAX_ATTEMPTS));
    }

    private String start(int status, AtomicReference<com.sun.net.httpserver.Headers> headers, AtomicReference<String> body)
            throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            try {
                headers.set(exchange.getRequestHeaders());
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(status, -1);
            } finally {
                exchange.close();
            }
        });
        stub.start();
        return "http://127.0.0.1:" + stub.getAddress().getPort() + "/hook";
    }

    private static String hmacHex(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
