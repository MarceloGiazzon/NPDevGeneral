package com.finalexec.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.DefaultTenant;
import com.finalexec.config.ModelHolder;
import com.finalexec.webhook.InboundSignatureVerifier;
import com.finalexec.webhook.InboundWebhookSupport;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledWebhook;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.ExecutionStatus;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
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
 * {@link java.security.MessageDigest#isEqual(byte[], byte[])}, which the JDK documents as constant-time
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
 * <p><b>Provider signature schemes (INT-6).</b> The door's {@code signature} block picks the scheme
 * and header: {@code npdev} (default, above), {@code hmac-sha256-hex} (GitHub),
 * {@code hmac-sha256-base64} (Shopify) and {@code stripe-v1} (timestamped {@code t.body}, with a
 * tolerance window; a genuine signature outside it is 401 {@code WEBHOOK_SIGNATURE_EXPIRED}). The
 * crypto lives in {@link InboundSignatureVerifier} and always runs over the raw bytes read once
 * below.
 *
 * <p><b>Replay de-dupe (INT-6).</b> When the door declares {@code idempotencyKey} (a body dot-path or
 * {@code $header.<Name>}), a delivery whose key was already processed SUCCESSFULLY is acknowledged
 * with 200 {@code {ok:true,duplicate:true}} and nothing is published or started. The key is recorded
 * only after the event/flow succeeded, so a provider retry of a failed first attempt still runs.
 * Two concurrent deliveries of the same key can both pass the check (the store has no atomic
 * claim); providers retry sequentially after a failure, so this is the accepted residual window.
 * A door without {@code idempotencyKey} has no replay protection beyond the stripe timestamp window.
 *
 * <p><b>Event + flow (INT-6).</b> {@code eventName} is appended to the {@link EventStore} (which
 * resumes a parked {@code awaitEvent}) AND published on the {@link EventBus} so orchestration rules
 * subscribed to it fire. {@code startFlow} additionally runs that flow with the mapped payload as
 * input, as the home tenant's system actor, synchronously inside the provider's request -- keep it
 * short, a provider times out after ~10 s and retries. A flow that does not finish OK (or park on an
 * {@code awaitEvent}) is a 500 {@code WEBHOOK_FLOW_FAILED} so the provider retries. If a door has
 * both, an event published before a failing flow is published again on the retry.
 *
 * <p><b>Bodies.</b> A JSON object, or {@code application/x-www-form-urlencoded} (first value per
 * key); anything else is 400 {@code WEBHOOK_BODY_NOT_JSON}.
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

    private static final String SIGNATURE_HEADER = "X-Npdev-Webhook-Signature";
    private static final String IDEMPOTENCY_CAPABILITY = "webhook-inbound";
    private static final TypeReference<LinkedHashMap<String, Object>> BODY_TYPE = new TypeReference<>() {
    };

    // REG-208 (B28 lift): a live ModelHolder -- receive() below does a fresh per-request lookup, so
    // modelHolder.get() alone observes a reload; no ModelReloadListener needed.
    private final ModelHolder modelHolder;
    private final EventStore eventStore;
    private final FlowInstanceStore flowInstanceStore;
    private final ObjectMapper objectMapper;
    private final Function<String, String> secretLookup;
    private final EventBus eventBus;
    private final ObjectProvider<KernelRunner> kernelRunner;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    @Autowired
    public WebhookInboundController(
            ModelHolder modelHolder,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            ObjectMapper objectMapper,
            EventBus eventBus,
            ObjectProvider<KernelRunner> kernelRunner,
            IdempotencyStore idempotencyStore
    ) {
        this(modelHolder, eventStore, flowInstanceStore, objectMapper, System::getenv,
                eventBus, kernelRunner, idempotencyStore, Clock.systemUTC());
    }

    /** Convenience overload for existing test call sites with a plain {@link CompiledModel} and no
     * live {@link ModelHolder} -- wraps it in a non-reloading holder. */
    WebhookInboundController(
            CompiledModel compiledModel,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            ObjectMapper objectMapper,
            Function<String, String> secretLookup
    ) {
        this(new ModelHolder(compiledModel), eventStore, flowInstanceStore, objectMapper, secretLookup);
    }

    /** Test-only seam: a fixed secret lookup instead of real environment variables. */
    WebhookInboundController(
            ModelHolder modelHolder,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            ObjectMapper objectMapper,
            Function<String, String> secretLookup
    ) {
        this(modelHolder, eventStore, flowInstanceStore, objectMapper, secretLookup,
                null, null, IdempotencyStore.noop(), Clock.systemUTC());
    }

    /** Full test seam: every collaborator and the clock injectable. A null {@code eventBus} skips the
     *  bus publish; a null {@code kernelRunner} makes {@code startFlow} doors fail. */
    WebhookInboundController(
            ModelHolder modelHolder,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            ObjectMapper objectMapper,
            Function<String, String> secretLookup,
            EventBus eventBus,
            ObjectProvider<KernelRunner> kernelRunner,
            IdempotencyStore idempotencyStore,
            Clock clock
    ) {
        this.modelHolder = modelHolder;
        this.eventStore = eventStore;
        this.flowInstanceStore = flowInstanceStore;
        this.objectMapper = objectMapper;
        this.secretLookup = secretLookup;
        this.eventBus = eventBus;
        this.kernelRunner = kernelRunner;
        this.idempotencyStore = idempotencyStore == null ? IdempotencyStore.noop() : idempotencyStore;
        this.clock = clock;
    }

    @PostMapping("/{source}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String source, HttpServletRequest httpRequest) {
        Optional<CompiledWebhook> webhookLookup = modelHolder.get().findWebhookBySource(source);
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

        Map<String, Object> signature = webhook.signature();
        String scheme = signatureText(signature, "scheme", InboundSignatureVerifier.SCHEME_NPDEV);
        String headerName = signatureText(signature, "header", SIGNATURE_HEADER);
        String prefix = signatureText(signature, "prefix", null);
        long toleranceSeconds = signature.get("toleranceSeconds") instanceof Number n
                ? n.longValue() : InboundSignatureVerifier.DEFAULT_TOLERANCE_SECONDS;

        String signatureHeader = httpRequest.getHeader(headerName);
        if (signatureHeader == null || signatureHeader.isBlank()) {
            LOG.info("webhook '{}' denied: missing signature header", source);
            return failure(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_MISSING",
                    "missing " + headerName + " header");
        }
        InboundSignatureVerifier.Result verdict = InboundSignatureVerifier.check(
                scheme, secret, rawBody, httpRequest::getHeader, headerName, prefix,
                toleranceSeconds, Instant.now(clock));
        if (verdict == InboundSignatureVerifier.Result.EXPIRED) {
            LOG.info("webhook '{}' denied: signed timestamp outside tolerance", source);
            return failure(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_EXPIRED",
                    "signed timestamp is outside the allowed tolerance");
        }
        if (verdict != InboundSignatureVerifier.Result.VALID) {
            // Never log the presented or computed signature -- see class javadoc.
            LOG.info("webhook '{}' denied: signature verification failed", source);
            return failure(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID", "signature verification failed");
        }

        Map<String, Object> body;
        String contentType = httpRequest.getContentType();
        if (contentType != null && contentType.regionMatches(true, 0, "application/x-www-form-urlencoded", 0, 33)) {
            body = InboundWebhookSupport.parseFormBody(rawBody);
        } else {
            try {
                body = rawBody.length == 0 ? new LinkedHashMap<>() : objectMapper.readValue(rawBody, BODY_TYPE);
            } catch (IOException parseFailure) {
                return failure(HttpStatus.BAD_REQUEST, "WEBHOOK_BODY_NOT_JSON", "request body must be a JSON object");
            }
        }

        String homeTenant = DefaultTenant.id();
        String idempotencyKey = InboundWebhookSupport.resolveIdempotencyKey(
                webhook.idempotencyKey(), body, httpRequest::getHeader);
        if (idempotencyKey != null) {
            if (idempotencyKey.length() > InboundWebhookSupport.MAX_IDEMPOTENCY_KEY_LENGTH) {
                return failure(HttpStatus.BAD_REQUEST, "WEBHOOK_IDEMPOTENCY_KEY_INVALID",
                        "idempotency key exceeds " + InboundWebhookSupport.MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
            }
            if (idempotencyStore.find(homeTenant, IDEMPOTENCY_CAPABILITY, source, idempotencyKey).isPresent()) {
                LOG.info("webhook '{}' duplicate delivery acknowledged without re-running", source);
                Map<String, Object> duplicate = new LinkedHashMap<>();
                duplicate.put("ok", true);
                duplicate.put("duplicate", true);
                return ResponseEntity.ok(duplicate);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        String correlationId = null;
        if (webhook.fieldMapping().isEmpty()) {
            payload.putAll(body);
        } else {
            for (Map.Entry<String, String> mapping : webhook.fieldMapping().entrySet()) {
                Object value = InboundWebhookSupport.readDotPath(body, mapping.getValue());
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

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);

        if (!webhook.eventName().isBlank()) {
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
            if (eventBus != null) {
                // INT-6: the store append above resumes awaitEvent; the bus publish is what
                // orchestrationRules (kernelRunner.subscribeEvent) listen on.
                eventBus.publish(event);
            }
            LOG.info("webhook '{}' verified: published event '{}' (eventId={})", source, webhook.eventName(), event.eventId());
            response.put("eventId", event.eventId());
            response.put("eventName", event.eventName());
        }

        if (!webhook.startFlow().isBlank()) {
            KernelRunner runner = kernelRunner == null ? null : kernelRunner.getIfAvailable();
            if (runner == null) {
                return failure(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_FLOW_FAILED",
                        "flow runtime is not available");
            }
            ExecutionResult result;
            try {
                result = runner.execute(webhook.startFlow(), payload, ExecutionContext.system(homeTenant));
            } catch (RuntimeException flowFailure) {
                LOG.warn("webhook '{}' flow '{}' threw {}", source, webhook.startFlow(), flowFailure.getClass().getSimpleName());
                return failure(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_FLOW_FAILED",
                        "flow '" + webhook.startFlow() + "' failed");
            }
            ExecutionStatus status = result.getStatus();
            if (status != ExecutionStatus.OK && status != ExecutionStatus.WAITING_EVENT) {
                LOG.warn("webhook '{}' flow '{}' finished {}", source, webhook.startFlow(), status);
                return failure(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_FLOW_FAILED",
                        "flow '" + webhook.startFlow() + "' finished " + status);
            }
            LOG.info("webhook '{}' verified: started flow '{}' ({})", source, webhook.startFlow(), status);
            response.put("flow", webhook.startFlow());
            response.put("executionId", result.getExecutionId());
            response.put("status", status.name());
        }

        if (idempotencyKey != null) {
            // Recorded only now, after the event/flow succeeded: a failed first attempt must stay
            // retryable. The stored result is deliberately minimal (no payload, nothing sensitive).
            idempotencyStore.saveSuccess(homeTenant, IDEMPOTENCY_CAPABILITY, source, idempotencyKey,
                    "{\"ok\":true}", clock.millis());
        }
        return ResponseEntity.ok(response);
    }

    private static String signatureText(Map<String, Object> signature, String key, String fallback) {
        Object value = signature.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
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

    private static ResponseEntity<Map<String, Object>> failure(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        return ResponseEntity.status(status).body(body);
    }
}
