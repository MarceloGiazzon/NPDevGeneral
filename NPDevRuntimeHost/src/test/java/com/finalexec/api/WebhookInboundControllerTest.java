package com.finalexec.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledWebhook;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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
}
