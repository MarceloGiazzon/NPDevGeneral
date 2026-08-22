package com.npdev.adapters.messaging.http;

import com.npdev.adapters.idempotency.inproc.InProcIdempotencyStore;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves R6.4's definition of done in separate, non-collapsed cases: (a) a real loopback HTTP round
 * trip between two independently-configured adapter instances -- "an event published in app A is
 * received and acted on in app B", where "app A"/"app B" are two {@link HttpMessagingCapabilityAdapter}
 * instances each with their own {@code IdempotencyStore}, peer config and (for B) an embedded
 * inbound {@code HttpServer} bound to a real ephemeral loopback port; (b) a duplicate delivery of the
 * SAME {@code deliveryId} is survivable -- the subscriber fires exactly once; (c) an unreachable peer
 * makes {@code publish} throw rather than silently succeed, bounded by this adapter's own deadline
 * (RUN-4 style, a hanging socket); (d) a peer with no configured secret is denied fail-closed; (e) a
 * forged signature is rejected by the receiver.
 *
 * <p>This is a single-process proof (both "apps" run in this one JVM/test), stated plainly rather
 * than overclaimed: what makes it an honest substitute for two booted FinalApps is that the two
 * instances never share state directly -- they only ever talk to each other over a real HTTP POST to
 * a real bound socket, exactly as two separate processes would.
 */
class HttpMessagingCapabilityAdapterTest {

    private static final String SECRET_ENV = "NPDEV_TEST_MESSAGING_SECRET";
    private static final String SECRET_VALUE = "s3cr3t-messaging-key";

    private HttpMessagingCapabilityAdapter appA;
    private HttpMessagingCapabilityAdapter appB;

    @AfterEach
    void closeAdapters() {
        if (appA != null) {
            appA.close();
        }
        if (appB != null) {
            appB.close();
        }
    }

    // ------------------------------------------------------------------ (a) real two-instance bridge

    @Test
    void anEventPublishedInAppAIsReceivedAndActedOnInAppB() throws InterruptedException {
        appB = newAdapter("app-b", List.of(trustPeer("app-a")), bindReceiver());
        appA = newAdapter("app-a", List.of(peerToward("app-b", appB)), null);

        List<Map<String, Object>> receivedByB = new CopyOnWriteArrayList<>();
        appB.subscribe("order.created", (Consumer<Map<String, Object>>) receivedByB::add);

        Object ack = appA.publish(Map.of("topic", "order.created", "orderId", "o-1"));

        assertTrue(ack instanceof Map<?, ?>);
        Map<?, ?> ackMap = (Map<?, ?>) ack;
        assertEquals("published", ackMap.get("status"));
        assertEquals(List.of("app-b"), ackMap.get("remoteDeliveries"));

        awaitUntil(() -> receivedByB.size() == 1, "app B's subscriber to receive the bridged event");
        assertEquals("order.created", receivedByB.get(0).get("topic"));
        assertEquals("o-1", receivedByB.get(0).get("orderId"));
    }

    @Test
    void aFlowCapabilityCallPublishesThroughTheSameBridge() throws InterruptedException {
        appB = newAdapter("app-b", List.of(trustPeer("app-a")), bindReceiver());
        appA = newAdapter("app-a", List.of(peerToward("app-b", appB)), null);
        List<Object> receivedByB = new CopyOnWriteArrayList<>();
        appB.subscribe("invoice.paid", (Consumer<Map<String, Object>>) receivedByB::add);

        CapabilityCall call = new CapabilityCall(
                "messaging", "MessagingCapability", "messaging-http", "publish",
                Map.of("topic", "invoice.paid", "invoiceId", "inv-9"));
        CapabilityResult result = appA.invoke(call, Map.of());

        assertTrue(result.ok(), "expected the capability call to succeed: " + result);
        awaitUntil(() -> receivedByB.size() == 1, "app B's subscriber via the capabilityCall path");
    }

    // ------------------------------------------------------------------ (b) duplicate is survivable

    @Test
    void aDuplicateDeliveryOfTheSameDeliveryIdInvokesTheSubscriberOnlyOnce() throws InterruptedException {
        appB = newAdapter("app-b", List.of(trustPeer("app-a")), bindReceiver());
        appA = newAdapter("app-a", List.of(peerToward("app-b", appB)), null);
        AtomicInteger invocations = new AtomicInteger();
        appB.subscribe("payment.settled", (Consumer<Map<String, Object>>) m -> invocations.incrementAndGet());

        Map<String, Object> message = Map.of("topic", "payment.settled", "deliveryId", "fixed-delivery-1", "amount", 100);
        appA.publish(message);
        awaitUntil(() -> invocations.get() == 1, "the first delivery to be processed");

        // Same deliveryId again -- simulates a sender-side retry after a lost ack, or a genuine
        // network-level redelivery. The receiver must not invoke the subscriber a second time.
        appA.publish(message);

        Thread.sleep(200); // give a wrongly-firing second dispatch a chance to show up
        assertEquals(1, invocations.get(), "a duplicate deliveryId must not re-invoke the subscriber");
    }

