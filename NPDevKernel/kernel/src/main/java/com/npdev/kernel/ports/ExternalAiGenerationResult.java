package com.npdev.kernel.ports;

/**
 * What a vendor answered a {@link ExternalAiGenerationRequest} with.
 *
 * <p>{@code text} is the assistant message extracted from the vendor's own response shape;
 * {@code rawResponse} is the untouched body, kept so a caller can show what actually came back when
 * extraction finds nothing useful. Neither ever contains the API key -- the key travels in a request
 * header and is never echoed by any supported vendor -- but {@code rawResponse} is vendor-controlled
 * text, so a caller that logs it is logging something it did not author. Callers in this codebase
 * log the vendor id and model only.
 */
public record ExternalAiGenerationResult(
        String vendorId,
        String model,
        String text,
        String rawResponse
) {
    public ExternalAiGenerationResult {
        if (vendorId == null || vendorId.isBlank()) {
            throw new IllegalArgumentException("vendorId must be non-blank");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must be non-null (use an empty string, never null)");
        }
    }
}
