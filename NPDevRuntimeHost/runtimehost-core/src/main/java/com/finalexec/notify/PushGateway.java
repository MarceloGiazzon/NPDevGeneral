package com.finalexec.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * N1: forwards an in-app notification to a push gateway, in the background, after it is stored. Never fails or slows
 * {@code notify.send}: delivery runs on its own daemon thread, retries a 5xx or an IO error 3 times (backoff, 2x
 * each), gives up on any 4xx, and logs ONE line per give-up. No redirects, a hard per-request deadline, and the
 * key is read from an environment variable whose NAME is configured -- never a value in a config file.
 *
 * <p>Formats: {@code generic} POSTs {@code {to, title, body, link, data}} as JSON to the URL; {@code ntfy} POSTs the
 * body as text to {@code <url>/<topicPrefix><to>} with the title in the {@code Title} header (ntfy.sh topics are
 * PUBLIC: anyone who knows the topic can read it -- use a long, unguessable prefix).
 */
public final class PushGateway {

    public static final String FORMAT_GENERIC = "generic";
    public static final String FORMAT_NTFY = "ntfy";
    private static final int ATTEMPTS = 3;

    private final String url;
    private final String keyEnv;
    private final String format;
    private final String topicPrefix;
    private final long backoffMillis;
    private final Function<String, String> env;
    private final HttpClient client;
    private final ExecutorService executor;
    private final ObjectMapper mapper = new ObjectMapper();

    public PushGateway(String url, String keyEnv, String format, String topicPrefix, long backoffMillis,
                       Function<String, String> env) {
        this.url = url == null ? "" : url.trim();
        this.keyEnv = keyEnv == null ? "" : keyEnv.trim();
        this.format = FORMAT_NTFY.equalsIgnoreCase(format) ? FORMAT_NTFY : FORMAT_GENERIC;
        this.topicPrefix = topicPrefix == null ? "" : topicPrefix;
        this.backoffMillis = Math.max(1, backoffMillis);
        this.env = env == null ? System::getenv : env;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "npdev-push");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean enabled() {
        return !url.isEmpty();
    }

    /** Queue one push; returns immediately. */
    public void send(String recipient, String title, String body, String link, String tenant) {
        if (!enabled()) {
            return;
        }
        executor.execute(() -> deliver(recipient, title, body, link, tenant));
    }

    private void deliver(String recipient, String title, String body, String link, String tenant) {
        HttpRequest request;
        try {
            request = buildRequest(recipient, title, body, link, tenant);
        } catch (RuntimeException | JsonProcessingException e) {
            System.err.println("npdev-push: gave up for " + recipient + " (cannot build request: " + e.getMessage() + ")");
            return;
        }
        long wait = backoffMillis;
        String last = "";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return;
                }
                last = "HTTP " + status;
                if (status < 500) {
                    break; // 4xx (a gone device, a bad key): retrying cannot help
                }
            } catch (IOException e) {
                last = e.getClass().getSimpleName();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (attempt < ATTEMPTS) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                wait *= 2;
            }
        }
        System.err.println("npdev-push: gave up for " + recipient + " (" + last + ")");
    }

    private HttpRequest buildRequest(String recipient, String title, String body, String link, String tenant)
            throws JsonProcessingException {
        HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(Duration.ofSeconds(10));
        String key = keyEnv.isEmpty() ? null : env.apply(keyEnv);
        if (key != null && !key.isBlank()) {
            builder.header("Authorization", "Bearer " + key.trim());
        }
        if (FORMAT_NTFY.equals(format)) {
            String topic = URLEncoder.encode(topicPrefix + recipient, StandardCharsets.UTF_8);
            builder.uri(URI.create(url.replaceAll("/+$", "") + "/" + topic))
                    .header("Title", asciiHeader(title))
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? title : body, StandardCharsets.UTF_8));
            if (link != null) {
                builder.header("Click", link);
            }
        } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("to", recipient);
            payload.put("title", title);
            payload.put("body", body);
            payload.put("link", link);
            payload.put("data", Map.of("tenant", tenant == null ? "" : tenant));
            builder.uri(URI.create(url)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    /** HTTP headers are not the place for non-ASCII or line breaks. */
    private static String asciiHeader(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").replaceAll("[^\\x20-\\x7E]", "?");
    }
}
