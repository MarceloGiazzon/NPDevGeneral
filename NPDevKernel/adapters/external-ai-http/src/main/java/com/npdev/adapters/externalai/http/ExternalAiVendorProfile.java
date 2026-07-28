package com.npdev.adapters.externalai.http;

/**
 * Per-vendor config for {@link HttpExternalAiCapabilityAdapter}, supplied by the caller at
 * construction time -- same pattern as {@code SmtpMailCapabilityAdapter}'s host/port/credentials
 * (RuntimeHost wires this from env vars via {@code @Value}). The API key itself is never held here:
 * only the name of the env var to read it from, so a profile is safe to log or serialize.
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
        if (apiKeyEnvVar == null || apiKeyEnvVar.isBlank()) {
            throw new IllegalArgumentException("apiKeyEnvVar must be non-blank");
        }
        if (requestFormat == null) {
            throw new IllegalArgumentException("requestFormat must be non-null");
        }
    }

    /**
     * NVIDIA Build (build.nvidia.com / NIM) -- an OpenAI-compatible chat-completions endpoint
     * hosting many catalog models. {@code model} must be a real NVIDIA Build catalog id (e.g.
     * {@code "meta/llama-3.3-70b-instruct"}, confirmed working end-to-end 2026-07-27). NVIDIA
     * Build's own {@code GET /v1/models} catalog listing is NOT authoritative for what a given
     * account can actually invoke -- several listed models 404'd with "Function not found for
     * account" when called for real. Confirm any different model against a real call, not just
     * the catalog listing, before relying on it.
     */
    public static ExternalAiVendorProfile nvidiaBuild(String apiKeyEnvVar, String model) {
        return new ExternalAiVendorProfile(
                "nvidia", "https://integrate.api.nvidia.com/v1/chat/completions", model, apiKeyEnvVar,
                ExternalAiRequestFormat.OPENAI_CHAT);
    }

    public static ExternalAiVendorProfile gemini(String apiKeyEnvVar, String model) {
        return new ExternalAiVendorProfile(
                "gemini", "https://generativelanguage.googleapis.com/v1beta", model, apiKeyEnvVar,
                ExternalAiRequestFormat.GEMINI_GENERATE_CONTENT);
    }
}
