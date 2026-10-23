package com.npdev.kernel.ports;

/**
 * P4 (G3): a prompt whose answer must be JSON of a declared shape, for
 * {@link ExternalAiCapabilityContract#generateStructured}. Distinct from
 * {@link ExternalAiGenerationRequest} (prose) because the vendor is asked to constrain its own output
 * to {@code responseSchemaJson} (Gemini {@code responseSchema}, OpenAI {@code json_schema}), and the
 * caller still re-validates the answer -- a vendor-side constraint is a hint, never the guarantee.
 *
 * <p>{@code imageBytes}/{@code imageMimeType} are optional and travel together (vision input).
 * {@code vendorId} names WHICH configured vendor, never WHERE -- the endpoint comes from the
 * server-side vendor profile, same SSRF reasoning as {@link ExternalAiGenerationRequest}.
 *
 * <p>AIP-3 (3.4a): {@code effort} is optional and advisory ({@code low}/{@code medium}/{@code high}),
 * with the same contract as {@link ExternalAiGenerationRequest}: a vendor with no real equivalent drops it.
 */
public record ExternalAiStructuredRequest(
        String vendorId,
        String model,
        String prompt,
        String responseSchemaJson,
        byte[] imageBytes,
        String imageMimeType,
        Integer maxOutputTokens,
        String effort
) {
    public ExternalAiStructuredRequest {
        if (vendorId == null || vendorId.isBlank()) {
            throw new IllegalArgumentException("vendorId must be non-blank");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must be non-blank");
        }
        if (responseSchemaJson == null || responseSchemaJson.isBlank()) {
            throw new IllegalArgumentException("responseSchemaJson must be non-blank");
        }
        if ((imageBytes == null) != (imageMimeType == null || imageMimeType.isBlank())) {
            throw new IllegalArgumentException("imageBytes and imageMimeType must be given together");
        }
        if (effort != null && !effort.isBlank()
                && !("low".equals(effort) || "medium".equals(effort) || "high".equals(effort))) {
            throw new IllegalArgumentException("effort must be one of low, medium, high -- got: " + effort);
        }
    }

    public ExternalAiStructuredRequest(String vendorId, String model, String prompt, String responseSchemaJson,
                                       byte[] imageBytes, String imageMimeType, Integer maxOutputTokens) {
        this(vendorId, model, prompt, responseSchemaJson, imageBytes, imageMimeType, maxOutputTokens, null);
    }

    public boolean hasImage() {
        return imageBytes != null;
    }
}
