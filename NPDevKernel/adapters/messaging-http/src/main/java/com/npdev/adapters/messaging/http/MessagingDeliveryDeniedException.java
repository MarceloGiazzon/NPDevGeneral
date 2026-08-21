package com.npdev.adapters.messaging.http;

/**
 * Thrown instead of sending, whenever this adapter cannot prove an outbound delivery to a peer is
 * allowed -- the target peer app id has no configured {@link MessagingPeerProfile}, or its HMAC
 * secret environment variable is unset. Mirrors {@code WebhookEgressDeniedException}'s shape (a
 * stable machine {@code code} plus a human message): fail-closed, never a silent skip.
 */
public final class MessagingDeliveryDeniedException extends RuntimeException {
    private final String code;

    public MessagingDeliveryDeniedException(String code, String message) {
        super(message);
        this.code = normalize(code);
    }

    public String code() {
        return code;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "MESSAGING_DELIVERY_DENIED";
        }
        return value.trim();
    }
}
