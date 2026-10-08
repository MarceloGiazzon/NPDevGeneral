package com.finalexec.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P8 (G5): a photo sent to the Telegram bot reaches {@link AgentConversationService#handlePhoto} --
 * the largest size within the intake field's limit is chosen, fetched with getFile + the file
 * endpoint, and the Confirm button carries the photo's confirm id. Fake Bot API on a local HttpServer.
 */
class TelegramChannelPhotoTest {

    private static final String TOKEN = "123:abc";
    private static final byte[] PHOTO = {(byte) 0xFF, (byte) 0xD8, 7, 7, 7};

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> fileIdsRequested = new CopyOnWriteArrayList<>();
    private final List<JsonNode> sent = new CopyOnWriteArrayList<>();
    private final AtomicBoolean delivered = new AtomicBoolean();
    private HttpServer bot;
    private TelegramChannel channel;
    private AgentConversationService conversations;

    @BeforeEach
    void setUp() throws Exception {
        bot = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bot.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] request = exchange.getRequestBody().readAllBytes();
            byte[] answer;
            if (path.equals("/file/bot" + TOKEN + "/photos/big.jpg")) {
                answer = PHOTO;
            } else if (path.endsWith("/getMe")) {
                answer = json("{\"ok\":true,\"result\":{\"username\":\"capbot\"}}");
            } else if (path.endsWith("/getUpdates")) {
                answer = json(delivered.getAndSet(true) ? "{\"ok\":true,\"result\":[]}" : """
                    {"ok":true,"result":[{"update_id":5,"message":{"message_id":9,
                      "chat":{"id":77,"type":"private"},"from":{"id":42},"caption":"Lech",
                      "photo":[{"file_id":"small","file_size":9000},{"file_id":"big","file_size":150000},
                               {"file_id":"huge","file_size":900000}]}}]}""");
                if (delivered.get()) {
                    sleepQuietly();
                }
            } else if (path.endsWith("/getFile")) {
                fileIdsRequested.add(mapper.readTree(request).path("file_id").asText());
                answer = json("{\"ok\":true,\"result\":{\"file_path\":\"photos/big.jpg\"}}");
            } else {
                if (path.endsWith("/sendMessage")) {
                    sent.add(mapper.readTree(request));
                }
                answer = json("{\"ok\":true,\"result\":{}}");
            }
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        bot.start();
        AgentLinkService links = mock(AgentLinkService.class);
        when(links.speakerFor("telegram", "42"))
                .thenReturn(Optional.of(new AgentLinkService.Speaker("dev", "tavo", Set.of("MEMBER"))));
        conversations = mock(AgentConversationService.class);
        when(conversations.photoMaxBytes()).thenReturn(204800L);
        when(conversations.handlePhoto(any(), any(), any(), any(), any()))
                .thenReturn(new AgentConversationService.Reply("New Beer cap from your photo", "ph-1234"));
        channel = new TelegramChannel(TOKEN, mapper, links, conversations, "",
                "http://127.0.0.1:" + bot.getAddress().getPort() + "/bot");
    }

    @AfterEach
    void tearDown() {
        channel.stop();
        bot.stop(0);
    }

    @Test
    void photoIsDownloadedAtTheLargestSizeThatFitsAndOfferedForConfirm() throws Exception {
        channel.start();

        AgentConversationService.Speaker tavo =
                new AgentConversationService.Speaker("telegram", "42", "dev", "tavo", Set.of("MEMBER"));
        verify(conversations, timeout(5000)).handlePhoto(eq(tavo), eq(PHOTO), eq("image/jpeg"), any(), eq("Lech"));
        assertEquals(List.of("big"), fileIdsRequested, "150 KB fits the 200 KB field limit; 900 KB does not");

        // handlePhoto returning is not the reply being sent -- wait for the sendMessage itself.
        long deadline = System.currentTimeMillis() + 5000;
        Optional<JsonNode> sentReply = Optional.empty();
        while (sentReply.isEmpty() && System.currentTimeMillis() < deadline) {
            sentReply = sent.stream().filter(m -> m.path("text").asText().startsWith("New Beer cap")).findFirst();
            Thread.sleep(20);
        }
        JsonNode reply = sentReply.orElseThrow();
        assertEquals("c:ph-1234", reply.at("/reply_markup/inline_keyboard/0/0/callback_data").asText());
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
