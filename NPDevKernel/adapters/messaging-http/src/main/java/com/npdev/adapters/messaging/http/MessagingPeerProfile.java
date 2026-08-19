package com.npdev.adapters.messaging.http;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * One entry in the fail-closed, operator-configured peer list: another NPDev app this app is
 * willing to bridge {@code MessagingCapability} traffic with, both ways -- {@code baseUrl} is where
 * THIS app POSTs a message when it targets {@code peerAppId}, and {@code peerAppId} is also the
 * value THIS app expects in the {@code X-Npdev-Messaging-Sender} header of an inbound delivery it
 * will accept, verified against the SAME {@code hmacSecretEnvVar} secret.
 *
 * <p><b>Deliberately not URL-scoped like {@code WebhookDestinationProfile}.</b> A webhook's
 * destination URL is caller-supplied application data, so that adapter allowlists by HOST as an SSRF
 * guard. A messaging peer's {@code baseUrl} is operator configuration only --
 * {@code HttpMessagingCapabilityAdapter.publish} never accepts a destination from the published
 * message -- so there is no equivalent untrusted-input surface here; the allowlist exists purely to
 * name which peers exist and to bind each to its secret.
 *
 * <p>{@code topics}: the set of topics this peer receives when this app publishes. An empty set means
 * "every topic" (the peer is a catch-all subscriber) -- this is the whole of the bridge's routing
 * table; there is no dynamic, runtime {@code subscribe()}-over-the-wire protocol in this round.
 *
 * <p>{@code hmacSecretEnvVar} names an environment variable, never a literal secret, resolved at call
 * time (default {@link System#getenv}), matching {@code WebhookDestinationProfile}/
 * {@code ExternalAiVendorProfile}'s secret-by-reference posture. Both peers in a bridged pair must be
 * configured with the SAME secret value under their own env var name.
 */
public record MessagingPeerProfile(String peerAppId, String baseUrl, String hmacSecretEnvVar, Set<String> topics) {

    public MessagingPeerProfile {
        if (peerAppId == null || peerAppId.isBlank()) {
            throw new IllegalArgumentException("peerAppId must be non-blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must be non-blank");
        }
        if (hmacSecretEnvVar == null || hmacSecretEnvVar.isBlank()) {
            throw new IllegalArgumentException("hmacSecretEnvVar must be non-blank");
        }
        peerAppId = normalize(peerAppId);
        baseUrl = baseUrl.trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        topics = topics == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(topics));
    }

    /** Peer app ids are matched case-insensitively -- see {@link HttpMessagingCapabilityAdapter}. */
    static String normalize(String rawAppId) {
        return rawAppId.trim().toLowerCase(Locale.ROOT);
    }

    public static MessagingPeerProfile of(String peerAppId, String baseUrl, String hmacSecretEnvVar) {
        return new MessagingPeerProfile(peerAppId, baseUrl, hmacSecretEnvVar, Set.of());
    }

    public static MessagingPeerProfile of(String peerAppId, String baseUrl, String hmacSecretEnvVar, Set<String> topics) {
        return new MessagingPeerProfile(peerAppId, baseUrl, hmacSecretEnvVar, topics);
    }
}
