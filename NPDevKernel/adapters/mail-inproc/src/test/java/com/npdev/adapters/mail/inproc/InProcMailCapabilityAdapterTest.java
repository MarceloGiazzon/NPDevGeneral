package com.npdev.adapters.mail.inproc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.MailMessage;
import com.npdev.kernel.ports.MailSendResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcMailCapabilityAdapterTest {

    @Test
    void sendRendersTemplateAndRecordsDelivery() {
        InProcMailCapabilityAdapter adapter = new InProcMailCapabilityAdapter();
        MailSendResult result = adapter.send(new MailMessage(
                List.of("ada@example.com"),
                "Welcome, ${name}",
                "Hello ${name}, your order ${orderId} shipped.",
                Map.of("name", "Ada", "orderId", "A-42")
        ));

        assertEquals("sent", result.status());
        assertEquals("mail-inproc", result.provider());
        assertNotNull(result.deliveryId());

        assertEquals(1, adapter.deliveries().size());
        Map<String, Object> delivery = adapter.deliveries().get(0);
        assertEquals("Welcome, Ada", delivery.get("subject"));
        assertEquals("Hello Ada, your order A-42 shipped.", delivery.get("body"));
        assertEquals(List.of("ada@example.com"), delivery.get("to"));
    }

    @Test
    void invokeViaCapabilityCallParsesMapPayload() {
        InProcMailCapabilityAdapter adapter = new InProcMailCapabilityAdapter();
        CapabilityCall call = new CapabilityCall(
                "mail",
                "EmailCapability",
                "mail-inproc",
                "send",
                List.of(Map.of(
                        "to", "bob@example.com",
                        "subject", "Hi ${name}",
                        "body", "Body ${name}",
                        "templateVars", Map.of("name", "Bob")
                ))
        );

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertTrue(result.ok());
        Map<?, ?> value = (Map<?, ?>) result.value();
        assertEquals("Hi Bob", value.get("subject"));
        assertEquals("Body Bob", value.get("body"));
    }

    @Test
    void invokeRejectsUnsupportedOperation() {
        InProcMailCapabilityAdapter adapter = new InProcMailCapabilityAdapter();
        CapabilityCall call = new CapabilityCall("mail", "EmailCapability", "mail-inproc", "delete", List.of());

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertTrue(!result.ok());
    }
}
