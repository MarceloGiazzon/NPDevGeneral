package com.npdev.adapters.webhook.inproc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InProcWebhookCapabilityAdapterTest {

    @Test
    void postShouldReturnAcceptedEnvelope() {
        InProcWebhookCapabilityAdapter adapter = new InProcWebhookCapabilityAdapter();
        Object result = adapter.post(Map.of("expenseId", "exp-1", "approved", true));

        Map<?, ?> response = (Map<?, ?>) result;
        assertEquals("accepted", response.get("status"));
        assertEquals("exp-1", response.get("expenseId"));
        assertNotNull(response.get("requestId"));
    }
}
