package com.npdev.kernel.ports;

/**
 * P4 (G3): what a vendor answered an {@link ExternalAiStructuredRequest} with. {@code json} is the
 * answer text as the vendor returned it -- NOT yet validated against the request's schema; the caller
 * owns that check. Token counts are the vendor's own usage figures ({@code 0} when it reports none)
 * and are what cost estimation and the monthly cap are computed from.
 */
public record ExternalAiStructuredResult(
        String vendorId,
        String model,
        String json,
        long inputTokens,
        long outputTokens
) {
    public ExternalAiStructuredResult {
        if (vendorId == null || vendorId.isBlank()) {
            throw new IllegalArgumentException("vendorId must be non-blank");
        }
        if (json == null) {
            throw new IllegalArgumentException("json must be non-null (use an empty string, never null)");
        }
    }
}
