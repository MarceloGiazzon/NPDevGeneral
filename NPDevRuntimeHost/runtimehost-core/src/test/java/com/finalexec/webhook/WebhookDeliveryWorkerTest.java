package com.finalexec.webhook;

import com.npdev.adapters.webhook.http.HttpWebhookCapabilityAdapter;
import com.npdev.adapters.webhook.http.WebhookDeliveryRecordStore;
import com.npdev.adapters.webhook.http.WebhookDestinationProfile;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.storage.sql.H2Dialect;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** INT-5: queue-on-post, background drain, backoff, dead-letter and redeliver against a loopback stub. */
class WebhookDeliveryWorkerTest {

    private record Seen(Headers headers, String body) {
    }

    private HttpServer stub;
    private String url;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private DriverManagerDataSource dataSource;
    private final WebhookDeliveryRecordStore store = new WebhookDeliveryRecordStore(H2Dialect.INSTANCE);
    private QueuedWebhookCapabilityHandler handler;
    private WebhookDeliveryWorker worker;

    @BeforeEach
    void setUp() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            try {
                seen.add(new Seen(exchange.getRequestHeaders(),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                exchange.sendResponseHeaders(status, -1);
            } finally {
                exchange.close();
            }
        });
        stub.start();
        url = "http://127.0.0.1:" + stub.getAddress().getPort() + "/hooks/orders";

        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");

        HttpWebhookCapabilityAdapter adapter = new HttpWebhookCapabilityAdapter(
                List.of(WebhookDestinationProfile.of("127.0.0.1", "ERP_SECRET")),
                HttpClient.newHttpClient(),
                env -> "ERP_SECRET".equals(env) ? "s3cret" : null,
                Duration.ofSeconds(5), 0, Duration.ZERO, null, null);
        handler = new QueuedWebhookCapabilityHandler(() -> adapter, () -> dataSource, store);
        worker = new WebhookDeliveryWorker(() -> adapter, () -> dataSource, store);
    }

    @AfterEach
    void tearDown() {
        stub.stop(0);
    }

    private CapabilityResult post(String targetUrl) {
        return handler.invoke(new CapabilityCall("webhook", "WebhookCapability", "webhook-http", "post",
                        List.of(Map.of("url", targetUrl, "event", "WidgetOrderPlaced", "orderId", "o-1", "status", "NEW"))),
                Map.of("eventName", "WidgetOrderPlaced"));
    }

    private List<Map<String, Object>> rows() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            store.ensureSchema(connection);
            return store.list(connection, null, 50);
        }
    }

    @Test
    void postOnlyQueuesAndTheWorkerDeliversASignedRequestWithTheEventHeader() throws Exception {
        CapabilityResult queued = post(url);

        assertTrue(queued.ok(), String.valueOf(queued.error()));
        assertEquals("queued", ((Map<?, ?>) queued.value()).get("status"));
        assertEquals(0, seen.size(), "post() must never send in-line");
        assertEquals("PENDING", rows().get(0).get("status"));

        assertEquals(1, worker.drainOnce(Instant.now().plusSeconds(1)));

        assertEquals(1, seen.size());
        Seen request = seen.get(0);
        String deliveryId = String.valueOf(((Map<?, ?>) queued.value()).get("requestId"));
        assertEquals(deliveryId, request.headers().getFirst("X-Npdev-Webhook-Delivery"));
        assertEquals("WidgetOrderPlaced", request.headers().getFirst("X-Npdev-Webhook-Event"));
        assertEquals("sha256=" + hmacHex("s3cret", request.body()), request.headers().getFirst("X-Npdev-Webhook-Signature"));
        assertTrue(request.body().contains("\"orderId\":\"o-1\""), request.body());
        assertFalse(request.body().contains("\"url\""), "routing url is not part of the body");
        assertEquals("DELIVERED", rows().get(0).get("status"));
    }

    @Test
    void aFailingReceiverIsRetriedWithBackoffThenDeadLetteredAndRedeliverKeepsTheId() throws Exception {
        status = 503;
        String id = String.valueOf(((Map<?, ?>) post(url).value()).get("requestId"));

        Instant before = Instant.now();
        assertEquals(1, worker.drainOnce(before.plusSeconds(1)));
        Map<String, Object> row = rows().get(0);
        assertEquals("PENDING", row.get("status"));
        assertEquals(1, row.get("attempt_count"));
        Instant next = Instant.parse(String.valueOf(row.get("next_attempt_at")));
        assertTrue(next.isAfter(before.plusSeconds(25)) && next.isBefore(before.plusSeconds(60)),
                "first retry is ~30 s out, was " + next);
        assertEquals(0, worker.drainOnce(before.plusSeconds(5)), "not due yet");

        Instant farFuture = Instant.now().plus(Duration.ofDays(30));
        for (int attempt = 2; attempt <= 6; attempt++) {
            assertEquals(1, worker.drainOnce(farFuture), "attempt " + attempt);
        }
        row = rows().get(0);
        assertEquals("FAILED", row.get("status"));
        assertEquals(6, row.get("attempt_count"));
        assertEquals(6, seen.size());
        assertEquals(0, worker.drainOnce(farFuture), "a dead letter is never retried by itself");

        status = 200;
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(store.redeliver(connection, id, Instant.now()));
        }
        assertEquals(1, worker.drainOnce(Instant.now().plusSeconds(1)));
        assertEquals(id, seen.get(seen.size() - 1).headers().getFirst("X-Npdev-Webhook-Delivery"),
                "redelivery keeps the delivery id");
        assertEquals("DELIVERED", rows().get(0).get("status"));
    }

    @Test
    void aPermanentClientErrorDeadLettersImmediately() throws Exception {
        status = 400;
        post(url);

        assertEquals(1, worker.drainOnce(Instant.now().plusSeconds(1)));

        assertEquals("FAILED", rows().get(0).get("status"));
        assertEquals(1, seen.size());
    }

    @Test
    void anUnlistedHostIsRefusedAndNothingIsQueued() throws Exception {
        CapabilityResult result = post("https://evil.example.org/hook");

        assertFalse(result.ok());
        assertEquals("WEBHOOK_EGRESS_DENIED_HOST_NOT_ALLOWED", result.error().code());
        assertTrue(rows().isEmpty());
        assertEquals(0, seen.size());
    }

    @Test
    void unsupportedOperationsAndMissingBeansFailClosedWithoutThrowing() {
        CapabilityResult unsupported = handler.invoke(
                new CapabilityCall("webhook", "WebhookCapability", "webhook-http", "put", List.of(Map.of())), Map.of());
        assertEquals("WEBHOOK_OPERATION_UNSUPPORTED", unsupported.error().code());

        QueuedWebhookCapabilityHandler unconfigured = new QueuedWebhookCapabilityHandler(() -> null, () -> null, store);
        CapabilityResult result = unconfigured.invoke(
                new CapabilityCall("webhook", "WebhookCapability", "webhook-http", "post", List.of(Map.of("url", url))), Map.of());
        assertEquals("WEBHOOK_NOT_CONFIGURED", result.error().code());
        assertEquals(0, new WebhookDeliveryWorker(() -> null, () -> null, store).drainOnce(Instant.now()));
        assertNotEquals(null, result.error().message());
    }

    private static String hmacHex(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
