package com.finalexec.auth;

import com.finalexec.agent.AgentChannelsStarter;
import com.finalexec.agent.WhatsAppChannel;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * AGENT-1 (A10): the door Meta's WhatsApp Cloud API delivers to. Unauthenticated by NPDev on
 * purpose -- it sits under {@code /api/hooks/}, which {@code JwtBearerAuthFilter} exempts -- and
 * authenticated by Meta instead: every POST must carry {@code X-Hub-Signature-256}, checked against
 * the raw body before a single byte is parsed. Two path segments, so it can never collide with
 * {@code WebhookInboundController}'s one-segment {@code /api/hooks/{source}}.
 *
 * <p>Answers 404 whenever the channel is not running (model does not enable WhatsApp, or a
 * {@code NPDEV_WHATSAPP_*} secret is missing), so an unconfigured app exposes nothing here. Lives
 * in {@code com.finalexec.auth}, jwt-only, for the reason {@link AgentLinkController}'s javadoc
 * gives: WhatsApp requires {@code auth.mode=jwt}, and a conditional controller in
 * {@code com.finalexec.api} reads as a phantom runtime-surface manifest entry.
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
@RequestMapping("/api/hooks/agent/whatsapp")
public class AgentWhatsAppWebhookController {

    private static final Logger LOG = Logger.getLogger(AgentWhatsAppWebhookController.class.getName());
    private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

    private final AgentChannelsStarter channelsStarter;

    public AgentWhatsAppWebhookController(AgentChannelsStarter channelsStarter) {
        this.channelsStarter = channelsStarter;
    }

    /** Meta's subscription handshake: echo {@code hub.challenge} when {@code hub.verify_token} matches. */
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        WhatsAppChannel channel = channelsStarter.whatsappChannel();
        if (channel == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String echoed = channel.verifyChallenge(mode, verifyToken, challenge);
        if (echoed == null) {
            LOG.info("WhatsApp webhook verification refused (wrong verify token or malformed request).");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(echoed);
    }

    /** One delivery: verify the signature, queue the messages, acknowledge at once. */
    @PostMapping
    public ResponseEntity<Void> receive(HttpServletRequest request) {
        WhatsAppChannel channel = channelsStarter.whatsappChannel();
        if (channel == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (IOException unreadable) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!channel.signatureValid(rawBody, request.getHeader(SIGNATURE_HEADER))) {
            // Never log the presented signature or the body.
            LOG.info("WhatsApp webhook delivery refused: signature verification failed.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        channel.accept(rawBody);
        return ResponseEntity.ok().build();
    }
}
