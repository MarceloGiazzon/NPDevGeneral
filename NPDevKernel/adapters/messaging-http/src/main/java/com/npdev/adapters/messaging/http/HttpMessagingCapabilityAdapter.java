package com.npdev.adapters.messaging.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.MessagingCapability;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * R6.4: the cross-app half of NPDev's {@link MessagingCapability}. An event {@link #publish(Object)
 * published} in one generated app's kernel is delivered to every OTHER app configured as a
 * {@link MessagingPeerProfile} for that topic, over a real HTTP POST -- the same fail-closed,
 * timeout-and-retry-bounded posture proven by {@code HttpWebhookCapabilityAdapter} (R6.1) and
 * {@code HttpExternalAiCapabilityAdapter} (ADR-0009), applied to a NEW direction: not a third party
 * or a vendor, but another NPDev app's own {@link #HttpMessagingCapabilityAdapter(String, List,
 * HttpClient, Function, Duration, int, Duration, IdempotencyStore, InetSocketAddress) inbound
 * receiver}, which this same class also implements.
 *
 * <h2>Delivery semantics: AT-LEAST-ONCE, with an idempotent receiver</h2>
 *
 * <p>{@link #publish(Object)} POSTs to every matching peer with a bounded, adapter-local retry
 * ({@code maxRetries} attempts after the first, linear backoff -- identical shape to
 * {@code HttpWebhookCapabilityAdapter#send}). If a targeted peer cannot be reached after the retry
 * budget is exhausted, {@code publish} throws {@link MessagingDeliveryFailedException} rather than
 * returning a success ack -- <b>this is the "failure must be visible" requirement</b>: a caller that
 * catches nothing sees the exception and knows the peer did not receive the message. This adapter
 * never silently drops an undeliverable event.
 *
 * <p>Because the sender retries, the receiver can see the SAME {@code deliveryId} more than once
 * (a genuine second attempt after a lost response, or a caller that resends with an explicit
 * {@code deliveryId} for its own retry). <b>A duplicate delivery is survivable</b>: the inbound
 * handler ({@link #handleInboundDelivery}) looks up {@code deliveryId} in the injected
 * {@link IdempotencyStore} (the SAME port the kernel's own capability-call idempotency and the cron
 * scheduler's fire-claim already use -- reused here rather than a second dedup mechanism) before
 * invoking any locally registered subscriber. A delivery already recorded {@code SUCCESS} is
 * acknowledged again WITHOUT re-invoking subscribers -- so a subscriber's side effect (e.g. crediting
 * an account) runs at most once even though the wire protocol is at-least-once. A delivery previously
 * recorded {@code FAILED} (a subscriber threw, or the app crashed mid-processing) is retried: this is
 * "at-least-once", not "exactly-once", so a permanently broken subscriber is retried, not silently
 * given up on and not double-counted once it does succeed.
 *
 * <h2>Two directions, one class</h2>
 *
 * <p>The SAME instance is both the outbound sender (for messages this app publishes) and the inbound
 * receiver (for messages a peer sends to this app) -- both directions dispatch to the SAME local
 * {@code subscribe}/{@code unsubscribe} registry, so "acted on in app B" means exactly: app B's own
 * {@code HttpMessagingCapabilityAdapter} received the POST and invoked app B's locally registered
 * handler for that topic.
 *
 * <h2>Two ways to reach the inbound path: embedded server, or a direct in-process call</h2>
 *
 * <p>{@link #handleInboundDelivery} is this class's own embedded {@link HttpServer} route handler,
 * started only when an instance is constructed with a non-null {@code inboundListenAddress} -- real
 * wire transport, a genuine second HTTP server. This module's own tests use it to prove two
 * independently configured instances really exchange a signed HTTP POST over a real loopback socket,
 * an honest substitute for two booted FinalApps.
 *
 * <p>A RuntimeHost-generated FinalApp does not need a second listening socket: the app already has its
 * own front door (Spring MVC), so {@code com.finalexec.api.MessagingDeliveryController} (RuntimeHost,
 * wired by {@code NpdevCapabilityBindingConfig}) reads an inbound request's headers/body itself and
 * calls {@link #receiveInboundDelivery} directly, in-process -- the SAME verify/dedupe/dispatch logic
 * {@link #handleInboundDelivery} runs, extracted so both callers share it, with no embedded
 * {@link HttpServer}, no loopback socket, and no extra network hop in that path. Consequently the
 * FinalApp's {@code httpMessagingCapabilityAdapter} bean is constructed with a null
 * {@code inboundListenAddress}; {@link #inboundPort()} only applies to an instance that opted into the
 * embedded-server path.
 */
public final class HttpMessagingCapabilityAdapter implements CapabilityAdapter, MessagingCapability, AutoCloseable {

    public static final String INBOUND_PATH = "/npdev/messaging/deliver";

    /** Public: a same-JVM in-process caller (e.g. RuntimeHost's {@code MessagingDeliveryController})
     *  needs the exact header name to read from its own inbound request and pass to
     *  {@link #receiveInboundDelivery}. */
    public static final String SIGNATURE_HEADER = "X-Npdev-Messaging-Signature";
    public static final String SENDER_HEADER = "X-Npdev-Messaging-Sender";
    private static final String DELIVERY_ID_HEADER = "X-Npdev-Messaging-Delivery";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final String IDEMPOTENCY_CAPABILITY = "MessagingCapability";
    private static final String IDEMPOTENCY_OPERATION = "deliver";

    /** RUN-4 posture: connect timeout for the HttpClient this adapter builds itself. */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Per-request deadline -- a peer is expected to acknowledge quickly, not run a long job inline. */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofMillis(500);

    private final String appId;
    private final Map<String, MessagingPeerProfile> peersByAppId;
    private final List<MessagingPeerProfile> peersInOrder;
    private final HttpClient httpClient;
    private final Function<String, String> hmacSecretLookup;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final Duration retryBackoff;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, CopyOnWriteArrayList<Subscription>> subscribersByTopic = new ConcurrentHashMap<>();
    private final Map<String, Subscription> subscriptionsById = new ConcurrentHashMap<>();

    /** Serializes concurrent inbound deliveries that share a deliveryId, closing the race the
     *  {@link IdempotencyStore} port itself has no atomic claim primitive for (unlike
     *  {@code ScheduledEventSql.claim()}'s optimistic UPDATE). Entries are never removed -- the
     *  lock objects are tiny and bounded by the number of distinct deliveryIds ever seen, which is
     *  the same bound the idempotency store itself already carries. */
    private final Map<String, Object> inFlightDeliveryLocks = new ConcurrentHashMap<>();

    private final HttpServer inboundServer;

    /** No inbound receiver: this instance can publish but will never accept a delivery from a peer. */
    public HttpMessagingCapabilityAdapter(String appId, List<MessagingPeerProfile> peers, IdempotencyStore idempotencyStore) {
        this(appId, peers, idempotencyStore, null);
    }

    /** Starts an embedded inbound receiver on {@code inboundListenAddress} (nullable -- see above). */
    public HttpMessagingCapabilityAdapter(
            String appId,
            List<MessagingPeerProfile> peers,
            IdempotencyStore idempotencyStore,
            InetSocketAddress inboundListenAddress
    ) {
        this(
                appId, peers,
                HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build(),
                System::getenv,
                DEFAULT_REQUEST_TIMEOUT, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_BACKOFF,
                idempotencyStore, inboundListenAddress
        );
    }

    /**
     * Full constructor: an adapter-owned deadline/retry policy (RUN-4), plus the inbound receiver
     * toggle. {@code requestTimeout} bounds a single attempt; {@code maxRetries} is the number of
     * retries AFTER the first attempt (0 = try once, never retry); {@code retryBackoff} is the base
     * delay, multiplied by the attempt number between retries.
     */
    public HttpMessagingCapabilityAdapter(
            String appId,
            List<MessagingPeerProfile> peers,
            HttpClient httpClient,
            Function<String, String> hmacSecretLookup,
            Duration requestTimeout,
            int maxRetries,
            Duration retryBackoff,
            IdempotencyStore idempotencyStore,
            InetSocketAddress inboundListenAddress
    ) {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("appId must be non-blank");
        }
        this.appId = MessagingPeerProfile.normalize(appId);
        Objects.requireNonNull(peers, "peers");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.hmacSecretLookup = Objects.requireNonNull(hmacSecretLookup, "hmacSecretLookup");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        this.maxRetries = maxRetries;
        this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");

        Map<String, MessagingPeerProfile> byId = new LinkedHashMap<>();
        for (MessagingPeerProfile peer : peers) {
            if (byId.putIfAbsent(peer.peerAppId(), peer) != null) {
                throw new IllegalArgumentException("Duplicate peerAppId in configuration: " + peer.peerAppId());
            }
        }
        this.peersByAppId = Map.copyOf(byId);
        this.peersInOrder = List.copyOf(byId.values());

        if (inboundListenAddress == null) {
            this.inboundServer = null;
        } else {
            try {
                this.inboundServer = HttpServer.create(inboundListenAddress, 0);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed starting messaging-http inbound receiver on "
                        + inboundListenAddress, e);
            }
            inboundServer.createContext(INBOUND_PATH, this::handleInboundDelivery);
            inboundServer.setExecutor(null);
            inboundServer.start();
        }
    }

    /** The real bound port of the embedded receiver, once started -- 0 requests an ephemeral port. */
    public int inboundPort() {
        if (inboundServer == null) {
            throw new IllegalStateException("This adapter instance has no inbound receiver configured");
        }
        return inboundServer.getAddress().getPort();
    }

    @Override
    public void close() {
        if (inboundServer != null) {
            inboundServer.stop(0);
        }
    }

    @Override
    public String adapterId() {
        return "messaging-http";
    }

    @Override
    public String capability() {
        return "messaging";
    }

    @Override
    public String capabilityType() {
        return "MessagingCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        return switch (call.operation()) {
            case "publish" -> CapabilityResult.success(publish(call.input()));
            default -> CapabilityResult.failure(
                    "MESSAGING_OPERATION_UNSUPPORTED_VIA_CAPABILITY_CALL",
                    "Operation '" + call.operation() + "' is not reachable through a flow capabilityCall step; "
                            + "'subscribe'/'unsubscribe' take a Java handler reference and are a Java-API-only "
                            + "surface on this adapter, not JSON-representable.",
                    CapabilityErrorKind.CONTRACT,
                    Map.of("operation", call.operation())
            );
        };
    }

    // ------------------------------------------------------------------ outbound: publish

    /**
     * @param message a {@code Map} carrying {@code topic} (required, non-blank String), an optional
     *                {@code deliveryId} (routing metadata, stripped before delivery -- supplying one
     *                lets a caller's OWN retry of a failed {@code publish} call collapse into the
     *                same receiver-side dedup as a transport-level retry), plus whatever application
     *                fields make up the message body.
     * @throws MessagingDeliveryFailedException if any targeted peer could not be delivered to after
     *         retries -- see the class javadoc's "failure must be visible" section.
     */
    @Override
    public Object publish(Object message) {
        Map<String, Object> request = normalizePayload(message);
        String topic = requireTopic(request);
        Object explicitDeliveryId = request.remove("deliveryId");
        String deliveryId = (explicitDeliveryId instanceof String s && !s.isBlank())
                ? s
                : UUID.randomUUID().toString();

        int localDelivered = deliverLocally(topic, request);

        Map<String, Object> payloadOnly = new LinkedHashMap<>(request);
        payloadOnly.remove("topic");

        List<String> succeeded = new ArrayList<>();
        Map<String, RuntimeException> failed = new LinkedHashMap<>();
        for (MessagingPeerProfile peer : peersInOrder) {
            if (!peer.topics().isEmpty() && !peer.topics().contains(topic)) {
                continue;
            }
            try {
                sendToPeer(peer, topic, deliveryId, payloadOnly);
                succeeded.add(peer.peerAppId());
            } catch (RuntimeException e) {
                failed.put(peer.peerAppId(), e);
            }
        }

        if (!failed.isEmpty()) {
            throw new MessagingDeliveryFailedException(topic, deliveryId, succeeded, failed);
        }

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("deliveryId", deliveryId);
        ack.put("topic", topic);
        ack.put("status", "published");
        ack.put("localDeliveries", localDelivered);
        ack.put("remoteDeliveries", succeeded);
        return ack;
    }

    private int deliverLocally(String topic, Map<String, Object> messageIncludingTopic) {
        List<Subscription> handlers = subscribersByTopic.getOrDefault(topic, new CopyOnWriteArrayList<>());
        for (Subscription subscription : handlers) {
            subscription.handler().accept(messageIncludingTopic);
        }
        return handlers.size();
    }

    private void sendToPeer(MessagingPeerProfile peer, String topic, String deliveryId, Map<String, Object> payloadOnly) {
        String secret = requireHmacSecret(peer);

        String bodyJson;
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("topic", topic);
            envelope.put("deliveryId", deliveryId);
            envelope.put("senderAppId", appId);
            envelope.put("payload", payloadOnly);
            bodyJson = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed serializing outbound messaging envelope to JSON", e);
        }

        String signature = hmacHex(secret, bodyJson.getBytes(StandardCharsets.UTF_8));
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(peer.baseUrl() + INBOUND_PATH))
                .header("Content-Type", "application/json")
                .header(SIGNATURE_HEADER, "sha256=" + signature)
                .header(DELIVERY_ID_HEADER, deliveryId)
                .header(SENDER_HEADER, appId)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();

        send(httpRequest, peer.peerAppId());
    }

    private String requireHmacSecret(MessagingPeerProfile peer) {
        String secret = hmacSecretLookup.apply(peer.hmacSecretEnvVar());
        if (secret == null || secret.isBlank()) {
            throw new MessagingDeliveryDeniedException(
                    "MESSAGING_EGRESS_DENIED_NO_SECRET",
                    "No HMAC secret configured (env var " + peer.hmacSecretEnvVar() + ") for peer '"
                            + peer.peerAppId() + "'; denying this delivery rather than sending unsigned.");
        }
        return secret;
    }

    /**
     * RUN-4 bounded retry loop, entirely local to this adapter -- same shape as
     * {@code HttpWebhookCapabilityAdapter.send}. Retries only {@link IOException} (which covers
     * {@code HttpTimeoutException} when {@link #requestTimeout} expires) and 429/5xx responses
     * (a 5xx is exactly what the receiver returns when a local subscriber threw -- see
     * {@link #handleInboundDelivery} -- so this retry is also what re-drives "at-least-once until a
     * subscriber succeeds"); never a 4xx (a denial or a malformed envelope, neither fixed by resending
     * the same bytes).
     */
    private HttpResponse<String> send(HttpRequest request, String peerAppId) {
        RuntimeException lastFailure = null;
        int totalAttempts = maxRetries + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (isRetryableStatus(response.statusCode()) && attempt < totalAttempts) {
                    lastFailure = new IllegalStateException(
                            "Messaging peer " + peerAppId + " returned retryable HTTP " + response.statusCode()
                                    + " on attempt " + attempt + "/" + totalAttempts + ": " + response.body());
                    backoff(attempt);
                    continue;
                }
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException(
                            "Messaging peer " + peerAppId + " returned HTTP " + response.statusCode()
                                    + ": " + response.body());
                }
                return response;
            } catch (IOException e) {
                lastFailure = new UncheckedIOException(
                        "Messaging HTTP delivery failed for peer " + peerAppId + " on attempt " + attempt + "/"
                                + totalAttempts + " (requestTimeout=" + requestTimeout + ")", e);
                if (attempt >= totalAttempts) {
                    throw lastFailure;
                }
                backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Messaging HTTP delivery interrupted for peer " + peerAppId, e);
            }
        }
        throw lastFailure != null
                ? lastFailure
                : new IllegalStateException("Messaging HTTP delivery exhausted retries with no recorded failure");
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
            throw new IllegalStateException("Messaging HTTP retry backoff interrupted", e);
        }
    }

    // ------------------------------------------------------------------ inbound: receive

    /**
     * The directly-callable entry point: verify/dedupe/dispatch an inbound delivery, given exactly the
     * raw material an HTTP POST to {@link #INBOUND_PATH} would carry -- the {@value #SENDER_HEADER} and
     * {@value #SIGNATURE_HEADER} header values, and the raw (unparsed) request body bytes. Performs
     * EXACTLY what {@link #handleInboundDelivery} performs: unknown-sender rejection, fail-closed
     * secret check, constant-time signature verification, {@code deliveryId} dedupe through the
     * injected {@link IdempotencyStore}, subscriber dispatch, and the same outcome classification --
     * see the class javadoc for the full at-least-once/idempotent-receiver contract. This is the ONLY
     * copy of that logic; {@link #handleInboundDelivery} (the embedded-server route) calls this same
     * method rather than duplicating it.
     *
     * <p>Never throws for a caller-facing outcome (a forged signature, an unknown sender, a failing
     * subscriber) -- every such case is a returned {@link InboundDeliveryResult}, never an exception,
     * so an in-process caller (e.g. a Spring controller) can translate {@code statusCode}/{@code body}
     * straight into its own HTTP response without a try/catch. An unrecoverable adapter-internal fault
     * (e.g. a JSON-serialization bug in an ack body) is still an exception, exactly as it always was
     * from {@link #respondFromResult}.
     *
     * @param senderAppId     the value of the {@value #SENDER_HEADER} header, or {@code null}/blank if
     *                        absent
     * @param signatureHeader the value of the {@value #SIGNATURE_HEADER} header, or {@code null} if
     *                        absent
     * @param rawBody         the exact bytes of the request body, unparsed -- the signature is verified
     *                        over these bytes, so a caller must not re-serialize or otherwise alter them
     *                        before passing them in
     */
    public InboundDeliveryResult receiveInboundDelivery(String senderAppId, String signatureHeader, byte[] rawBody) {
        Objects.requireNonNull(rawBody, "rawBody");
        if (senderAppId == null || senderAppId.isBlank()) {
            return errorResult(401, "MESSAGING_SENDER_UNKNOWN", "missing " + SENDER_HEADER + " header");
        }
        MessagingPeerProfile peer = peersByAppId.get(MessagingPeerProfile.normalize(senderAppId));
        if (peer == null) {
            return errorResult(401, "MESSAGING_SENDER_NOT_TRUSTED",
                    "sender '" + senderAppId + "' is not a configured peer");
        }
        String secret = hmacSecretLookup.apply(peer.hmacSecretEnvVar());
        if (secret == null || secret.isBlank()) {
            return errorResult(401, "MESSAGING_SECRET_NOT_CONFIGURED",
                    "no HMAC secret configured (env var " + peer.hmacSecretEnvVar() + ") for peer '"
                            + peer.peerAppId() + "'");
        }
        if (signatureHeader == null || !verifySignature(secret, rawBody, signatureHeader)) {
            return errorResult(401, "MESSAGING_SIGNATURE_INVALID", "signature verification failed");
        }

        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            return errorResult(400, "MESSAGING_BODY_NOT_JSON", "request body must be a JSON object");
        }
        String topic = envelope.path("topic").asText(null);
        String deliveryId = envelope.path("deliveryId").asText(null);
        if (topic == null || topic.isBlank() || deliveryId == null || deliveryId.isBlank()) {
            return errorResult(400, "MESSAGING_ENVELOPE_INVALID",
                    "envelope must carry non-blank 'topic' and 'deliveryId'");
        }
        Map<String, Object> payload = readPayload(envelope);

        return processInboundDelivery(topic, deliveryId, payload);
    }

    /**
     * The embedded receiver's one route -- a thin HTTP-transport adapter over
     * {@link #receiveInboundDelivery}. Always writes a response and closes the exchange; never lets an
     * exception escape uncaught (the JDK {@link HttpServer}'s default behaviour for an uncaught handler
     * exception is a bare 500 with no body, which would defeat the named-error-code contract every
     * other branch here honours).
     */
    private void handleInboundDelivery(HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respondFromResult(exchange, errorResult(405, "MESSAGING_METHOD_NOT_ALLOWED", "only POST is accepted"));
                return;
            }

            byte[] rawBody;
            try {
                rawBody = exchange.getRequestBody().readAllBytes();
            } catch (IOException e) {
                respondFromResult(exchange, errorResult(400, "MESSAGING_BODY_UNREADABLE", "failed reading request body"));
                return;
            }

            String senderAppId = firstHeader(exchange, SENDER_HEADER);
            String signatureHeader = firstHeader(exchange, SIGNATURE_HEADER);
            respondFromResult(exchange, receiveInboundDelivery(senderAppId, signatureHeader, rawBody));
        } finally {
            exchange.close();
        }
    }

    /**
     * The dedup + dispatch step, serialized per-{@code deliveryId} (see
     * {@link #inFlightDeliveryLocks}) so two literally-simultaneous deliveries of the same id cannot
     * both observe "not yet recorded" and both invoke subscribers.
     */
    private InboundDeliveryResult processInboundDelivery(String topic, String deliveryId, Map<String, Object> payload) {
        String tenantId = "cross-app-messaging:" + appId;
        Object lock = inFlightDeliveryLocks.computeIfAbsent(deliveryId, key -> new Object());
        synchronized (lock) {
            Optional<IdempotencyRecord> existing =
                    idempotencyStore.find(tenantId, IDEMPOTENCY_CAPABILITY, IDEMPOTENCY_OPERATION, deliveryId);
            if (existing.isPresent() && existing.get().success()) {
                return new InboundDeliveryResult(200, ackBody("duplicate", deliveryId, topic));
            }

            Map<String, Object> messageForHandlers = new LinkedHashMap<>(payload);
            messageForHandlers.put("topic", topic);
            int delivered;
            try {
                delivered = deliverLocally(topic, messageForHandlers);
            } catch (RuntimeException handlerFailure) {
                idempotencyStore.saveFailure(tenantId, IDEMPOTENCY_CAPABILITY, IDEMPOTENCY_OPERATION, deliveryId,
                        handlerFailure.getClass().getSimpleName(), System.currentTimeMillis());
                return errorResult(500, "MESSAGING_SUBSCRIBER_FAILED",
                        "a local subscriber for topic '" + topic + "' failed: " + handlerFailure.getMessage());
            }

            idempotencyStore.saveSuccess(tenantId, IDEMPOTENCY_CAPABILITY, IDEMPOTENCY_OPERATION, deliveryId,
                    "{\"delivered\":" + delivered + "}", System.currentTimeMillis());
            Map<String, Object> ack = ackBody("delivered", deliveryId, topic);
            ack.put("delivered", delivered);
            return new InboundDeliveryResult(200, ack);
        }
    }

    private Map<String, Object> readPayload(JsonNode envelope) {
        JsonNode payloadNode = envelope.path("payload");
        if (payloadNode.isMissingNode() || payloadNode.isNull()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(payloadNode, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static Map<String, Object> ackBody(String status, String deliveryId, String topic) {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", status);
        ack.put("deliveryId", deliveryId);
        ack.put("topic", topic);
        return ack;
    }

    private static InboundDeliveryResult errorResult(int statusCode, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        return new InboundDeliveryResult(statusCode, body);
    }

    /** Writes an {@link InboundDeliveryResult} to the embedded server's exchange -- the ONLY remaining
     *  caller of this is {@link #handleInboundDelivery}; an in-process caller of
     *  {@link #receiveInboundDelivery} reads {@code statusCode}/{@code body} directly instead. */
    private void respondFromResult(HttpExchange exchange, InboundDeliveryResult result) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(result.body());
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(result.statusCode(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing messaging-http inbound response", e);
        }
    }

    private static String firstHeader(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    /**
     * {@link MessageDigest#isEqual(byte[], byte[])} is documented constant-time specifically to defeat
     * timing attacks on a signature comparison -- same posture as {@code WebhookInboundController}.
     */
    private static boolean verifySignature(String secret, byte[] rawBody, String signatureHeader) {
        String presented = signatureHeader.regionMatches(true, 0, "sha256=", 0, 7)
                ? signatureHeader.substring(7)
                : signatureHeader;
        byte[] presentedBytes;
        try {
            presentedBytes = HexFormat.of().parseHex(presented.trim());
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, rawBody), presentedBytes);
    }

    private static String hmacHex(String secret, byte[] data) {
        return HexFormat.of().formatHex(hmac(secret, data));
    }

    private static byte[] hmac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed computing HMAC signature for cross-app messaging", e);
        }
    }

    // ------------------------------------------------------------------ local subscribe/unsubscribe

    /**
     * @param handlerRef must be a {@link Consumer}{@code <Map<String,Object>>}, invoked with the full
     *                   message (including {@code topic}) whenever this SAME adapter instance either
     *                   publishes locally or receives an inbound delivery for {@code topic}.
     * @return the subscription id (a String), also valid as the {@code subscriptionRef} passed to
     *         {@link #unsubscribe(Object)}.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Object subscribe(String topic, Object handlerRef) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("messaging.subscribe requires a non-blank topic");
        }
        if (!(handlerRef instanceof Consumer<?> rawHandler)) {
            throw new IllegalArgumentException(
                    "messaging.subscribe requires a java.util.function.Consumer<Map<String,Object>> handlerRef, got: "
                            + (handlerRef == null ? "null" : handlerRef.getClass()));
        }
        Consumer<Map<String, Object>> handler = (Consumer<Map<String, Object>>) rawHandler;
        String subscriptionId = UUID.randomUUID().toString();
        Subscription subscription = new Subscription(subscriptionId, topic, handler);
        subscribersByTopic.computeIfAbsent(topic, key -> new CopyOnWriteArrayList<>()).add(subscription);
        subscriptionsById.put(subscriptionId, subscription);
        return subscriptionId;
    }

    @Override
    public Object unsubscribe(Object subscriptionRef) {
        String subscriptionId = String.valueOf(subscriptionRef);
        Subscription subscription = subscriptionsById.remove(subscriptionId);
        Map<String, Object> ack = new LinkedHashMap<>();
        if (subscription == null) {
            ack.put("status", "not_found");
            return ack;
        }
        List<Subscription> handlers = subscribersByTopic.get(subscription.topic());
        if (handlers != null) {
            handlers.remove(subscription);
        }
        ack.put("status", "unsubscribed");
        return ack;
    }

    // ------------------------------------------------------------------ helpers

    private static String requireTopic(Map<String, Object> request) {
        Object topic = request.get("topic");
        if (!(topic instanceof String topicString) || topicString.isBlank()) {
            throw new IllegalArgumentException("messaging.publish payload must contain a non-blank 'topic' field");
        }
        return topicString;
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
                "messaging.publish payload must be a map containing at least 'topic'; got: " + payload.getClass());
    }

    /**
     * The outcome of {@link #receiveInboundDelivery}: an HTTP-shaped status code and a JSON-ready body
     * -- ack (2xx) or {@code {ok:false, code, message}} error (4xx/5xx), identical to what the embedded
     * server has always written to the wire. Deliberately dumb (no behaviour beyond the two accessors)
     * so any caller -- the embedded {@link HttpServer} route or an in-process Spring controller -- can
     * turn it into its own response type without depending on anything JDK-HTTP-specific.
     */
    public record InboundDeliveryResult(int statusCode, Map<String, Object> body) {
        public InboundDeliveryResult {
            Objects.requireNonNull(body, "body");
        }
    }

    private record Subscription(String id, String topic, Consumer<Map<String, Object>> handler) {
        private Subscription {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(handler, "handler");
        }
    }
}
