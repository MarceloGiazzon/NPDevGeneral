package com.npdev.adapters.notification.inproc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InProcWarningNotificationCapabilityAdapterTest {

    @Test
    void sendShouldReturnWarningEnvelope() {
        InProcWarningNotificationCapabilityAdapter adapter = new InProcWarningNotificationCapabilityAdapter();
        Object result = adapter.send(Map.of("email", "user@example.com", "message", "hello"));

        Map<?, ?> message = (Map<?, ?>) result;
        assertEquals("warning", message.get("status"));
        assertEquals("email-warning", message.get("channel"));
        assertEquals("notification-warning-inproc", message.get("adapterId"));
        assertEquals("warning", message.get("pluginProfile"));
        assertEquals("user@example.com", message.get("email"));
        assertNotNull(message.get("deliveryId"));
    }
}
