package com.npdev.adapters.externalai.http;

/**
 * Per-vendor config for {@link HttpExternalAiCapabilityAdapter}, supplied by the caller at
 * construction time -- same pattern as {@code SmtpMailCapabilityAdapter}'s host/port/credentials
 * (RuntimeHost wires this from env vars via {@code @Value}). The API key itself is never held here:
 * only the name of the env var to read it from, so a profile is safe to log or serialize. A generated
 * app builds its profiles from the AI catalogue NPDev ships ({@link ExternalAiCatalogue}); the static
 * factories below remain for tests and for code that wires one vendor by hand.
 *
 * <p>The two static factories cover D1's vendor choice (revised 2026-07-26: NVIDIA Build + Gemini,
 * replacing the original OpenAI + xAI Grok answer) with well-known public default endpoints; the
 * canonical constructor is available for a different base URL (e.g. an Azure/enterprise variant)
 * without any code change.</p>
 */
public record ExternalAiVendorProfile(
        String vendorId,
        String baseUrl,
        String model,
        String apiKeyEnvVar,
        ExternalAiRequestFormat requestFormat
) {
    public ExternalAiVendorProfile {
        if (vendorId == null || vendorId.isBlank()) {
            throw new IllegalArgumentException("vendorId must be non-blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must be non-blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must be non-blank");
        }
        // null = a keyless vendor (a local server such as Ollama: kind "local" in the AI catalogue);
        // the adapter then sends no auth header. Blank is still a mistake, never a quiet "keyless".
        if (apiKeyEnvVar != null && apiKeyEnvVar.isBlank()) {
            throw new IllegalArgumentException("apiKeyEnvVar must be non-blank, or null for a keyless vendor");
        }
        if (requestFormat == null) {
            throw new IllegalArgumentException("requestFormat must be non-null");
        }
    }

    /** A vendor that needs no key (a local server); {@link HttpExternalAiCapabilityAdapter} sends no auth header. */
    public boolean keyless() {
        return apiKeyEnvVar == null;
    }

    /**
     * Whether this vendor's API has a knob the adapter maps {@code low}/{@code medium}/{@code high}
     * onto. Anthropic's Messages API does ({@code output_config.effort}, GA, no beta header). The
     * OpenAI-compatible shape does not, uniformly: {@code reasoning_effort} exists only on reasoning
     * models and is a 400 on the chat models this adapter defaults to, so claiming support would
     * turn a UI affordance into a request failure. Gemini has no equivalent field at all.
     */
    public boolean supportsEffort() {
        return requestFormat == ExternalAiRequestFormat.ANTHROPIC_MESSAGES;
    }
}
