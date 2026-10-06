package com.finalexec.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AGENT-1 (A10): the WhatsApp Cloud API adapter against a fake Graph API (a local HttpServer that
 * records every send) -- the subscription handshake, the signature gate, webhook parsing, and each
 * conversation path's outgoing body shape. Wire shapes: {@code helpers/agent/wire-formats.md} section 4.
 */
class WhatsAppChannelTest {

    private static final String PHONE_ID = "1029384756";
    private static final String SECRET = "app-secret-for-tests";
    private static final String USER = "5511999999999";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> sent = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private HttpServer graph;
    private AgentLinkService links;
    private AgentConversationService conversations;
    private WhatsAppChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        graph = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        graph.createContext("/v21.0/" + PHONE_ID + "/messages", exchange -> {
            sent.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] ok = "{\"messages\":[{\"id\":\"wamid.out\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        graph.start();
        links = mock(AgentLinkService.class);
        conversations = mock(AgentConversationService.class);
        channel = new WhatsAppChannel(PHONE_ID, "access-token", SECRET, "my-verify-token", mapper, links,
                conversations, "https://shop.example.com",
                "http://127.0.0.1:" + graph.getAddress().getPort() + "/v21.0/");
    }

    @AfterEach
    void tearDown() {
        channel.stop();
        graph.stop(0);
    }

    @Test
    void subscriptionHandshakeEchoesChallengeOnlyForTheRightToken() {
        assertEquals("1158201444", channel.verifyChallenge("subscribe", "my-verify-token", "1158201444"));
        assertNull(channel.verifyChallenge("subscribe", "wrong-token", "1158201444"));
        assertNull(channel.verifyChallenge("unsubscribe", "my-verify-token", "1158201444"));
        assertNull(channel.verifyChallenge("subscribe", null, "1158201444"));
        assertNull(channel.verifyChallenge("subscribe", "my-verify-token", "<script>alert(1)</script>"),
                "a challenge that is not a plain token is never echoed back");
    }

    @Test
    void signatureIsHmacSha256OfTheRawBody() throws Exception {
        byte[] body = textDelivery("wamid.1", "hello").getBytes(StandardCharsets.UTF_8);
        assertTrue(channel.signatureValid(body, sign(body, SECRET)));
        assertFalse(channel.signatureValid(body, sign(body, "another-secret")));
        assertFalse(channel.signatureValid(body, sign("tampered".getBytes(StandardCharsets.UTF_8), SECRET)));
        assertFalse(channel.signatureValid(body, null));
        assertFalse(channel.signatureValid(body, "sha1=abcdef"));
        assertFalse(channel.signatureValid(body, "sha256=not-hex"));
    }

