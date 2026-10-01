package com.finalexec.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * AGENT-1 (A7.2): long-polling Telegram bot. No public URL needed (works on a laptop). One daemon
 * thread polls; each update is handled on a small worker pool so one slow LLM call does not block
 * other users. See {@code helpers/agent/wire-formats.md} section 3 for the exact wire shapes.
 *
 * <p>Started by {@code AgentChannelsStarter} (A7.3) on {@code ApplicationReadyEvent} only when the
 * model enables {@code agentAccess.channels.telegram} AND {@code NPDEV_TELEGRAM_BOT_TOKEN} is set.
 * Stopped on context close.
 */
public final class TelegramChannel {

    private static final Logger LOG = Logger.getLogger(TelegramChannel.class.getName());
    private static final String DEFAULT_API_BASE = "https://api.telegram.org/bot";
    private static final int MAX_MESSAGES_PER_MINUTE = 20;

    private final String token;
    private final ObjectMapper mapper;
    private final AgentLinkService links;
    private final AgentConversationService conversations;
    private final String publicBaseUrl;
    private final String apiBase;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "npdev-telegram-worker");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Deque<Instant>> rate = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile String botUsername = "";
    private volatile Instant lastPollOk;

    public TelegramChannel(String token, ObjectMapper mapper, AgentLinkService links,
            AgentConversationService conversations, String publicBaseUrl) {
        this(token, mapper, links, conversations, publicBaseUrl, DEFAULT_API_BASE);
    }

    /** Full constructor: {@code apiBase} lets a test point this at a fake Telegram server. */
    public TelegramChannel(String token, ObjectMapper mapper, AgentLinkService links,
            AgentConversationService conversations, String publicBaseUrl, String apiBase) {
        this.token = token;
        this.mapper = mapper;
        this.links = links;
        this.conversations = conversations;
        this.publicBaseUrl = publicBaseUrl;
        this.apiBase = apiBase == null || apiBase.isBlank() ? DEFAULT_API_BASE : apiBase;
    }

    public void start() {
        JsonNode me = call("getMe", Map.of(), 15);
        if (me == null || !me.path("ok").asBoolean()) {
            LOG.warning("Telegram channel NOT started: getMe failed (is NPDEV_TELEGRAM_BOT_TOKEN right?)");
            return;
        }
        botUsername = me.path("result").path("username").asText("");
        call("deleteWebhook", Map.of("drop_pending_updates", false), 15);
        running = true;
        Thread poller = new Thread(this::pollLoop, "npdev-telegram-poller");
        poller.setDaemon(true);
        poller.start();
        LOG.info("Telegram channel connected as @" + botUsername);
    }

    public void stop() {
        running = false;
        workers.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }

    public String botUsername() {
        return botUsername;
    }

    public Instant lastPollOk() {
        return lastPollOk;
    }

    private void pollLoop() {
        long offset = 0;
        long backoffMs = 1000;
        while (running) {
            JsonNode response = call("getUpdates",
                    Map.of("offset", offset, "timeout", 50, "allowed_updates", List.of("message", "callback_query")), 60);
            if (response == null || !response.path("ok").asBoolean()) {
                int code = response == null ? 0 : response.path("error_code").asInt();
                if (code == 401) {
                    LOG.severe("Telegram: token rejected (401). Channel stopped.");
                    running = false;
                    return;
                }
                if (code == 409) {
                    LOG.warning("Telegram: 409 Conflict -- another copy of this app (or a webhook) is using the same bot token.");
                }
                long retryAfter = response == null ? 0 : response.path("parameters").path("retry_after").asLong(0) * 1000;
                sleep(Math.max(backoffMs, retryAfter));
                backoffMs = Math.min(backoffMs * 2, 60_000);
                continue;
            }
            backoffMs = 1000;
            lastPollOk = Instant.now();
            for (JsonNode update : response.path("result")) {
                offset = Math.max(offset, update.path("update_id").asLong() + 1);
                JsonNode copy = update;
                workers.submit(() -> safeHandle(copy));
            }
        }
    }

    private void safeHandle(JsonNode update) {
        try {
            if (update.has("callback_query")) {
                handleButton(update.path("callback_query"));
            } else if (update.has("message")) {
                handleMessage(update.path("message"));
            }
        } catch (RuntimeException failed) {
            LOG.warning("Telegram: handling an incoming bot event failed: " + failed.getClass().getSimpleName());
        }
    }

    private void handleMessage(JsonNode message) {
        if (!"private".equals(message.path("chat").path("type").asText())) {
            return;
        }
        long chatId = message.path("chat").path("id").asLong();
        String telegramUserId = message.path("from").path("id").asText();
        String text = message.path("text").asText("").trim();
        if (text.isEmpty()) {
            return;
        }
        if (!allow(chatId)) {
            send(chatId, "Too many messages -- please wait a minute.", null);
            return;
        }
        if (text.startsWith("/start ") || text.startsWith("/link ")) {
            String code = text.substring(text.indexOf(' ') + 1).trim();
            send(chatId, links.completeLink("telegram", telegramUserId, code), null);
            return;
        }
        if (text.equals("/unlink")) {
            boolean removed = links.unlink("telegram", telegramUserId);
            send(chatId, removed ? "Unlinked." : "Nothing to unlink.", null);
            return;
        }
        Optional<AgentLinkService.Speaker> linked = links.speakerFor("telegram", telegramUserId);
        if (linked.isEmpty()) {
            String where = publicBaseUrl == null || publicBaseUrl.isBlank()
                    ? "the app's 'Connect chat' page (agent-link.html)" : publicBaseUrl + "/agent-link.html";
            send(chatId, "Hi! I only talk to registered users. Sign in at " + where
                    + ", press 'Connect Telegram', then come back here.", null);
            return;
        }
        AgentConversationService.Speaker speaker = new AgentConversationService.Speaker(
                "telegram", telegramUserId, linked.get().tenantId(), linked.get().username(), linked.get().roles());
        if (text.equals("/whoami")) {
            send(chatId, "You are " + speaker.username() + " (roles: " + speaker.roles() + ").", null);
            return;
        }
        call("sendChatAction", Map.of("chat_id", chatId, "action", "typing"), 10);
        AgentConversationService.Reply reply = conversations.handleText(speaker, text);
        send(chatId, reply.text(), reply.confirmId());
    }

    private void handleButton(JsonNode query) {
        String data = query.path("data").asText("");
        long chatId = query.path("message").path("chat").path("id").asLong();
        long messageId = query.path("message").path("message_id").asLong();
        String telegramUserId = query.path("from").path("id").asText();
        call("answerCallbackQuery", Map.of("callback_query_id", query.path("id").asText()), 10);
        call("editMessageReplyMarkup", Map.of("chat_id", chatId, "message_id", messageId,
                "reply_markup", Map.of("inline_keyboard", List.of())), 10);
        Optional<AgentLinkService.Speaker> linked = links.speakerFor("telegram", telegramUserId);
        if (linked.isEmpty() || data.length() < 3) {
            return;
        }
        AgentConversationService.Speaker speaker = new AgentConversationService.Speaker(
                "telegram", telegramUserId, linked.get().tenantId(), linked.get().username(), linked.get().roles());
        boolean approved = data.startsWith("c:");
        AgentConversationService.Reply reply = conversations.handleConfirmation(speaker, data.substring(2), approved);
        send(chatId, reply.text(), reply.confirmId());
    }

    private void send(long chatId, String text, String confirmId) {
        String remaining = text == null ? "" : text;
        while (remaining.length() > 4000) {
            call("sendMessage", Map.of("chat_id", chatId, "text", remaining.substring(0, 4000)), 15);
            remaining = remaining.substring(4000);
        }
        if (confirmId == null) {
            call("sendMessage", Map.of("chat_id", chatId, "text", remaining), 15);
        } else {
            call("sendMessage", Map.of("chat_id", chatId, "text", remaining, "reply_markup", Map.of("inline_keyboard",
                    List.of(List.of(Map.of("text", "✅ Confirm", "callback_data", "c:" + confirmId),
                            Map.of("text", "✖ Cancel", "callback_data", "x:" + confirmId))))), 15);
        }
    }

    private boolean allow(long chatId) {
        Deque<Instant> window = rate.computeIfAbsent(chatId, k -> new ArrayDeque<>());
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

    /** Returns the parsed JSON (ok:false bodies included), or null on a transport failure. Never
     *  logs the token (it is part of the URL -- logs the method name only). */
    private JsonNode call(String method, Map<String, Object> body, int timeoutSeconds) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + token + "/" + method))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return mapper.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception failed) {
            LOG.fine("Telegram " + method + " failed: " + failed.getClass().getSimpleName());
            return null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
