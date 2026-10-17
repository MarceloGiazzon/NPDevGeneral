package com.finalexec.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledWebhook;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R6.2: {@link WebhookInboundController} is deliberately reachable with NO NPDev auth (a third
 * party has no credential to present), so its own HMAC verification is the only gate -- these tests
 * exercise that gate directly with a standalone {@link MockMvc} harness, same shape as {@link
 * AgentProxyControllerTest}, needing no database and no Spring context.
 */
class WebhookInboundControllerTest {

    private static final String SOURCE = "stripe";
    private static final String SECRET_ENV_VAR = "TEST_WEBHOOK_SECRET_ENV";
    private static final String SECRET = "unit-test-secret-not-real";
    private static final String EVENT_NAME = "PaymentConfirmed";

    private final CompiledModel compiledModel = Mockito.mock(CompiledModel.class);
    private final EventStore eventStore = Mockito.mock(EventStore.class);
    private final FlowInstanceStore flowInstanceStore = Mockito.mock(FlowInstanceStore.class);

    private MockMvc mockMvc(CompiledWebhook webhook, Function<String, String> secretLookup) {
        when(compiledModel.findWebhookBySource(SOURCE)).thenReturn(Optional.ofNullable(webhook));
        when(compiledModel.findWebhookBySource(Mockito.argThat(s -> s != null && !s.equals(SOURCE))))
                .thenReturn(Optional.empty());
        when(flowInstanceStore.findWaitingByCorrelation(any())).thenReturn(List.of());
        return MockMvcBuilders.standaloneSetup(
                new WebhookInboundController(compiledModel, eventStore, flowInstanceStore, new ObjectMapper(), secretLookup)
        ).build();
    }

    private static CompiledWebhook webhookWithMapping(Map<String, String> fieldMapping) {
        return new CompiledWebhook(SOURCE, SECRET_ENV_VAR, EVENT_NAME, fieldMapping);
    }

    private static Function<String, String> resolvingSecretFor(String envVar, String secret) {
        return name -> envVar.equals(name) ? secret : null;
    }