    @Test
    void aDeliveryThatFailedIsRetriedAndCanLaterSucceed() throws InterruptedException {
        appB = newAdapter("app-b", List.of(trustPeer("app-a")), bindReceiver());
        appA = newAdapter("app-a", List.of(peerToward("app-b", appB)), null);
        AtomicInteger attempts = new AtomicInteger();
        appB.subscribe("shipment.dispatched", (Consumer<Map<String, Object>>) m -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated transient subscriber failure on first attempt");
            }
        });

        Map<String, Object> message = Map.of("topic", "shipment.dispatched", "deliveryId", "fixed-delivery-2", "n", 1);
        // First publish: B's subscriber throws -> B returns 500 -> A's own retry loop re-sends the
        // SAME deliveryId -> B sees status FAILED (not SUCCESS) and retries the subscriber, which
        // succeeds on its second invocation. So the whole publish() call succeeds without app A ever
        // seeing a failure, and the subscriber genuinely ran twice for a message that ultimately
        // succeeded once -- "at-least-once", not "exactly-once".
        Object ack = appA.publish(message);

        assertTrue(ack instanceof Map<?, ?>);
        awaitUntil(() -> attempts.get() >= 2, "the receiver's own retry to re-invoke the subscriber");
    }

    // ------------------------------------------------------------------ (c) unreachable peer is visible

    @Test
    void publishThrowsRatherThanSilentlySucceedingWhenAPeerIsUnreachable() throws Exception {
        try (ServerSocket hangingServer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger acceptedConnections = new AtomicInteger();
            Thread acceptor = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        hangingServer.accept();
                        acceptedConnections.incrementAndGet();
                        // Deliberately never write a response.
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "hanging-messaging-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            MessagingPeerProfile hangingPeer = MessagingPeerProfile.of(
                    "app-hanging", "http://127.0.0.1:" + hangingServer.getLocalPort(), SECRET_ENV);
            appA = new HttpMessagingCapabilityAdapter(
                    "app-a", List.of(hangingPeer),
                    HttpClient.newHttpClient(),
                    env -> SECRET_ENV.equals(env) ? SECRET_VALUE : null,
                    Duration.ofMillis(300), 1, Duration.ofMillis(50),
                    new InProcIdempotencyStore(), null);

            long startedAt = System.nanoTime();
            MessagingDeliveryFailedException thrown = assertThrows(MessagingDeliveryFailedException.class,
                    () -> appA.publish(Map.of("topic", "never.arrives", "n", 1)));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            acceptor.interrupt();

            assertTrue(elapsedMs < 5000, "expected the adapter's own deadline to bound the call, took " + elapsedMs + "ms");
            assertEquals(2, acceptedConnections.get(), "expected exactly maxRetries+1 = 2 attempts");
            assertTrue(thrown.failuresByPeerAppId().containsKey("app-hanging"), thrown.getMessage());
            assertEquals(List.of(), thrown.succeededPeerAppIds());
        }
    }

    // ------------------------------------------------------------------ (d) fail-closed on no secret

    @Test
    void publishDeniesFailClosedWhenAPeersHmacSecretEnvVarIsUnset() {
        MessagingPeerProfile peer = MessagingPeerProfile.of("app-c", "http://127.0.0.1:1", "NPDEV_TEST_UNSET_SECRET");
        appA = new HttpMessagingCapabilityAdapter("app-a", List.of(peer), new InProcIdempotencyStore());

        MessagingDeliveryFailedException thrown = assertThrows(MessagingDeliveryFailedException.class,
                () -> appA.publish(Map.of("topic", "anything", "n", 1)));

        assertTrue(thrown.failuresByPeerAppId().get("app-c").contains("NPDEV_TEST_UNSET_SECRET"),
                thrown.failuresByPeerAppId().toString());
    }

    // ------------------------------------------------------------------ (e) forged signature rejected

    @Test
    void theReceiverRejectsAForgedSignature() throws Exception {
        appB = newAdapter("app-b", List.of(), bindReceiver());
        List<Object> received = new CopyOnWriteArrayList<>();
        appB.subscribe("topic.x", (Consumer<Map<String, Object>>) received::add);

        String body = "{\"topic\":\"topic.x\",\"deliveryId\":\"forged-1\",\"senderAppId\":\"app-forger\",\"payload\":{}}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + appB.inboundPort() + HttpMessagingCapabilityAdapter.INBOUND_PATH))
                .header("Content-Type", "application/json")
                .header("X-Npdev-Messaging-Sender", "app-forger")
                .header("X-Npdev-Messaging-Signature", "sha256=" + hmacHex("wrong-secret", body))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        // app-forger is not even a configured peer of app-b, so this is denied before the signature
        // is even checked -- proves the sender-allowlist half of the fail-closed posture.
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("MESSAGING_SENDER_NOT_TRUSTED"), response.body());
        assertTrue(received.isEmpty());
    }

    @Test
    void theReceiverRejectsAKnownSenderWithAWrongSignature() throws Exception {
        appB = newAdapter("app-b", List.of(peerToward("app-a", null, "http://127.0.0.1:1")), bindReceiver());
        List<Object> received = new CopyOnWriteArrayList<>();
        appB.subscribe("topic.y", (Consumer<Map<String, Object>>) received::add);

        String body = "{\"topic\":\"topic.y\",\"deliveryId\":\"tampered-1\",\"senderAppId\":\"app-a\",\"payload\":{}}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + appB.inboundPort() + HttpMessagingCapabilityAdapter.INBOUND_PATH))
                .header("Content-Type", "application/json")
                .header("X-Npdev-Messaging-Sender", "app-a")
                .header("X-Npdev-Messaging-Signature", "sha256=" + hmacHex("definitely-not-" + SECRET_VALUE, body))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("MESSAGING_SIGNATURE_INVALID"), response.body());
        assertTrue(received.isEmpty());
    }

    // ------------------------------------------------------------------ local-only, no peers

    @Test
    void publishWithNoConfiguredPeersOnlyDeliversLocallyAndDoesNotThrow() {
        appA = new HttpMessagingCapabilityAdapter("solo-app", List.of(), new InProcIdempotencyStore());
        List<Object> received = new CopyOnWriteArrayList<>();
        appA.subscribe("local.only", (Consumer<Map<String, Object>>) received::add);

        Object ack = appA.publish(Map.of("topic", "local.only", "n", 1));

        Map<?, ?> ackMap = (Map<?, ?>) ack;
        assertEquals(1, ackMap.get("localDeliveries"));
        assertEquals(List.of(), ackMap.get("remoteDeliveries"));
        assertEquals(1, received.size());
    }

    @Test
    void unsubscribeStopsFurtherLocalDelivery() {
        appA = new HttpMessagingCapabilityAdapter("solo-app", List.of(), new InProcIdempotencyStore());
        List<Object> received = new CopyOnWriteArrayList<>();
        Object subscriptionRef = appA.subscribe("topic.z", (Consumer<Map<String, Object>>) received::add);

        appA.publish(Map.of("topic", "topic.z", "n", 1));
        appA.unsubscribe(subscriptionRef);
        appA.publish(Map.of("topic", "topic.z", "n", 2));

        assertEquals(1, received.size());
    }

    @Test
    void invokeReturnsAStructuredFailureForSubscribeSinceAHandlerIsNotJsonRepresentable() {
        appA = new HttpMessagingCapabilityAdapter("solo-app", List.of(), new InProcIdempotencyStore());
        CapabilityCall call = new CapabilityCall(
                "messaging", "MessagingCapability", "messaging-http", "subscribe", List.of("topic.q"));

        CapabilityResult result = appA.invoke(call, Map.of());

        assertFalse(result.ok());
        assertEquals("MESSAGING_OPERATION_UNSUPPORTED_VIA_CAPABILITY_CALL", result.error().code());
    }

    // ------------------------------------------------------------------ helpers

    private HttpMessagingCapabilityAdapter newAdapter(String appId, List<MessagingPeerProfile> peers, InetSocketAddress listenAddress) {
        return new HttpMessagingCapabilityAdapter(
                appId, peers,
                HttpClient.newHttpClient(),
                env -> SECRET_ENV.equals(env) ? SECRET_VALUE : null,
                Duration.ofSeconds(5), 2, Duration.ofMillis(50),
                new InProcIdempotencyStore(), listenAddress);
    }

    private static InetSocketAddress bindReceiver() {
        return new InetSocketAddress("127.0.0.1", 0);
    }

    private static MessagingPeerProfile peerToward(String peerAppId, HttpMessagingCapabilityAdapter target) {
        return MessagingPeerProfile.of(peerAppId, "http://127.0.0.1:" + target.inboundPort(), SECRET_ENV);
    }

    /**
     * Trust is symmetric and configured on both sides, on purpose (fail-closed: the receiver only
     * accepts a sender it was told about). This entry's {@code baseUrl} is never dialed by these
     * tests -- app-b never publishes back to app-a here -- so a placeholder is fine; only the
     * {@code peerAppId} + shared secret matter for verifying an INBOUND sender.
     */
    private static MessagingPeerProfile trustPeer(String peerAppId) {
        return MessagingPeerProfile.of(peerAppId, "http://127.0.0.1:1", SECRET_ENV);
    }

    private static MessagingPeerProfile peerToward(String peerAppId, HttpMessagingCapabilityAdapter target, String baseUrl) {
        return MessagingPeerProfile.of(peerAppId, baseUrl, SECRET_ENV);
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for: " + what);
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
