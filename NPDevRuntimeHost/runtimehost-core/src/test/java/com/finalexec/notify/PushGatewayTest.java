package com.finalexec.notify;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** N1: push delivery is background, bounded, and never changes what notify.send answers. */
class PushGatewayTest {

    private HttpServer server;
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile int failFirst = 0;

    private String start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n = hits.incrementAndGet();
            received.add(exchange.getRequestURI().getPath() + "|" + exchange.getRequestHeaders().getFirst("Authorization")
                    + "|" + exchange.getRequestHeaders().getFirst("Title") + "|" + body);
            exchange.sendResponseHeaders(n <= failFirst ? 500 : status, -1);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static PushGateway gateway(String url, String format) {
        return new PushGateway(url, "NPDEV_TEST_PUSH_KEY", format, "ride-", 20L,
                name -> "NPDEV_TEST_PUSH_KEY".equals(name) ? "k-secret" : null);
    }

    @Test
    void genericDeliversJsonWithTheBearerKey() throws Exception {
        PushGateway g = gateway(start() + "/v1/push", "generic");
        g.send("rider1", "Driver is coming", "ETA 3 min", "/x", "t1");
        String got = received.poll(5, TimeUnit.SECONDS);
        assertTrue(got.startsWith("/v1/push|Bearer k-secret|null|"), got);
        assertTrue(got.contains("\"to\":\"rider1\"") && got.contains("\"title\":\"Driver is coming\""), got);
    }

    @Test
    void ntfyPostsTextToTheTopicWithTheTitleHeader() throws Exception {
        PushGateway g = gateway(start(), "ntfy");
        g.send("rider1", "Driver is coming", "ETA 3 min", null, "t1");
        String got = received.poll(5, TimeUnit.SECONDS);
        assertEquals("/ride-rider1|Bearer k-secret|Driver is coming|ETA 3 min", got);
    }

    @Test
    void a500IsRetriedThenSucceedsAnd410IsNotRetried() throws Exception {
        failFirst = 2;
        PushGateway g = gateway(start() + "/p", "generic");
        g.send("a", "t", "b", null, "t1");
        for (int i = 0; i < 3; i++) {
            assertTrue(received.poll(5, TimeUnit.SECONDS) != null, "attempt " + (i + 1));
        }
        failFirst = 0;
        status = 410;
        int before = hits.get();
        g.send("b", "t", "b", null, "t1");
        assertTrue(received.poll(5, TimeUnit.SECONDS) != null);
        Thread.sleep(300);
        assertEquals(before + 1, hits.get(), "a 4xx is given up at once");
    }

    @Test
    void alwaysFailingIsGivenUpAfterThreeAttempts() throws Exception {
        status = 500;
        PushGateway g = gateway(start() + "/p", "generic");
        g.send("a", "t", "b", null, "t1");
        Thread.sleep(600);
        assertEquals(3, hits.get());
    }

    @Test
    void offWhenUrlIsEmptyAndTheStepResultIsIdenticalWithPushOnOrOff() throws Exception {
        assertTrue(!gateway("", "generic").enabled());
        PushGateway on = gateway(start() + "/p", "generic");
        Map<String, Object> state = Map.of("tenantId", "t1");
        CapabilityCall call = new CapabilityCall("notify", "NotifyCapability", "notify", "send",
                List.of("rider1", "Hello", "Body", Map.of(), "/x"));
        CapabilityResult off = new NotifyCapabilityHandler(new InMemoryNotificationStore(), () -> 1L).invoke(call, state);
        CapabilityResult withPush = new NotifyCapabilityHandler(new InMemoryNotificationStore(), () -> 1L, on).invoke(call, state);
        assertEquals(off.value(), withPush.value());
        assertTrue(withPush.ok());
        assertTrue(received.poll(5, TimeUnit.SECONDS) != null, "the push still went out in the background");
        assertNull(withPush.error());
    }
}
