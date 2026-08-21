package com.npdev.adapters.webhook.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * R6.1: a real outbound webhook adapter, cloning {@code HttpExternalAiCapabilityAdapter}'s proven
 * posture rather than inventing a new one -- fail-closed destination allowlist, per-request
 * timeout, bounded adapter-local retry, all in the same shape as that sibling.
 *
 * <p><b>Fail-closed, same as the port's own default:</b> a call whose URL's host has no configured
 * {@link WebhookDestinationProfile} -- including the "no allowlist configured at all" case -- is
 * denied with a {@link WebhookEgressDeniedException} naming why. No real network call happens until
 * both an allowlisted host AND a real HMAC secret exist. This is the SSRF guard: the URL a caller
 * passes (typically read back from application data, e.g. a saved subscription record) is never
 * trusted by itself.
 *
 * <p><b>Timeout + bounded retry, the RUN-4 (R8d) way.</b> A per-request deadline
 * ({@link HttpRequest.Builder#timeout}) applies regardless of which {@link HttpClient} sends the
 * request, plus an adapter-local retry loop in {@link #send} that retries only transport-level
 * failures ({@link IOException}, which covers a timed-out request) and 429/5xx responses -- never a
 * deny or a 4xx contract failure, neither of which a retry can fix. See
 * {@code ledger/items/RUN-4.yml} for why this lives here rather than in
 * {@code CapabilityExecutionPolicy}.
 *
 * <p><b>HMAC request signing.</b> Every outbound body is signed with HMAC-SHA256 using a secret
 * resolved at call time from an environment variable named by the matched
 * {@link WebhookDestinationProfile} -- never a literal in code, never defaulted, never committed.
 * The signature travels in {@code X-Npdev-Webhook-Signature: sha256=<hex>} so the receiver can
 * verify the body was not forged or altered in transit.
 */
public final class HttpWebhookCapabilityAdapter implements CapabilityAdapter {

    /** R8d (RUN-4) posture: connect timeout for the HttpClient this adapter builds itself. */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Per-request deadline, applied via {@link HttpRequest.Builder#timeout} regardless of which
     * HttpClient sends it. Shorter than {@code external-ai-http}'s 120s default on purpose -- a
     * webhook receiver is expected to acknowledge quickly, not run a long-lived generation.
     */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Bounded retry, adapter-local, for transport failures and 429/5xx only -- see {@link #send}. */
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofMillis(500);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_HEADER = "X-Npdev-Webhook-Signature";
    private static final String DELIVERY_ID_HEADER = "X-Npdev-Webhook-Delivery";

    private final Map<String, WebhookDestinationProfile> destinationsByHost;
    private final HttpClient httpClient;
    private final Function<String, String> hmacSecretLookup;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final Duration retryBackoff;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebhookDeliveryRecordStore deliveryStore;
    private final Function<Void, Connection> connectionSupplier;

    public HttpWebhookCapabilityAdapter(List<WebhookDestinationProfile> destinations) {
        // followRedirects is stated explicitly even though Redirect.NEVER is already the JDK default:
        // the allowlist authorises a HOST, and a redirect is the one way an authorised host can hand
        // the request to an unauthorised one after the check has passed. Leaving it implicit means a
        // later `.followRedirects(ALWAYS)` added for convenience would silently reopen that path.
        this(destinations,
                HttpClient.newBuilder()
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                System::getenv);
    }

    public HttpWebhookCapabilityAdapter(
            List<WebhookDestinationProfile> destinations,
            HttpClient httpClient,
            Function<String, String> hmacSecretLookup
    ) {
        this(destinations, httpClient, hmacSecretLookup, DEFAULT_REQUEST_TIMEOUT, DEFAULT_MAX_RETRIES,
                DEFAULT_RETRY_BACKOFF, null, null);
    }

    /**
     * Full constructor: an adapter-owned deadline/retry policy plus optional delivery-record
     * persistence. {@code deliveryStore}/{@code connectionSupplier} are both null or both non-null --
     * when absent, {@code post} behaves exactly like the simpler constructors (no persistence, same
     * send behaviour), mirroring how {@code HttpExternalAiCapabilityAdapter} keeps its verdict
     * tracking in memory only.
     */
    public HttpWebhookCapabilityAdapter(
            List<WebhookDestinationProfile> destinations,
            HttpClient httpClient,
            Function<String, String> hmacSecretLookup,
            Duration requestTimeout,
            int maxRetries,
            Duration retryBackoff,
            WebhookDeliveryRecordStore deliveryStore,
            Function<Void, Connection> connectionSupplier
    ) {
        Objects.requireNonNull(destinations, "destinations");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.hmacSecretLookup = Objects.requireNonNull(hmacSecretLookup, "hmacSecretLookup");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        this.maxRetries = maxRetries;
        this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff");
        if ((deliveryStore == null) != (connectionSupplier == null)) {
            throw new IllegalArgumentException("deliveryStore and connectionSupplier must both be null or both be set");
        }
        this.deliveryStore = deliveryStore;
        this.connectionSupplier = connectionSupplier;

        Map<String, WebhookDestinationProfile> byHost = new LinkedHashMap<>();
        for (WebhookDestinationProfile destination : destinations) {
            if (byHost.putIfAbsent(destination.host(), destination) != null) {
                throw new IllegalArgumentException("Duplicate destination host in configuration: " + destination.host());
            }
        }
        this.destinationsByHost = Map.copyOf(byHost);
    }

    @Override
    public String adapterId() {
        return "webhook-http";
    }

    @Override
    public String capability() {
        return "webhook";
    }

    @Override
    public String capabilityType() {
        return "WebhookCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        if (!"post".equals(call.operation())) {
            return CapabilityResult.failure(
                    "WEBHOOK_OPERATION_UNSUPPORTED",
                    "Unsupported webhook operation: " + call.operation(),
                    CapabilityErrorKind.CONTRACT,
                    Map.of("operation", call.operation())
            );
        }
        Object payload = call.args().isEmpty() ? Map.of() : call.args().get(0);
        return CapabilityResult.success(post(payload));
    }

    /**
     * @param payload a map carrying {@code url} (the destination, required) plus whatever
     *                application fields make up the delivered body. {@code url} is stripped before
     *                the body is signed and sent -- it is routing metadata, not application data.
     */
    public Map<String, Object> post(Object payload) {
        Map<String, Object> request = normalizePayload(payload);
        Object rawUrl = request.remove("url");
        if (!(rawUrl instanceof String url) || url.isBlank()) {
            throw new IllegalArgumentException("webhook.post payload must contain a non-blank 'url' field");
        }

        String host = extractHost(url);
        WebhookDestinationProfile destination = requireAllowedDestination(host);
        String secret = requireHmacSecret(destination);

        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed serializing webhook payload to JSON", e);
        }

        String deliveryId = UUID.randomUUID().toString();
        String signature = hmacHex(secret, bodyJson.getBytes(StandardCharsets.UTF_8));
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header(SIGNATURE_HEADER, "sha256=" + signature)
                .header(DELIVERY_ID_HEADER, deliveryId)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();

        Instant now = Instant.now();
        recordPending(deliveryId, host, url, bodyJson, now);
        HttpResponse<String> response;
        try {
            response = send(httpRequest, host);
        } catch (RuntimeException failure) {
            recordFailed(deliveryId, failure.getMessage(), Instant.now());
            throw failure;
        }
        recordDelivered(deliveryId, response.statusCode(), Instant.now());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", deliveryId);
        result.put("status", "delivered");
        result.put("httpStatus", response.statusCode());
        result.put("url", url);
        result.put("deliveredAt", Instant.now().toString());
        return result;
    }

    private String extractHost(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("webhook.post 'url' must be an absolute URL with a host: " + url);
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("webhook.post 'url' is not a valid URL: " + url, e);
        }
    }

    private WebhookDestinationProfile requireAllowedDestination(String host) {
        if (destinationsByHost.isEmpty()) {
            throw new WebhookEgressDeniedException(
                    "WEBHOOK_EGRESS_DENIED_NO_ALLOWLIST",
                    "No webhook destination allowlist is configured; denying delivery to '" + host
                            + "' rather than sending unchecked.");
        }
        WebhookDestinationProfile destination = destinationsByHost.get(host);
        if (destination == null) {
            throw new WebhookEgressDeniedException(
                    "WEBHOOK_EGRESS_DENIED_HOST_NOT_ALLOWED",
                    "Host '" + host + "' is not on the configured webhook destination allowlist; "
                            + "denying rather than sending to an unlisted host.");
        }
        return destination;
    }

    private String requireHmacSecret(WebhookDestinationProfile destination) {
        String secret = hmacSecretLookup.apply(destination.hmacSecretEnvVar());
        if (secret == null || secret.isBlank()) {
            throw new WebhookEgressDeniedException(
                    "WEBHOOK_EGRESS_DENIED_NO_SECRET",
                    "No HMAC secret configured (env var " + destination.hmacSecretEnvVar() + ") for host '"
                            + destination.host() + "'; denying this delivery rather than sending unsigned.");
        }
        return secret;
    }

    /**
     * R8d (RUN-4) bounded retry loop, entirely local to this adapter -- identical shape to
     * {@code HttpExternalAiCapabilityAdapter.send}. Retries only {@link IOException} (which covers
     * {@code HttpTimeoutException} when {@link #requestTimeout} expires) and 429/5xx responses;
     * never a 4xx. The request body is a fixed in-memory string, so resending it is safe.
     */
    private HttpResponse<String> send(HttpRequest request, String host) {
        RuntimeException lastFailure = null;
        int totalAttempts = maxRetries + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (isRetryableStatus(response.statusCode()) && attempt < totalAttempts) {
                    lastFailure = new IllegalStateException(
                            "Webhook destination " + host + " returned retryable HTTP " + response.statusCode()
                                    + " on attempt " + attempt + "/" + totalAttempts + ": " + response.body());
                    backoff(attempt);
                    continue;
                }
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException(
                            "Webhook destination " + host + " returned HTTP " + response.statusCode()
                                    + ": " + response.body());
                }
                return response;
            } catch (IOException e) {
                lastFailure = new UncheckedIOException(
                        "Webhook HTTP call failed for host " + host + " on attempt " + attempt + "/" + totalAttempts
                                + " (requestTimeout=" + requestTimeout + ")", e);
                if (attempt >= totalAttempts) {
                    throw lastFailure;
                }
                backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Webhook HTTP call interrupted for host " + host, e);
            }
        }
        // Unreachable in practice (the loop above always returns or throws on its last iteration),
        // but the compiler cannot prove that from a non-constant loop bound.
        throw lastFailure != null
                ? lastFailure
                : new IllegalStateException("Webhook HTTP call exhausted retries with no recorded failure");
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private void backoff(int attempt) {
        long delayMs = retryBackoff.toMillis() * attempt;
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Webhook HTTP retry backoff interrupted", e);
        }
    }

    private static String hmacHex(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed computing HMAC signature for outbound webhook", e);
        }
    }

    private void recordPending(String id, String host, String url, String bodyJson, Instant now) {
        if (deliveryStore == null) {
            return;
        }
        try (Connection connection = connectionSupplier.apply(null)) {
            deliveryStore.ensureSchema(connection);
            deliveryStore.insertPending(connection, id, host, url, bodyJson, now);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed recording pending webhook delivery " + id, e);
        }
    }

    private void recordDelivered(String id, int httpStatus, Instant now) {
        if (deliveryStore == null) {
            return;
        }
        try (Connection connection = connectionSupplier.apply(null)) {
            deliveryStore.markDelivered(connection, id, httpStatus, now);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed recording delivered webhook " + id, e);
        }
    }

    private void recordFailed(String id, String errorMessage, Instant now) {
        if (deliveryStore == null) {
            return;
        }
        try (Connection connection = connectionSupplier.apply(null)) {
            deliveryStore.markFailed(connection, id, errorMessage, now);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed recording failed webhook " + id, e);
        }
    }

    private static Map<String, Object> normalizePayload(Object payload) {
        if (payload == null) {
            return new LinkedHashMap<>();
        }
        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return out;
        }
        throw new IllegalArgumentException(
                "webhook.post payload must be a map containing at least 'url'; got: " + payload.getClass());
    }
}
