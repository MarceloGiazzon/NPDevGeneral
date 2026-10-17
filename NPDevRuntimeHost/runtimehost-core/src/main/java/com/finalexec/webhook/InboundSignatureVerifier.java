package com.finalexec.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Function;

/**
 * INT-6: pure verifier for the signature schemes an inbound webhook door ({@code POST
 * /api/hooks/{source}}) accepts. Always runs over the RAW request body bytes -- never a re-serialised
 * parse -- because every provider signs the exact bytes it sent.
 *
 * <ul>
 *   <li>{@code npdev} -- NPDev's own convention: hex HMAC-SHA256 of the body, optional
 *       {@code sha256=} prefix (the header the outbound {@code webhook-http} adapter sends).</li>
 *   <li>{@code hmac-sha256-hex} -- GitHub style: {@code X-Hub-Signature-256: sha256=<hex>}; the
 *       configured {@code prefix} is stripped before hex-decoding.</li>
 *   <li>{@code hmac-sha256-base64} -- Shopify style: {@code X-Shopify-Hmac-Sha256: <base64>}.</li>
 *   <li>{@code stripe-v1} -- {@code Stripe-Signature: t=<unix>,v1=<hex>[,v1=<hex>...]}, HMAC of
 *       {@code t + "." + body}; ANY {@code v1} entry may match (secret rotation) and the timestamp
 *       must be within {@code toleranceSeconds} of now.</li>
 * </ul>
 *
 * <p>Every comparison goes through {@link MessageDigest#isEqual(byte[], byte[])} (constant time).
 * Nothing here logs or returns the presented/computed signature or the secret.
 */
public final class InboundSignatureVerifier {

    public static final String SCHEME_NPDEV = "npdev";
    public static final String SCHEME_HEX = "hmac-sha256-hex";
    public static final String SCHEME_BASE64 = "hmac-sha256-base64";
    public static final String SCHEME_STRIPE = "stripe-v1";

    /** Default {@code stripe-v1} tolerance, matching Stripe's own libraries. */
    public static final long DEFAULT_TOLERANCE_SECONDS = 300L;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Outcome of {@link #check}; {@link #EXPIRED} means the signature itself was genuine. */
    public enum Result { VALID, INVALID, EXPIRED }

    private InboundSignatureVerifier() {
    }

    /**
     * @param scheme           one of the {@code SCHEME_*} constants; null/blank means {@code npdev}
     * @param secret           the HMAC secret (non-blank)
     * @param rawBody          the request body exactly as received
     * @param headerLookup     request-header lookup (name -> value, null when absent)
     * @param headerName       header carrying the signature
     * @param prefix           literal prefix stripped from the header value (may be null)
     * @param toleranceSeconds stripe-v1 timestamp tolerance; {@code <= 0} means the default
     * @param now              the clock reading to compare a stripe timestamp against
     */
    public static boolean verify(String scheme, String secret, byte[] rawBody,
                                 Function<String, String> headerLookup, String headerName, String prefix,
                                 long toleranceSeconds, Instant now) {
        return check(scheme, secret, rawBody, headerLookup, headerName, prefix, toleranceSeconds, now)
                == Result.VALID;
    }

    public static Result check(String scheme, String secret, byte[] rawBody,
                               Function<String, String> headerLookup, String headerName, String prefix,
                               long toleranceSeconds, Instant now) {
        if (secret == null || secret.isBlank() || rawBody == null || headerLookup == null
                || headerName == null || headerName.isBlank()) {
            return Result.INVALID;
        }
        String headerValue = headerLookup.apply(headerName);
        if (headerValue == null || headerValue.isBlank()) {
            return Result.INVALID;
        }
        String effectiveScheme = scheme == null || scheme.isBlank() ? SCHEME_NPDEV : scheme;
        return switch (effectiveScheme) {
            case SCHEME_NPDEV -> hexMatches(secret, rawBody, stripPrefix(headerValue, "sha256=", true))
                    ? Result.VALID : Result.INVALID;
            case SCHEME_HEX -> hexMatches(secret, rawBody, stripPrefix(headerValue, prefix, false))
                    ? Result.VALID : Result.INVALID;
            case SCHEME_BASE64 -> base64Matches(secret, rawBody, stripPrefix(headerValue, prefix, false))
                    ? Result.VALID : Result.INVALID;
            case SCHEME_STRIPE -> checkStripe(secret, rawBody, headerValue, toleranceSeconds, now);
            default -> Result.INVALID;
        };
    }

    private static Result checkStripe(String secret, byte[] rawBody, String headerValue,
                                      long toleranceSeconds, Instant now) {
        String timestampText = null;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : headerValue.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if ("t".equals(key)) {
                timestampText = value;
            } else if ("v1".equals(key)) {
                signatures.add(value);
            }
        }
        if (timestampText == null || signatures.isEmpty()) {
            return Result.INVALID;
        }
        if (timestampText.isEmpty() || timestampText.length() > 18
                || !timestampText.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return Result.INVALID;
        }
        long timestamp = Long.parseLong(timestampText);
        byte[] timestampBytes = (timestampText + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signedPayload = new byte[timestampBytes.length + rawBody.length];
        System.arraycopy(timestampBytes, 0, signedPayload, 0, timestampBytes.length);
        System.arraycopy(rawBody, 0, signedPayload, timestampBytes.length, rawBody.length);
        byte[] expected = hmac(secret, signedPayload);

        boolean anyMatch = false;
        for (String candidate : signatures) {
            byte[] presented = parseHex(candidate);
            // Evaluate every candidate (no early exit) so timing does not reveal which one matched.
            anyMatch |= presented != null && MessageDigest.isEqual(expected, presented);
        }
        if (!anyMatch) {
            return Result.INVALID;
        }
        long tolerance = toleranceSeconds <= 0 ? DEFAULT_TOLERANCE_SECONDS : toleranceSeconds;
        long skew = Math.abs(now.getEpochSecond() - timestamp);
        return skew > tolerance ? Result.EXPIRED : Result.VALID;
    }

    private static boolean hexMatches(String secret, byte[] rawBody, String presentedHex) {
        byte[] presented = parseHex(presentedHex);
        return presented != null && MessageDigest.isEqual(hmac(secret, rawBody), presented);
    }

    private static boolean base64Matches(String secret, byte[] rawBody, String presentedBase64) {
        byte[] presented = new byte[0];
        boolean decoded = false;
        try {
            presented = Base64.getDecoder().decode(presentedBase64.trim());
            decoded = true;
        } catch (IllegalArgumentException notBase64) {
            decoded = false;
        }
        // Compare unconditionally so a malformed value costs the same as a wrong one.
        boolean equal = MessageDigest.isEqual(hmac(secret, rawBody), presented);
        return decoded && equal;
    }

    private static String stripPrefix(String value, String prefix, boolean ignoreCase) {
        if (prefix != null && !prefix.isEmpty() && value.regionMatches(ignoreCase, 0, prefix, 0, prefix.length())) {
            return value.substring(prefix.length());
        }
        return value;
    }

    /** Hex-decodes {@code hex}; null when it is not well-formed hex (checked, not caught). */
    private static byte[] parseHex(String hex) {
        String trimmed = hex.trim();
        if (trimmed.isEmpty() || trimmed.length() % 2 != 0) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!HexFormat.isHexDigit(trimmed.charAt(i))) {
                return null;
            }
        }
        return HexFormat.of().parseHex(trimmed);
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
}
