package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.agent.AgentChannelsStarter;
import com.finalexec.agent.AgentConversationService;
import com.finalexec.agent.AgentLinkService;
import com.finalexec.agent.WhatsAppChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AGENT-1 (A10): the WhatsApp webhook door -- 404 while the channel is not running, Meta's GET
 * handshake, and the signature gate in front of every POST. The channel's own parsing/sending is
 * {@code WhatsAppChannelTest}'s job (runtimehost-core); here a status-only delivery is used so
 * nothing is ever sent anywhere.
 */
class AgentWhatsAppWebhookControllerTest {

    private static final String SECRET = "controller-test-secret";
    private static final byte[] STATUS_ONLY = """
            {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"42"},
              "statuses":[{"id":"wamid.out","status":"delivered"}]}}]}]}
            """.getBytes(StandardCharsets.UTF_8);

    private final AgentChannelsStarter starter = mock(AgentChannelsStarter.class);
    private final WhatsAppChannel channel = new WhatsAppChannel("42", "token", SECRET, "verify-me",
            new ObjectMapper(), mock(AgentLinkService.class), mock(AgentConversationService.class), "",
            "http://127.0.0.1:1/");
    private final AgentWhatsAppWebhookController controller = new AgentWhatsAppWebhookController(starter);

    @AfterEach
    void tearDown() {
        channel.stop();
    }

    @Test
    void everythingIs404WhileTheChannelIsNotRunning() {
        when(starter.whatsappChannel()).thenReturn(null);

        assertEquals(404, controller.verify("subscribe", "verify-me", "123").getStatusCode().value());
        assertEquals(404, controller.receive(post(STATUS_ONLY, sign(STATUS_ONLY))).getStatusCode().value());
    }

    @Test
    void handshakeEchoesTheChallengeAsPlainTextForTheRightToken() {
        when(starter.whatsappChannel()).thenReturn(channel);

        ResponseEntity<String> ok = controller.verify("subscribe", "verify-me", "1158201444");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(MediaType.TEXT_PLAIN, ok.getHeaders().getContentType());
        assertEquals("1158201444", ok.getBody());

        assertEquals(403, controller.verify("subscribe", "guess", "1158201444").getStatusCode().value());
    }

    @Test
    void deliveryWithoutAValidSignatureIsRefusedBeforeParsing() {
        when(starter.whatsappChannel()).thenReturn(channel);

        assertEquals(401, controller.receive(post(STATUS_ONLY, null)).getStatusCode().value());
        assertEquals(401, controller.receive(post(STATUS_ONLY, "sha256=" + "00".repeat(32))).getStatusCode().value());
        assertNull(channel.lastInboundAt(), "a refused delivery must never reach the channel");
    }

    @Test
    void signedDeliveryIsAcknowledgedAndHandedToTheChannel() {
        when(starter.whatsappChannel()).thenReturn(channel);

        assertEquals(200, controller.receive(post(STATUS_ONLY, sign(STATUS_ONLY))).getStatusCode().value());
        assertNotNull(channel.lastInboundAt());
    }

    private static MockHttpServletRequest post(byte[] body, String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/hooks/agent/whatsapp");
        request.setContent(body);
        if (signature != null) {
            request.addHeader("X-Hub-Signature-256", signature);
        }
        return request;
    }

    private static String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
