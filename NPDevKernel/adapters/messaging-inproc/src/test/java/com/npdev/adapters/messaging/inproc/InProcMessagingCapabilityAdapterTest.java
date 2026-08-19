package com.npdev.adapters.messaging.inproc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcMessagingCapabilityAdapterTest {

    @Test
    void publishDeliversToEveryLocalSubscriberOfTheTopicSynchronously() {
        InProcMessagingCapabilityAdapter adapter = new InProcMessagingCapabilityAdapter();
        List<Map<String, Object>> receivedA = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> receivedB = new CopyOnWriteArrayList<>();
        adapter.subscribe("order.created", (Consumer<Map<String, Object>>) receivedA::add);
        adapter.subscribe("order.created", (Consumer<Map<String, Object>>) receivedB::add);
        adapter.subscribe("order.cancelled", (Consumer<Map<String, Object>>) m -> {
            throw new AssertionError("must not receive a message on a different topic");
        });

        Object ack = adapter.publish(Map.of("topic", "order.created", "orderId", "o-1"));

        assertTrue(ack instanceof Map<?, ?>);
        Map<?, ?> ackMap = (Map<?, ?>) ack;
        assertEquals("order.created", ackMap.get("topic"));
        assertEquals(2, ackMap.get("deliveredTo"));
        assertEquals(1, receivedA.size());
        assertEquals("o-1", receivedA.get(0).get("orderId"));
        assertEquals(1, receivedB.size());
    }

    @Test
    void unsubscribeStopsFurtherDelivery() {
        InProcMessagingCapabilityAdapter adapter = new InProcMessagingCapabilityAdapter();
        List<Object> received = new CopyOnWriteArrayList<>();
        Object subscriptionRef = adapter.subscribe("topic.x", (Consumer<Map<String, Object>>) received::add);

        adapter.publish(Map.of("topic", "topic.x", "n", 1));
        adapter.unsubscribe(subscriptionRef);
        adapter.publish(Map.of("topic", "topic.x", "n", 2));

        assertEquals(1, received.size());
    }

    @Test
    void publishRequiresANonBlankTopic() {
        InProcMessagingCapabilityAdapter adapter = new InProcMessagingCapabilityAdapter();
        assertThrows(IllegalArgumentException.class, () -> adapter.publish(Map.of("n", 1)));
    }

    @Test
    void invokeDispatchesPublishThroughTheCapabilityCallEntryPoint() {
        InProcMessagingCapabilityAdapter adapter = new InProcMessagingCapabilityAdapter();
        List<Object> received = new CopyOnWriteArrayList<>();
        adapter.subscribe("topic.y", (Consumer<Map<String, Object>>) received::add);

        CapabilityCall call = new CapabilityCall(
                "messaging", "MessagingCapability", "messaging-inproc", "publish",
                Map.of("topic", "topic.y", "n", 7));
        CapabilityResult result = adapter.invoke(call, Map.of());

        assertTrue(result.ok(), "expected the capability call to succeed: " + result);
        assertEquals(1, received.size());
    }

    @Test
    void invokeReturnsAStructuredFailureForSubscribeSinceAHandlerIsNotJsonRepresentable() {
        InProcMessagingCapabilityAdapter adapter = new InProcMessagingCapabilityAdapter();
        CapabilityCall call = new CapabilityCall(
                "messaging", "MessagingCapability", "messaging-inproc", "subscribe",
                List.of("topic.z"));

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertFalse(result.ok());
        assertEquals("MESSAGING_OPERATION_UNSUPPORTED_VIA_CAPABILITY_CALL", result.error().code());
    }
}
