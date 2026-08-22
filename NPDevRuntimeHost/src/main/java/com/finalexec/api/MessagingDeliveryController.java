package com.finalexec.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.adapters.messaging.http.HttpMessagingCapabilityAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R6.4: the public "door" for {@code MessagingCapability}'s cross-app bridge -- {@code POST
 * /npdev/messaging/deliver}, the SAME path {@link HttpMessagingCapabilityAdapter#INBOUND_PATH}
 * publishes and every peer's outbound {@code sendToPeer} already targets.
 *
 * <p><b>Deliberately NOT gated by NPDev auth.</b> Same posture as {@link WebhookInboundController}
 * (R6.2): the caller is another NPDev app's own {@code messaging-http} adapter, not a human with a
 * role, so this controller carries no {@code RuntimeContextService}/role check at all. Authentication
 * here is the HMAC signature the adapter itself verifies -- see below.
 *
 * <p><b>Calls the adapter directly, in-process -- no second HTTP server.</b> An earlier version of
 * this controller opened a loopback HTTP connection to an embedded {@code com.sun.net.httpserver
 * .HttpServer} the adapter ran inside the same JVM, purely to reuse that server's signature-check code
 * -- which meant every inbound delivery took an extra local network hop to talk to itself, and the app
 * ran two HTTP servers for no real reason. That embedded receiver was never required: this controller
 * and {@link HttpMessagingCapabilityAdapter} both live inside the same JVM, so {@code
 * NpdevCapabilityBindingConfig#httpMessagingCapabilityAdapter} now constructs the adapter with NO
 * inbound listen address (no embedded server at all in this app), and this controller reads the
 * request's {@value HttpMessagingCapabilityAdapter#SENDER_HEADER}/
 * {@value HttpMessagingCapabilityAdapter#SIGNATURE_HEADER} headers and raw body itself, then calls
 * {@link HttpMessagingCapabilityAdapter#receiveInboundDelivery} directly -- the EXACT SAME
 * verify/dedupe/dispatch code the embedded server's route handler runs (both now call the one
 * extracted method), just invoked as a plain Java method call instead of a second HTTP round trip. The
 * signature check, the "unknown sender" check, and the idempotent dedupe still happen inside the
 * adapter, exactly once, never duplicated here -- only the transport changed.
 *
 * <p><b>Fail-closed when messaging-http is not configured for this app.</b> No bean means no model
 * binding requested {@code messaging-http} (see the config javadoc) -- a request here is then a 404
 * naming that, never a silent 200 or an attempt to construct an adapter on the fly.
 *
 * <p><b>Registration, and why this package.</b> Listed in {@code npdev/runtime-supported-controllers
 * .json}'s {@code allowedControllers} -- required by all three enforcement points ({@code
 * RuntimeControllerAllowlistConfig}, {@code build.gradle.template}'s compile-exclusion, {@code
 * run-runtime-surface-evidence.ps1}), all three of which key off {@code com.finalexec.api}, so this
 * controller lives there like {@code WebhookInboundController} and {@code AgentProxyController}.
 *
 * <p><b>Known auth-filter gap (outside this module's surface).</b> {@code JwtBearerAuthFilter}
 * (this module, {@code com.finalexec.config}) exempts {@link HttpMessagingCapabilityAdapter#INBOUND_PATH}
 * the same way it exempts {@code /api/hooks/}. The DEFAULT auth mode's filter,
 * {@code com.npdev.generated.runtime.config.RuntimeApiKeyAuthFilter}, is generated per-app from
 * {@code NPDevGenerator}'s {@code npdev-runtime-api-key-auth-filter.mustache} template and carries no
 * such exemption -- that template is outside this module's edit surface for this round. A generated
 * app running the default {@code auth.mode=apikey} (or the unset default, which resolves to apikey)
 * will therefore reject an inbound peer delivery with a missing-API-key 401 before this controller
 * ever runs, until that template gains the same {@code /npdev/messaging/} exemption
 * {@code WebhookInboundController}'s {@code /api/hooks/} already has. {@code auth.mode=jwt} and
 * {@code auth.mode=none} apps are unaffected.
 */
@RestController
public class MessagingDeliveryController {

    private static final Logger LOG = LoggerFactory.getLogger(MessagingDeliveryController.class);

    private final ObjectProvider<HttpMessagingCapabilityAdapter> messagingAdapterProvider;
    private final ObjectMapper objectMapper;

    @Autowired
    public MessagingDeliveryController(
            ObjectProvider<HttpMessagingCapabilityAdapter> messagingAdapterProvider,
            ObjectMapper objectMapper
    ) {
        this.messagingAdapterProvider = messagingAdapterProvider;
        this.objectMapper = objectMapper;
    }

    @PostMapping(HttpMessagingCapabilityAdapter.INBOUND_PATH)
    public ResponseEntity<byte[]> deliver(HttpServletRequest request) throws IOException {
        HttpMessagingCapabilityAdapter adapter = messagingAdapterProvider.getIfAvailable();
        if (adapter == null) {
            LOG.info("rejected inbound messaging delivery: this app has no messaging-http capability binding");
            return failure(HttpStatus.NOT_FOUND, "MESSAGING_NOT_CONFIGURED",
                    "this app has no MessagingCapability bound to adapter 'messaging-http'");
        }

        byte[] rawBody = request.getInputStream().readAllBytes();
        String senderAppId = request.getHeader(HttpMessagingCapabilityAdapter.SENDER_HEADER);
        String signatureHeader = request.getHeader(HttpMessagingCapabilityAdapter.SIGNATURE_HEADER);

        HttpMessagingCapabilityAdapter.InboundDeliveryResult result =
                adapter.receiveInboundDelivery(senderAppId, signatureHeader, rawBody);

        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(toBytes(result.body()));
    }

    private byte[] toBytes(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (IOException e) {
            LOG.warn("failed serializing messaging delivery response body, falling back to a minimal error body: {}",
                    e.toString());
            return "{\"ok\":false,\"code\":\"MESSAGING_RESPONSE_SERIALIZATION_FAILED\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private ResponseEntity<byte[]> failure(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(toBytes(body));
    }
}
