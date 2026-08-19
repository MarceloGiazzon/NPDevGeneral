package com.finalexec.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledWebhook;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * R6.2: the INBOUND half of R6.1's webhook pair -- {@code POST /api/hooks/{source}} is the "door"
 * the flow docs' payment-confirmation use case needs: a third party that holds no NPDev credential
 * at all posts a signed request here, and once the signature verifies, this controller publishes
 * {@code webhook.eventName} into the flow engine's own {@link EventStore} so a flow parked on
 * {@code awaitEvent} (via {@code com.npdev.kernel.AwaitEventStep}) is resumed exactly as if the
 * event had come from inside the app.
 *
 * <p><b>Deliberately NOT gated by NPDev auth.</b> Every other controller in this package calls
 * {@code RuntimeContextService.currentContext(httpRequest)} or an equivalent to authenticate the
 * caller. This one never does -- its own HMAC-SHA256 signature check IS the authentication, exactly
 * how a payment processor, Git host, or form backend proves its identity to any webhook receiver.
 * Both the default api-key auth filter (the generated {@code RuntimeApiKeyAuthFilter}, via its
 * template {@code npdev-runtime-api-key-auth-filter.mustache}) and {@code JwtBearerAuthFilter} (jwt
 * mode) carry an explicit {@code /api/hooks/} exemption for exactly this reason -- without it, a
 * third party with no NPDev credential would be rejected before this controller ever ran.
 *
 * <p><b>HMAC verification, R6.1's exact posture reused.</b> {@code HmacSHA256} over the raw request
 * body bytes, hex-encoded, presented as {@code X-Npdev-Webhook-Signature: sha256=<hex>} -- the same
 * algorithm, encoding, and header name {@code com.npdev.adapters.webhook.http.HttpWebhookCapabilityAdapter}
 * (R6.1, {@code ledger/items/RUN-14.yml}) already sends on the outbound side, so a caller round-
 * tripping through both halves of this pair sees one convention, not two. The comparison is
 * {@link MessageDigest#isEqual(byte[], byte[])}, which the JDK documents as constant-time
 * specifically to defeat timing attacks -- never a short-circuiting {@code String.equals} /
 * {@code Arrays.equals} on the raw bytes.
 *
 * <p><b>Fail-closed, same as R6.1's egress guard.</b> No configured webhook for {@code source} is a
 * 404 naming the source (nothing to verify against). A webhook whose {@code hmacSecretEnvVar} does
 * not resolve to a non-blank value is a 401 {@code WEBHOOK_SECRET_NOT_CONFIGURED} -- this
 * controller never falls back to accepting an unsigned request. A missing, malformed, or wrong
 * signature is a 401 with a named code; no code path returns 200 without a verified signature.
 *
 * <p><b>The secret is resolved by NAME, never a literal.</b> {@link CompiledWebhook#hmacSecretEnvVar()}
 * is an environment variable NAME (e.g. {@code NPDEV_WEBHOOK_PAYMENT_PROCESSOR_SECRET}); the value
 * is looked up at request time via a {@code Function<String,String>} (defaulting to
 * {@link System#getenv(String)}), exactly R6.1's api-key/secret-by-reference shape. Nothing here,
 * in its tests, or in the ledger contains a real secret value.
 *
 * <p><b>Logging.</b> Source, event name, and outcome only -- never the presented signature header,
 * the computed signature, or the secret. An app's {@code logs/} directory ships verbatim inside
 * {@code npdev monitor logs export}, so anything written here should be assumed to end up in a
 * support bundle in a chat window (see {@code npdev_monitor.redact()}).
 *
 * <p><b>Replay: deliberately NOT handled in this round.</b> There is no timestamp window and no
 * nonce/idempotency check here -- a captured valid request can be replayed and will publish a
 * second event with a fresh {@code eventId}/{@code correlationId} lookup each time. A flow already
 * past its {@code awaitEvent} step is unaffected (nothing is waiting to consume the duplicate), but
 * a flow that re-parks on the same await could be resumed twice. Recording delivery ids (mirroring
 * R6.1's {@code WebhookDeliveryRecordStore}) is the natural follow-up if a specific source's replay
 * risk becomes a real problem; not built here because the roadmap item's own Definition of Done
 * names none of it.
 *
 * <p><b>Field mapping.</b> {@link CompiledWebhook#fieldMapping()} maps a target event-payload field
 * name to a dot-path into the inbound parsed JSON body (e.g. {@code "correlationId": "orderId"}
 * reads the body's top-level {@code orderId} field). A mapped target literally named
 * {@code correlationId} is ALSO used as the published {@link EventEnvelope#correlationId()} --
 * this is what lets {@code awaitEvent}'s {@code match.correlation: true} (the same mechanism R2.5
 * (RUN-11) already resumes a timed await with) find the exact waiting flow instance a third party's
 * payload identifies. An empty {@code fieldMapping} passes the raw parsed body through as the event
 * payload unchanged. No {@code correlationId} mapping (or a null/blank resolved value) falls back to
 * a fresh random correlation id -- the event still publishes and is still readable by
 * {@code awaitEvent}'s {@code payload}-match path, just not by correlation.
 *
 * <p><b>Tenant resolution.</b> A third-party caller carries no NPDev tenant claim at all, but
 * {@code ResumeCoordinator}'s resume sweep scopes its {@link EventStore} lookup by the WAITING
 * instance's own tenant -- an unscoped (null-tenant) published event is filtered out for any
 * instance whose tenant is non-blank, which is every apikey-mode create, even the single-tenant
 * default. This controller borrows the tenant from whichever WAITING instance is already parked on
 * the resolved correlationId ({@link FlowInstanceStore#findWaitingByCorrelation}) before publishing
 * -- found live, not by inspection: the first version of this controller published every event
 * tenant-null and the resume sweep silently never matched it against a tenant-scoped instance.
 *

 * <p><b>Registration, and why this package.</b> Listed in
 * {@code npdev/runtime-supported-controllers.json}'s {@code allowedControllers} -- required by all
 * three enforcement points ({@code RuntimeControllerAllowlistConfig}, {@code build.gradle.template}'s
 * compile-exclusion, {@code run-runtime-surface-evidence.ps1}), all three of which key off
 * {@code com.finalexec.api}, so this controller lives there like every other supported-core one.
 */
@RestController
@RequestMapping("/api/hooks")
public class WebhookInboundController {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookInboundController.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_HEADER = "X-Npdev-Webhook-Signature";
    private static final TypeReference<LinkedHashMap<String, Object>> BODY_TYPE = new TypeReference<>() {
    };

    private final CompiledModel compiledModel;
    private final EventStore eventStore;
    private final FlowInstanceStore flowInstanceStore;
    private final ObjectMapper objectMapper;
    private final Function<String, String> secretLookup;

    @Autowired
    public WebhookInboundController(
            CompiledModel compiledModel,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            ObjectMapper objectMapper
    ) {
        this(compiledModel, eventStore, flowInstanceStore, objectMapper, System::getenv);
    }

    /** Test-only seam: a fixed secret lookup instead of real environment variables. */
    WebhookInboundController(
            CompiledModel compiledModel,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            ObjectMapper objectMapper,
            Function<String, String> secretLookup
    ) {
        this.compiledModel = compiledModel;
        this.eventStore = eventStore;
        this.flowInstanceStore = flowInstanceStore;
        this.objectMapper = objectMapper;
        this.secretLookup = secretLookup;
    }

    @PostMapping("/{source}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String source, HttpServletRequest httpRequest) {
        Optional<CompiledWebhook> webhookLookup = compiledModel.findWebhookBySource(source);
        if (webhookLookup.isEmpty()) {
            return failure(HttpStatus.NOT_FOUND, "WEBHOOK_SOURCE_UNKNOWN",
                    "no webhook is configured for source '" + source + "'");
        }
        CompiledWebhook webhook = webhookLookup.get();

        byte[] rawBody;
        try {
            rawBody = httpRequest.getInputStream().readAllBytes();
        } catch (IOException readFailure) {
            return failure(HttpStatus.BAD_REQUEST, "WEBHOOK_BODY_UNREADABLE", "failed reading request body");
        }

        String secret = secretLookup.apply(webhook.hmacSecretEnvVar());
        if (secret == null || secret.isBlank()) {
            LOG.warn("webhook '{}' denied: no HMAC secret configured (env var {})", source, webhook.hmacSecretEnvVar());
            return failure(HttpStatus.UNAUTHORIZED, "WEBHOOK_SECRET_NOT_CONFIGURED",
                    "no HMAC secret configured for this webhook (env var " + webhook.hmacSecretEnvVar() + ")");
        }

        String signatureHeader = httpRequest.getHeader(SIGNATURE_HEADER);
        if (signatureHeader == null || signatureHeader.isBlank()) {
            LOG.info("webhook '{}' denied: missing {} header", source, SIGNATURE_HEADER);
            return failure(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_MISSING",
                    "missing " + SIGNATURE_HEADER + " header");
        }
        if (!verifySignature(secret, rawBody, signatureHeader)) {
            // Never log the presented or computed signature -- see class javadoc.
            LOG.info("webhook '{}' denied: signature verification failed", source);
            return failure(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID", "signature verification failed");
        }

        Map<String, Object> body;
        try {
            body = rawBody.length == 0 ? new LinkedHashMap<>() : objectMapper.readValue(rawBody, BODY_TYPE);
        } catch (IOException parseFailure) {
            return failure(HttpStatus.BAD_REQUEST, "WEBHOOK_BODY_NOT_JSON", "request body must be a JSON object");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        String correlationId = null;
        if (webhook.fieldMapping().isEmpty()) {
            payload.putAll(body);
        } else {
            for (Map.Entry<String, String> mapping : webhook.fieldMapping().entrySet()) {
                Object value = readDotPath(body, mapping.getValue());
                if (value != null) {
                    payload.put(mapping.getKey(), value);
                }
            }
        }
        Object mappedCorrelation = payload.get("correlationId");
        if (mappedCorrelation != null && !String.valueOf(mappedCorrelation).isBlank()) {
            correlationId = String.valueOf(mappedCorrelation);
        }
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        // The resume sweep (ResumeCoordinator.findAwaitedEventForInstance) scopes its EventStore
        // lookup by the WAITING instance's own tenantId -- a null-tenant published event is
        // filtered out for any instance whose tenant is non-blank (every apikey-mode create, even
        // the single-tenant "dev" default, carries one). A third-party webhook caller has no NPDev
        // tenant claim of its own to stamp the event with, so borrow the tenant from whichever
        // instance is actually parked on this correlationId -- the same "resume under the waiting
        // instance's own tenant" reasoning ResumeCoordinator itself already documents. No match
        // (event arriving before the flow parks, or an untenanted app) leaves tenantId null, which
        // EventStore's own filter treats as "unscoped" and matches any instance.
        String tenantId = resolveTenantForCorrelation(correlationId);

        EventEnvelope event = EventEnvelope.of(
                webhook.eventName(), payload, correlationId, UUID.randomUUID().toString(),
                "external", 0, Map.of(), tenantId, null);
        eventStore.append(event);

        LOG.info("webhook '{}' verified: published event '{}' (eventId={})", source, webhook.eventName(), event.eventId());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("eventId", event.eventId());
        response.put("eventName", event.eventName());
        return ResponseEntity.ok(response);
    }

    /** Resolves the tenant to stamp the published event with by finding whichever WAITING flow
     *  instance is already parked on this correlationId. Multiple matches (e.g. a fan-out or an
     *  ambiguous correlationId reused across tenants) uses the first found rather than refusing --
     *  the event still only actually resumes an instance the tenant AND correlationId both match,
     *  so a wrong pick here costs a missed resume, not a cross-tenant leak. */
    private String resolveTenantForCorrelation(String correlationId) {
        List<FlowInstance> waiting = flowInstanceStore.findWaitingByCorrelation(correlationId);
        for (FlowInstance instance : waiting) {
            if (instance != null && instance.tenantId() != null && !instance.tenantId().isBlank()) {
                return instance.tenantId();
            }
        }
        return null;
    }

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
        byte[] expectedBytes = hmac(secret, rawBody);
        // MessageDigest.isEqual is documented constant-time specifically to defeat HMAC/signature
        // timing attacks -- never String.equals/Arrays.equals here, which short-circuit on the
        // first differing byte and leak comparison-length information through response latency.
        return MessageDigest.isEqual(expectedBytes, presentedBytes);
    }

    private static byte[] hmac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed computing HMAC signature for inbound webhook", e);
        }
    }

    /** Reads a dot-path (e.g. {@code "customer.email"}) out of a parsed JSON body's nested maps.
     *  Returns null on any missing segment or a non-map intermediate -- absent, never an error, so
     *  one unmapped optional field does not fail the whole request. */
    private static Object readDotPath(Map<String, Object> body, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Object current = body;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static ResponseEntity<Map<String, Object>> failure(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        return ResponseEntity.status(status).body(body);
    }
}