    private static String hmacHex(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aCorrectlySignedRequestPublishesTheMappedEventAndReturns200() throws Exception {
        CompiledWebhook webhook = webhookWithMapping(Map.of("correlationId", "orderId", "reference", "txnRef"));
        MockMvc mvc = mockMvc(webhook, resolvingSecretFor(SECRET_ENV_VAR, SECRET));

        String body = "{\"orderId\":\"order-123\",\"txnRef\":\"TXN-1\"}";
        String signature = hmacHex(SECRET, body);

        mvc.perform(post("/api/hooks/" + SOURCE)
                        .header("X-Npdev-Webhook-Signature", "sha256=" + signature)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.eventName").value(EVENT_NAME));

        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(eventStore).append(captor.capture());
        EventEnvelope published = captor.getValue();
        assertEquals(EVENT_NAME, published.eventName());
        assertEquals("order-123", published.correlationId());
        assertEquals("order-123", published.payload().get("correlationId"));
        assertEquals("TXN-1", published.payload().get("reference"));
    }

    @Test
    void aWrongSignatureIsRejected401AndNothingIsPublished() throws Exception {
        CompiledWebhook webhook = webhookWithMapping(Map.of("correlationId", "orderId"));
        MockMvc mvc = mockMvc(webhook, resolvingSecretFor(SECRET_ENV_VAR, SECRET));

        String body = "{\"orderId\":\"order-123\"}";
        // Well-formed hex, but not the HMAC of this body under the real secret.
        String wrongSignature = "0".repeat(64);

        mvc.perform(post("/api/hooks/" + SOURCE)
                        .header("X-Npdev-Webhook-Signature", "sha256=" + wrongSignature)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));

        verify(eventStore, never()).append(any());
    }

    @Test
    void aMissingSignatureHeaderIsRejected401AndNothingIsPublished() throws Exception {
        CompiledWebhook webhook = webhookWithMapping(Map.of("correlationId", "orderId"));
        MockMvc mvc = mockMvc(webhook, resolvingSecretFor(SECRET_ENV_VAR, SECRET));

        mvc.perform(post("/api/hooks/" + SOURCE)
                        .contentType("application/json")
                        .content("{\"orderId\":\"order-123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_MISSING"));

        verify(eventStore, never()).append(any());
    }

    @Test
    void anUnknownSourceIs404BeforeAnySignatureCheck() throws Exception {
        MockMvc mvc = mockMvc(null, resolvingSecretFor(SECRET_ENV_VAR, SECRET));

        mvc.perform(post("/api/hooks/not-configured")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SOURCE_UNKNOWN"));

        verify(eventStore, never()).append(any());
    }

    @Test
    void noConfiguredSecretDeniesFailClosedRatherThanAcceptingUnsigned() throws Exception {
        CompiledWebhook webhook = webhookWithMapping(Map.of("correlationId", "orderId"));
        // secretLookup resolves nothing -- the env var is genuinely unset.
        MockMvc mvc = mockMvc(webhook, name -> null);

        mvc.perform(post("/api/hooks/" + SOURCE)
                        .header("X-Npdev-Webhook-Signature", "sha256=" + "a".repeat(64))
                        .contentType("application/json")
                        .content("{\"orderId\":\"order-123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SECRET_NOT_CONFIGURED"));

        verify(eventStore, never()).append(any());
    }

    @Test
    void tenantIsBorrowedFromTheWaitingInstanceParkedOnTheSameCorrelationId() throws Exception {
        // R6.2 live-proof finding: ResumeCoordinator scopes its EventStore lookup by the WAITING
        // instance's own tenant, so a null-tenant published event is silently never matched against
        // a tenant-scoped instance. Proven live against a booted app before this test existed --
        // fixed by resolving the tenant from whichever instance is parked on this correlationId.
        CompiledWebhook webhook = webhookWithMapping(Map.of("correlationId", "orderId"));
        FlowInstance waiting = Mockito.mock(FlowInstance.class);
        when(waiting.tenantId()).thenReturn("acme-tenant");
        when(flowInstanceStore.findWaitingByCorrelation("order-123")).thenReturn(List.of(waiting));
        when(compiledModel.findWebhookBySource(SOURCE)).thenReturn(Optional.of(webhook));

        String body = "{\"orderId\":\"order-123\"}";
        String signature = hmacHex(SECRET, body);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new WebhookInboundController(
                        compiledModel, eventStore, flowInstanceStore, new ObjectMapper(),
                        resolvingSecretFor(SECRET_ENV_VAR, SECRET))
        ).build();

        mvc.perform(post("/api/hooks/" + SOURCE)
                        .header("X-Npdev-Webhook-Signature", "sha256=" + signature)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(eventStore).append(captor.capture());
        assertEquals("acme-tenant", captor.getValue().tenantId());
    }

    // ---- INT-6: provider signature schemes, form bodies, startFlow, replay de-dupe, event bus ----

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    /** Map-backed store so find() sees what saveSuccess() recorded. */
    private static final class MapIdempotencyStore implements IdempotencyStore {
        final Map<String, IdempotencyRecord> rows = new HashMap<>();

        @Override
        public Optional<IdempotencyRecord> find(String tenantId, String capability, String operation, String key) {
            return Optional.ofNullable(rows.get(tenantId + "|" + capability + "|" + operation + "|" + key));
        }

        @Override
        public void saveSuccess(String tenantId, String capability, String operation, String key,
                                String resultJsonRedacted, long createdAtMs) {
            rows.put(tenantId + "|" + capability + "|" + operation + "|" + key, new IdempotencyRecord(
                    tenantId, key, capability, operation, createdAtMs, IdempotencyRecord.STATUS_SUCCESS,
                    resultJsonRedacted, null));
        }

        @Override
        public void saveFailure(String tenantId, String capability, String operation, String key,
                                String errorCode, long createdAtMs) {
        }
    }

    private final EventBus eventBus = Mockito.mock(EventBus.class);
    private final KernelRunner kernelRunner = Mockito.mock(KernelRunner.class);
    private final MapIdempotencyStore idempotency = new MapIdempotencyStore();

    @SuppressWarnings("unchecked")
    private MockMvc providerMvc(CompiledWebhook webhook) {
        when(compiledModel.findWebhookBySource(SOURCE)).thenReturn(Optional.of(webhook));
        when(flowInstanceStore.findWaitingByCorrelation(any())).thenReturn(List.of());
        ObjectProvider<KernelRunner> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(kernelRunner);
        return MockMvcBuilders.standaloneSetup(new WebhookInboundController(
                new ModelHolder(compiledModel), eventStore, flowInstanceStore, new ObjectMapper(),
                resolvingSecretFor(SECRET_ENV_VAR, SECRET), eventBus, provider, idempotency,
                Clock.fixed(NOW, ZoneOffset.UTC))).build();
    }

    private static CompiledWebhook stripeDoor(String startFlow, String eventName) {
        return new CompiledWebhook(SOURCE, SECRET_ENV_VAR, eventName,
                Map.of("orderId", "data.object.metadata.orderId"), startFlow, "id",
                Map.of("scheme", "stripe-v1", "header", "Stripe-Signature", "toleranceSeconds", 300));
    }

    private static String stripeHeader(long t, String body) {
        return "t=" + t + ",v1=" + hmacHex(SECRET, t + "." + body);
    }

    private static final String STRIPE_BODY =
            "{\"id\":\"evt_1\",\"data\":{\"object\":{\"metadata\":{\"orderId\":\"o-1\"}}}}";

    @Test
    void stripeDoorStartsTheFlowOnceAndAcknowledgesARetryAsDuplicate() throws Exception {
        when(kernelRunner.execute(eq("RecordStripePayment"), any(), any()))
                .thenReturn(ExecutionResult.ok("RecordStripePayment", Map.of(), List.of()));
        MockMvc mvc = providerMvc(stripeDoor("RecordStripePayment", ""));
        String header = stripeHeader(NOW.getEpochSecond() - 5, STRIPE_BODY);

        mvc.perform(post("/api/hooks/" + SOURCE).header("Stripe-Signature", header)
                        .contentType("application/json").content(STRIPE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.flow").value("RecordStripePayment"))
                .andExpect(jsonPath("$.status").value("OK"));

        ArgumentCaptor<Object> input = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<ExecutionContext> context = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(kernelRunner).execute(eq("RecordStripePayment"), input.capture(), context.capture());
        assertEquals(Map.of("orderId", "o-1"), input.getValue());
        verify(eventStore, never()).append(any());

        mvc.perform(post("/api/hooks/" + SOURCE).header("Stripe-Signature", header)
                        .contentType("application/json").content(STRIPE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
        verify(kernelRunner, Mockito.times(1)).execute(any(), any(), any());
    }

    @Test
    void aFailedFlowIs500AndTheDeliveryStaysRetryable() throws Exception {
        when(kernelRunner.execute(eq("RecordStripePayment"), any(), any()))
                .thenReturn(ExecutionResult.failed("RecordStripePayment", List.of(), List.of(), "boom"))
                .thenReturn(ExecutionResult.ok("RecordStripePayment", Map.of(), List.of()));
        MockMvc mvc = providerMvc(stripeDoor("RecordStripePayment", ""));
        String header = stripeHeader(NOW.getEpochSecond(), STRIPE_BODY);

        mvc.perform(post("/api/hooks/" + SOURCE).header("Stripe-Signature", header)
                        .contentType("application/json").content(STRIPE_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("WEBHOOK_FLOW_FAILED"));
        // not recorded as processed: the provider's retry runs the flow again and succeeds
        mvc.perform(post("/api/hooks/" + SOURCE).header("Stripe-Signature", header)
                        .contentType("application/json").content(STRIPE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void aStaleStripeTimestampIsExpiredAndATamperedBodyIsInvalid() throws Exception {
        MockMvc mvc = providerMvc(stripeDoor("RecordStripePayment", ""));
        String stale = stripeHeader(NOW.getEpochSecond() - 600, STRIPE_BODY);
        mvc.perform(post("/api/hooks/" + SOURCE).header("Stripe-Signature", stale)
                        .contentType("application/json").content(STRIPE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_EXPIRED"));

        String fresh = stripeHeader(NOW.getEpochSecond(), STRIPE_BODY);
        mvc.perform(post("/api/hooks/" + SOURCE).header("Stripe-Signature", fresh)
                        .contentType("application/json").content(STRIPE_BODY.replace("evt_1", "evt_2")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        verify(kernelRunner, never()).execute(any(), any(), any());
    }

    @Test
    void eventDoorAlsoPublishesOnTheEventBusSoOrchestrationRulesFire() throws Exception {
        CompiledWebhook hexDoor = new CompiledWebhook(SOURCE, SECRET_ENV_VAR, EVENT_NAME, Map.of(),
                "", "$header.X-Delivery-Id",
                Map.of("scheme", "hmac-sha256-hex", "header", "X-Hub-Signature-256", "prefix", "sha256="));
        MockMvc mvc = providerMvc(hexDoor);
        String body = "{\"ref\":\"main\"}";

        mvc.perform(post("/api/hooks/" + SOURCE)
                        .header("X-Hub-Signature-256", "sha256=" + hmacHex(SECRET, body))
                        .header("X-Delivery-Id", "d-1")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventName").value(EVENT_NAME));

        ArgumentCaptor<EventEnvelope> stored = ArgumentCaptor.forClass(EventEnvelope.class);
        ArgumentCaptor<EventEnvelope> published = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(eventStore).append(stored.capture());
        verify(eventBus).publish(published.capture());
        assertEquals(stored.getValue().eventId(), published.getValue().eventId());
        assertEquals("main", published.getValue().payload().get("ref"));

        mvc.perform(post("/api/hooks/" + SOURCE)
                        .header("X-Hub-Signature-256", "sha256=" + hmacHex(SECRET, body))
                        .header("X-Delivery-Id", "d-1")
                        .contentType("application/json").content(body))
                .andExpect(jsonPath("$.duplicate").value(true));
        verify(eventBus, Mockito.times(1)).publish(any());
    }

    @Test
    void base64SignatureWithAFormBodyIsAccepted() throws Exception {
        CompiledWebhook door = new CompiledWebhook(SOURCE, SECRET_ENV_VAR, EVENT_NAME, Map.of(),
                "", "", Map.of("scheme", "hmac-sha256-base64", "header", "X-Shopify-Hmac-Sha256"));
        MockMvc mvc = providerMvc(door);
        String body = "topic=orders%2Fpaid&shop=demo.myshopify.com";
        String signature = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hmacHex(SECRET, body)));

        mvc.perform(post("/api/hooks/" + SOURCE)
                        .header("X-Shopify-Hmac-Sha256", signature)
                        .contentType("application/x-www-form-urlencoded").content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<EventEnvelope> stored = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(eventStore).append(stored.capture());
        assertEquals("orders/paid", stored.getValue().payload().get("topic"));
        assertEquals("demo.myshopify.com", stored.getValue().payload().get("shop"));
    }
}
