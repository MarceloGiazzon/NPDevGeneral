package com.npdev.adapters.webhook.http;

import java.util.Locale;

/**
 * One entry in the fail-closed destination allowlist: a host this app is permitted to POST a
 * webhook to, plus which environment variable carries the HMAC secret used to sign requests sent
 * there.
 *
 * <p>Deliberately host-scoped, not URL-scoped: a subscriber's exact URL (path, query) is supplied
 * per call (it usually comes from application data -- e.g. a saved subscription record), while the
 * HOST an operator is willing to send data to is a server-side configuration decision. Checking
 * only the caller-supplied host against this list is what makes the allowlist an SSRF guard rather
 * than decoration -- see {@link HttpWebhookCapabilityAdapter#requireAllowedDestination}.
 *
 * <p>{@code hmacSecretEnvVar} names an environment variable; it is never a literal secret. The
 * secret value itself is resolved at call time via the adapter's {@code hmacSecretLookup} function
 * (defaulting to {@code System::getenv}) and is never stored on this record, matching
 * {@code ExternalAiVendorProfile}'s api-key-by-reference posture.
 */
public record WebhookDestinationProfile(String host, String hmacSecretEnvVar) {

    public WebhookDestinationProfile {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must be non-blank");
        }
        if (hmacSecretEnvVar == null || hmacSecretEnvVar.isBlank()) {
            throw new IllegalArgumentException("hmacSecretEnvVar must be non-blank");
        }
        host = normalizeHost(host);
    }

    /** Hosts are matched case-insensitively (DNS names are not case-sensitive). */
    static String normalizeHost(String rawHost) {
        return rawHost.trim().toLowerCase(Locale.ROOT);
    }

    public static WebhookDestinationProfile of(String host, String hmacSecretEnvVar) {
        return new WebhookDestinationProfile(host, hmacSecretEnvVar);
    }
}
