package com.finalexec.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.adapters.idempotency.inproc.InProcIdempotencyStore;
import com.npdev.adapters.messaging.http.HttpMessagingCapabilityAdapter;
import com.npdev.adapters.messaging.http.MessagingPeerProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R6.4: proves {@link MessagingDeliveryController} does what its javadoc claims -- forwards an
 * inbound request UNCHANGED to the real {@link HttpMessagingCapabilityAdapter}'s own embedded
 * loopback receiver, which does the actual signature verification, dedupe, and local dispatch. No
 * mocking of the adapter's security logic: every assertion here is driven by a REAL adapter instance
 * bound to a REAL ephemeral loopback port, exactly {@code HttpMessagingCapabilityAdapterTest}'s own
 * "real two-instance bridge" style, one hop shorter (peer -&gt; this controller -&gt; the adapter's
 * receiver, instead of peer adapter -&gt; this app's receiver directly).
 */
class MessagingDeliveryControllerTest {

    private static final String SENDER_APP_ID = "peer-app";
    private static final String SECRET_ENV = "NPDEV_TEST_MESSAGING_DELIVERY_SECRET";
    private static final String SECRET_VALUE = "unit-test-messaging-secret-not-real";
    private static final String SIGNATURE_HEADER = "X-Npdev-Messaging-Signature";
    private static final String DELIVERY_ID_HEADER = "X-Npdev-Messaging-Delivery";
    private static final String SENDER_HEADER = "X-Npdev-Messaging-Sender";

    private HttpMessagingCapabilityAdapter adapter;

    @AfterEach
    void closeAdapter() {
        if (adapter != null) {
            adapter.close();
        }
    }

    @Test
    void forwardsAValidlySignedDeliveryToTheAdapterAndDispatchesLocally() throws Exception {
        adapter = newAdapter();
        List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        adapter.subscribe("order.created", (Consumer<Map<String, Object>>) received::add);

        String body = "{\"topic\":\"order.created\",\"deliveryId\":\"d-1\",\"senderAppId\":\"" + SENDER_APP_ID
                + "\",\"payload\":{\"orderId\":\"o-42\"}}";

        mockMvc(adapter).perform(post(HttpMessagingCapabilityAdapter.INBOUND_PATH)
                        .contentType("application/json")
                        .header(SENDER_HEADER, SENDER_APP_ID)
                        .header(DELIVERY_ID_HEADER, "d-1")
                        .header(SIGNATURE_HEADER, "sha256=" + hmacHex(SECRET_VALUE, body))
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("delivered"))
                .andExpect(jsonPath("$.delivered").value(1));

        assertEquals(1, received.size());
        assertEquals("o-42", received.get(0).get("orderId"));
    }

    @Test
    void aSecondDeliveryOfTheSameIdIsDedupedNotRedispatched() throws Exception {
        adapter = newAdapter();
        List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        adapter.subscribe("order.created", (Consumer<Map<String, Object>>) received::add);

        String body = "{\"topic\":\"order.created\",\"deliveryId\":\"d-dup\",\"senderAppId\":\"" + SENDER_APP_ID
                + "\",\"payload\":{}}";
        String signature = "sha256=" + hmacHex(SECRET_VALUE, body);
        MockMvc mockMvc = mockMvc(adapter);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(HttpMessagingCapabilityAdapter.INBOUND_PATH)
                            .contentType("application/json")
                            .header(SENDER_HEADER, SENDER_APP_ID)
                            .header(DELIVERY_ID_HEADER, "d-dup")
                            .header(SIGNATURE_HEADER, signature)
                            .content(body))
                    .andExpect(status().isOk());
        }

        assertEquals(1, received.size(), "a retried delivery of the same deliveryId must dispatch at most once");
    }

    @Test
    void rejectsAWronglySignedDeliveryBeforeAnyDispatch() throws Exception {
        adapter = newAdapter();
        List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        adapter.subscribe("order.created", (Consumer<Map<String, Object>>) received::add);

        String body = "{\"topic\":\"order.created\",\"deliveryId\":\"d-2\",\"senderAppId\":\"" + SENDER_APP_ID
                + "\",\"payload\":{}}";

        mockMvc(adapter).perform(post(HttpMessagingCapabilityAdapter.INBOUND_PATH)
                        .contentType("application/json")
                        .header(SENDER_HEADER, SENDER_APP_ID)
                        .header(DELIVERY_ID_HEADER, "d-2")
                        .header(SIGNATURE_HEADER, "sha256=" + hmacHex("wrong-secret", body))
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MESSAGING_SIGNATURE_INVALID"));

        assertTrue(received.isEmpty(), "a forged signature must never reach a local subscriber");
    }

    @Test
    void rejectsAnUnknownSenderBeforeAnyDispatch() throws Exception {
        adapter = newAdapter();
        String body = "{\"topic\":\"order.created\",\"deliveryId\":\"d-3\",\"senderAppId\":\"someone-else\","
                + "\"payload\":{}}";

        mockMvc(adapter).perform(post(HttpMessagingCapabilityAdapter.INBOUND_PATH)
                        .contentType("application/json")
                        .header(SENDER_HEADER, "someone-else")
                        .header(DELIVERY_ID_HEADER, "d-3")
                        .header(SIGNATURE_HEADER, "sha256=" + hmacHex(SECRET_VALUE, body))
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MESSAGING_SENDER_NOT_TRUSTED"));
    }

    @Test
    void returnsNotFoundWhenThisAppHasNoMessagingHttpBinding() throws Exception {
        ObjectProvider<HttpMessagingCapabilityAdapter> emptyProvider = new ObjectProvider<>() {
            @Override
            public HttpMessagingCapabilityAdapter getObject() {
                throw new IllegalStateException("not configured");
            }

            @Override
            public HttpMessagingCapabilityAdapter getObject(Object... args) {
                throw new IllegalStateException("not configured");
            }

            @Override
            public HttpMessagingCapabilityAdapter getIfAvailable() {
                return null;
            }

            @Override
            public HttpMessagingCapabilityAdapter getIfUnique() {
                return null;
            }
        };
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new MessagingDeliveryController(emptyProvider, new ObjectMapper())
        ).build();

        mockMvc.perform(post(HttpMessagingCapabilityAdapter.INBOUND_PATH)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MESSAGING_NOT_CONFIGURED"));
    }

    private static HttpMessagingCapabilityAdapter newAdapter() {
        MessagingPeerProfile peer = MessagingPeerProfile.of(SENDER_APP_ID, "http://ignored-in-this-test", SECRET_ENV);
        return new HttpMessagingCapabilityAdapter(
                "this-app", List.of(peer),
                HttpClient.newHttpClient(),
                env -> SECRET_ENV.equals(env) ? SECRET_VALUE : null,
                Duration.ofSeconds(5), 0, Duration.ofMillis(50),
                new InProcIdempotencyStore(),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    private static MockMvc mockMvc(HttpMessagingCapabilityAdapter adapter) {
        ObjectProvider<HttpMessagingCapabilityAdapter> provider = new ObjectProvider<>() {
            @Override
            public HttpMessagingCapabilityAdapter getObject() {
                return adapter;
            }

            @Override
            public HttpMessagingCapabilityAdapter getObject(Object... args) {
                return adapter;
            }

            @Override
            public HttpMessagingCapabilityAdapter getIfAvailable() {
                return adapter;
            }

            @Override
            public HttpMessagingCapabilityAdapter getIfUnique() {
                return adapter;
            }
        };
        return MockMvcBuilders.standaloneSetup(
                new MessagingDeliveryController(provider, new ObjectMapper())
        ).build();
    }

    private static String hmacHex(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