    @Test
    void parsesTextAndButtonRepliesAndSkipsStatusesAndOtherNumbers() {
        String body = """
                {"object":"whatsapp_business_account","entry":[{"changes":[
                  {"value":{"metadata":{"phone_number_id":"%s"},"messages":[
                    {"from":"%s","id":"wamid.t","type":"text","text":{"body":"how many pigments?"}},
                    {"from":"%s","id":"wamid.b","type":"interactive",
                     "interactive":{"type":"button_reply","button_reply":{"id":"c:p-81f2","title":"Confirm"}}},
                    {"from":"%s","id":"wamid.i","type":"image","image":{"id":"media-1"}}]}},
                  {"value":{"metadata":{"phone_number_id":"%s"},"statuses":[{"id":"wamid.out","status":"read"}]}},
                  {"value":{"metadata":{"phone_number_id":"someone-else"},"messages":[
                    {"from":"%s","id":"wamid.x","type":"text","text":{"body":"not for us"}}]}}]}]}
                """.formatted(PHONE_ID, USER, USER, USER, PHONE_ID, USER);

        List<WhatsAppChannel.Inbound> messages = channel.parse(body.getBytes(StandardCharsets.UTF_8));

        assertEquals(3, messages.size());
        assertEquals(new WhatsAppChannel.Inbound(USER, "wamid.t", "how many pigments?", null), messages.get(0));
        assertEquals(new WhatsAppChannel.Inbound(USER, "wamid.b", null, "c:p-81f2"), messages.get(1));
        assertEquals(new WhatsAppChannel.Inbound(USER, "wamid.i", null, null), messages.get(2));
        assertEquals(List.of(), channel.parse("not json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void retriedDeliveryIsQueuedOnlyOnce() {
        byte[] body = textDelivery("wamid.retry", "hi").getBytes(StandardCharsets.UTF_8);
        assertEquals(1, channel.accept(body));
        assertEquals(0, channel.accept(body), "Meta re-sends an unacknowledged delivery with the same message id");
        assertTrue(channel.lastInboundAt() != null);
    }

    @Test
    void linkMessageCompletesTheLinkForThisPhone() {
        when(links.completeLink("whatsapp", USER, "AB7K2QXM")).thenReturn("Connected! You are bernard.");

        channel.handle(new WhatsAppChannel.Inbound(USER, "wamid.1", "link AB7K2QXM", null));

        assertEquals(1, sent.size());
        JsonNode message = sent.get(0);
        assertEquals("whatsapp", message.path("messaging_product").asText());
        assertEquals(USER, message.path("to").asText());
        assertEquals("text", message.path("type").asText());
        assertEquals("Connected! You are bernard.", message.path("text").path("body").asText());
        assertEquals("Bearer access-token", authHeaders.get(0));
        assertEquals(Boolean.TRUE, channel.lastSendOk());
    }

    @Test
    void unlinkedSenderIsPointedAtTheConnectPageAndNeverReachesTheAssistant() {
        when(links.speakerFor("whatsapp", USER)).thenReturn(Optional.empty());

        channel.handle(new WhatsAppChannel.Inbound(USER, "wamid.1", "show me all orders", null));

        assertTrue(sent.get(0).path("text").path("body").asText().contains("https://shop.example.com/agent-link.html"));
        verify(conversations, never()).handleText(any(), any());
    }

    @Test
    void linkedTextGoesToTheAssistantAsThisUserAndAWriteComesBackWithButtons() {
        when(links.speakerFor("whatsapp", USER))
                .thenReturn(Optional.of(new AgentLinkService.Speaker("t1", "bernard", Set.of("Customer"))));
        when(conversations.handleText(eq(new AgentConversationService.Speaker(
                "whatsapp", USER, "t1", "bernard", Set.of("Customer"))), eq("cancel order 12")))
                .thenReturn(new AgentConversationService.Reply("I am about to: Cancel order 12\nConfirm?", "p-81f2"));

        channel.handle(new WhatsAppChannel.Inbound(USER, "wamid.1", "cancel order 12", null));

        JsonNode interactive = sent.get(0).path("interactive");
        assertEquals("interactive", sent.get(0).path("type").asText());
        assertEquals("button", interactive.path("type").asText());
        assertEquals("I am about to: Cancel order 12\nConfirm?", interactive.path("body").path("text").asText());
        JsonNode buttons = interactive.path("action").path("buttons");
        assertEquals("c:p-81f2", buttons.get(0).path("reply").path("id").asText());
        assertEquals("x:p-81f2", buttons.get(1).path("reply").path("id").asText());
        assertTrue(buttons.get(0).path("reply").path("title").asText().length() <= 20);
    }

    @Test
    void buttonPressConfirmsOrCancelsThePendingWrite() {
        AgentLinkService.Speaker linked = new AgentLinkService.Speaker("t1", "bernard", Set.of("Customer"));
        AgentConversationService.Speaker speaker = new AgentConversationService.Speaker(
                "whatsapp", USER, "t1", "bernard", Set.of("Customer"));
        when(links.speakerFor("whatsapp", USER)).thenReturn(Optional.of(linked));
        when(conversations.handleConfirmation(speaker, "p-81f2", true))
                .thenReturn(new AgentConversationService.Reply("Order 12 cancelled.", null));
        when(conversations.handleConfirmation(speaker, "p-99aa", false))
                .thenReturn(new AgentConversationService.Reply("OK, not done.", null));

        channel.handle(new WhatsAppChannel.Inbound(USER, "wamid.1", null, "c:p-81f2"));
        channel.handle(new WhatsAppChannel.Inbound(USER, "wamid.2", null, "x:p-99aa"));

        assertEquals("Order 12 cancelled.", sent.get(0).path("text").path("body").asText());
        assertEquals("OK, not done.", sent.get(1).path("text").path("body").asText());
    }

    @Test
    void confirmTextTooLongForAButtonBodyIsSentFirstAsText() {
        when(links.speakerFor("whatsapp", USER))
                .thenReturn(Optional.of(new AgentLinkService.Speaker("t1", "bernard", Set.of())));
        String longDescription = "I am about to: Update stock\n" + "x".repeat(1500);
        when(conversations.handleText(any(), eq("set stock")))
                .thenReturn(new AgentConversationService.Reply(longDescription, "p-1"));

        channel.handle(new WhatsAppChannel.Inbound(USER, "wamid.1", "set stock", null));

        assertEquals(2, sent.size());
        assertEquals(longDescription, sent.get(0).path("text").path("body").asText());
        assertEquals("Confirm?", sent.get(1).path("interactive").path("body").path("text").asText());
    }

    @Test
    void nonTextMessageGetsAPlainExplanation() {
        channel.handle(new WhatsAppChannel.Inbound(USER, "wamid.1", null, null));

        assertEquals("I can only read text messages.", sent.get(0).path("text").path("body").asText());
        verify(links, never()).speakerFor(any(), any());
    }

    private static String textDelivery(String id, String text) {
        return """
                {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"%s"},
                  "messages":[{"from":"%s","id":"%s","type":"text","text":{"body":"%s"}}]}}]}]}
                """.formatted(PHONE_ID, USER, id, text);
    }

    private static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
