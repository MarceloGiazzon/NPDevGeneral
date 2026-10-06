package com.finalexec.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * AGENT-1 (A10): WhatsApp Cloud API channel -- the second adapter next to {@link TelegramChannel}
 * over the same channel-agnostic {@link AgentConversationService}. Unlike Telegram there is no
 * polling: Meta POSTs every message to {@code /api/hooks/agent/whatsapp}
 * ({@code com.finalexec.api.AgentWhatsAppWebhookController}), which needs a public HTTPS address --
 * the NPDev Manager's tunnel gives one in dev. See {@code helpers/agent/wire-formats.md} section 4.
 *
 * <p>The controller calls {@link #signatureValid} on the RAW body before anything is parsed, then
 * {@link #accept}, which answers immediately and handles each message on a small worker pool (Meta
 * retries a delivery that is not acknowledged within a few seconds, and an LLM round trip is
 * slower than that). Retried deliveries are dropped by message id.
 *
 * <p>Started by {@code AgentChannelsStarter} only when the model enables
 * {@code agentAccess.channels.whatsapp} AND all four {@code NPDEV_WHATSAPP_*} secrets are set.
 */
public final class WhatsAppChannel {

    private static final Logger LOG = Logger.getLogger(WhatsAppChannel.class.getName());
    private static final String DEFAULT_API_BASE = "https://graph.facebook.com/v21.0/";
    private static final int MAX_MESSAGES_PER_MINUTE = 20;
    /** Cloud API text limit is 4096; split a little below it, as TelegramChannel does. */
    private static final int MAX_TEXT = 4000;
    /** An interactive message's body.text is capped at 1024 characters. */
    private static final int MAX_BUTTON_BODY = 1024;
    private static final int SEEN_IDS_KEPT = 1000;
    /** Meta's challenge is a number; anything else is never echoed back. */
    private static final Pattern CHALLENGE = Pattern.compile("[0-9A-Za-z_-]{1,128}");

    /** One incoming message: free text, a Confirm/Cancel button press, or neither (an image, a
     *  sticker...) -- both null. */
    record Inbound(String from, String messageId, String text, String buttonId) {
    }

    private final String phoneNumberId;
    private final String accessToken;
    private final byte[] appSecret;
    private final String verifyToken;
    private final ObjectMapper mapper;
    private final AgentLinkService links;
    private final AgentConversationService conversations;
    private final String publicBaseUrl;
    private final String apiBase;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "npdev-whatsapp-worker");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Deque<Instant>> rate = new ConcurrentHashMap<>();
    private final Map<String, Boolean> seenIds = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_IDS_KEPT;
        }
    };
    private volatile Instant lastInboundAt;
    private volatile Boolean lastSendOk;
    private volatile boolean tokenRejectedLogged;

    public WhatsAppChannel(String phoneNumberId, String accessToken, String appSecret, String verifyToken,
            ObjectMapper mapper, AgentLinkService links, AgentConversationService conversations, String publicBaseUrl) {
        this(phoneNumberId, accessToken, appSecret, verifyToken, mapper, links, conversations, publicBaseUrl,
                DEFAULT_API_BASE);
    }

    /** Full constructor: {@code apiBase} lets a test point this at a fake Graph API server. */
    public WhatsAppChannel(String phoneNumberId, String accessToken, String appSecret, String verifyToken,
            ObjectMapper mapper, AgentLinkService links, AgentConversationService conversations, String publicBaseUrl,
            String apiBase) {
        this.phoneNumberId = phoneNumberId;
        this.accessToken = accessToken;
        this.appSecret = appSecret.getBytes(StandardCharsets.UTF_8);
        this.verifyToken = verifyToken;
        this.mapper = mapper;
        this.links = links;
        this.conversations = conversations;
        this.publicBaseUrl = publicBaseUrl;
        String base = apiBase == null || apiBase.isBlank() ? DEFAULT_API_BASE : apiBase;
        this.apiBase = base.endsWith("/") ? base : base + "/";
    }

    public void stop() {
        workers.shutdownNow();
    }

    /** When Meta last delivered anything to the webhook (null = never since boot). */
    public Instant lastInboundAt() {
        return lastInboundAt;
    }

    /** Whether the most recent send to the Graph API succeeded (null = nothing sent yet). */
    public Boolean lastSendOk() {
        return lastSendOk;
    }

    /**
     * The webhook subscription handshake: {@code GET ?hub.mode=subscribe&hub.verify_token=..&hub.challenge=..}.
     * Returns the challenge to echo back as plain text, or null (answer 403).
     */
    public String verifyChallenge(String mode, String token, String challenge) {
        if (!"subscribe".equals(mode) || token == null || challenge == null || !CHALLENGE.matcher(challenge).matches()) {
            return null;
        }
        return MessageDigest.isEqual(verifyToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))
                ? challenge : null;
    }

    /** {@code X-Hub-Signature-256: sha256=<hex>} = HMAC-SHA256(app secret, raw body), compared in
     *  constant time. Must run before the body is parsed. */
    public boolean signatureValid(byte[] rawBody, String header) {
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(header.substring("sha256=".length()).trim());
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret, "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(rawBody == null ? new byte[0] : rawBody), presented);
        } catch (Exception failed) {
            return false;
        }
    }

    /** Queues every new message in a signature-verified body; returns how many were queued. */
    public int accept(byte[] rawBody) {
        lastInboundAt = Instant.now();
        int queued = 0;
        for (Inbound message : parse(rawBody)) {
            if (firstSighting(message.messageId())) {
                workers.submit(() -> safeHandle(message));
                queued++;
            }
        }
        return queued;
    }

    /** {@code entry[].changes[].value.messages[]}, skipping deliveries addressed to another phone
     *  number of the same Meta app and status callbacks (sent/delivered/read carry no messages). */
    List<Inbound> parse(byte[] rawBody) {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception notJson) {
            return List.of();
        }
        List<Inbound> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                String addressedTo = value.path("metadata").path("phone_number_id").asText("");
                if (!addressedTo.isEmpty() && !addressedTo.equals(phoneNumberId)) {
                    continue;
                }
                for (JsonNode message : value.path("messages")) {
                    String from = message.path("from").asText("");
                    if (from.isEmpty()) {
                        continue;
                    }
                    String id = message.path("id").asText("");
                    String type = message.path("type").asText("");
                    String text = null;
                    String buttonId = null;
                    if ("text".equals(type)) {
                        text = message.path("text").path("body").asText("");
                    } else if ("interactive".equals(type)
                            && "button_reply".equals(message.path("interactive").path("type").asText())) {
                        buttonId = message.path("interactive").path("button_reply").path("id").asText("");
                    }
                    out.add(new Inbound(from, id, text, buttonId));
                }
            }
        }
        return out;
    }

    private boolean firstSighting(String messageId) {
        if (messageId == null || messageId.isEmpty()) {
            return true;
        }
        synchronized (seenIds) {
            return seenIds.put(messageId, Boolean.TRUE) == null;
        }
    }

    private void safeHandle(Inbound message) {
        try {
            handle(message);
        } catch (RuntimeException failed) {
            LOG.warning("WhatsApp: handling an incoming message failed: " + failed.getClass().getSimpleName());
        }
    }

    /** Package-private so a test can drive one message synchronously. */
    void handle(Inbound message) {
        String from = message.from();
        if (message.buttonId() != null) {
            handleButton(from, message.buttonId());
            return;
        }
        if (message.text() == null) {
            send(from, "I can only read text messages.", null);
            return;
        }
        String text = message.text().trim();
        if (text.isEmpty()) {
            return;
        }
        if (!allow(from)) {
            send(from, "Too many messages -- please wait a minute.", null);
            return;
        }
        // Telegram's slash commands, minus the slash: WhatsApp has no command menu, and a plain
        // "link ABCD1234" is what agent-link.html tells the user to send.
        String command = text.startsWith("/") ? text.substring(1) : text;
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.startsWith("link ") || lower.startsWith("start ")) {
            String code = command.substring(command.indexOf(' ') + 1).trim();
            send(from, links.completeLink("whatsapp", from, code), null);
            return;
        }
        if (lower.equals("unlink")) {
            boolean removed = links.unlink("whatsapp", from);
            send(from, removed ? "Unlinked." : "Nothing to unlink.", null);
            return;
        }
        Optional<AgentLinkService.Speaker> linked = links.speakerFor("whatsapp", from);
        if (linked.isEmpty()) {
            String where = publicBaseUrl == null || publicBaseUrl.isBlank()
                    ? "the app's 'Connect chat' page (agent-link.html)" : publicBaseUrl + "/agent-link.html";
            send(from, "Hi! I only talk to registered users. Sign in at " + where
                    + ", press 'Connect WhatsApp', then send me the 'link ...' message it shows.", null);
            return;
        }
        AgentConversationService.Speaker speaker = speaker(from, linked.get());
        if (lower.equals("whoami")) {
            send(from, "You are " + speaker.username() + " (roles: " + speaker.roles() + ").", null);
            return;
        }
        AgentConversationService.Reply reply = conversations.handleText(speaker, lower.equals("reset") ? "/reset" : text);
        send(from, reply.text(), reply.confirmId());
    }

    private void handleButton(String from, String buttonId) {
        Optional<AgentLinkService.Speaker> linked = links.speakerFor("whatsapp", from);
        if (linked.isEmpty() || buttonId.length() < 3) {
            return;
        }
        boolean approved = buttonId.startsWith("c:");
        AgentConversationService.Reply reply = conversations.handleConfirmation(
                speaker(from, linked.get()), buttonId.substring(2), approved);
        send(from, reply.text(), reply.confirmId());
    }

    private static AgentConversationService.Speaker speaker(String from, AgentLinkService.Speaker linked) {
        return new AgentConversationService.Speaker("whatsapp", from, linked.tenantId(), linked.username(), linked.roles());
    }

    private void send(String to, String text, String confirmId) {
        String body = text == null ? "" : text;
        if (confirmId == null) {
            sendTextChunks(to, body);
            return;
        }
        if (body.length() > MAX_BUTTON_BODY) {
            // Too long for a button message's body: send the description as text, then a short
            // prompt carrying the buttons.
            sendTextChunks(to, body);
            body = "Confirm?";
        }
        postMessage(buttonMessage(to, body, confirmId));
    }

    private void sendTextChunks(String to, String text) {
        String remaining = text;
        while (remaining.length() > MAX_TEXT) {
            postMessage(textMessage(to, remaining.substring(0, MAX_TEXT)));
            remaining = remaining.substring(MAX_TEXT);
        }
        postMessage(textMessage(to, remaining));
    }

    static Map<String, Object> textMessage(String to, String body) {
        return Map.of("messaging_product", "whatsapp", "to", to, "type", "text", "text", Map.of("body", body));
    }

    static Map<String, Object> buttonMessage(String to, String body, String confirmId) {
        return Map.of("messaging_product", "whatsapp", "to", to, "type", "interactive",
                "interactive", Map.of("type", "button", "body", Map.of("text", body),
                        "action", Map.of("buttons", List.of(
                                Map.of("type", "reply", "reply", Map.of("id", "c:" + confirmId, "title", "Confirm")),
                                Map.of("type", "reply", "reply", Map.of("id", "x:" + confirmId, "title", "Cancel"))))));
    }

    private boolean allow(String from) {
        Deque<Instant> window = rate.computeIfAbsent(from, k -> new ArrayDeque<>());
        synchronized (window) {
            Instant cutoff = Instant.now().minusSeconds(60);
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= MAX_MESSAGES_PER_MINUTE) {
                return false;
            }
            window.addLast(Instant.now());
            return true;
        }
    }

    /** POSTs one message. Never logs the access token, the recipient, or the text. */
    private void postMessage(Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + phoneNumberId + "/messages"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            lastSendOk = status >= 200 && status < 300;
            if (status == 401 && !tokenRejectedLogged) {
                tokenRejectedLogged = true;
                LOG.severe("WhatsApp: access token rejected (401) -- check NPDEV_WHATSAPP_ACCESS_TOKEN.");
            } else if (!lastSendOk) {
                LOG.warning("WhatsApp: sending a message failed with HTTP " + status + ".");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            lastSendOk = false;
        } catch (Exception failed) {
            lastSendOk = false;
            LOG.fine("WhatsApp send failed: " + failed.getClass().getSimpleName());
        }
    }
}
