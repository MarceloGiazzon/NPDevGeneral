package com.npdev.adapters.notification.inproc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InProcNotificationCapabilityAdapterTest {

    @Test
    void sendShouldReturnDeterministicEnvelope() {
        InProcNotificationCapabilityAdapter adapter = new InProcNotificationCapabilityAdapter();
        Object result = adapter.send(Map.of("email", "user@example.com", "message", "hello"));

        Map<?, ?> message = (Map<?, ?>) result;
        assertEquals("queued", message.get("status"));
        assertEquals("email", message.get("channel"));
        assertEquals("notification-inproc", message.get("adapterId"));
        assertEquals("user@example.com", message.get("email"));
        assertNotNull(message.get("deliveryId"));
    }
}
