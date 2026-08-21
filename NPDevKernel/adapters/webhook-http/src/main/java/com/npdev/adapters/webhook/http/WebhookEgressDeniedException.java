package com.npdev.adapters.webhook.http;

/**
 * Thrown instead of sending, whenever this adapter cannot prove a POST is allowed: no destination
 * allowlist configured at all, the caller-supplied URL's host is not on it, or the configured HMAC
 * secret's environment variable is unset. Mirrors {@code ExternalAiEgressDeniedException}'s shape
 * (a stable machine {@code code} plus a human message) so a caller already handling that exception
 * class recognises the same fail-closed posture here.
 */
public final class WebhookEgressDeniedException extends RuntimeException {
    private final String code;

    public WebhookEgressDeniedException(String code, String message) {
        super(message);
        this.code = normalize(code);
    }

    public String code() {
        return code;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "WEBHOOK_EGRESS_DENIED";
        }
        return value.trim();
    }
}
