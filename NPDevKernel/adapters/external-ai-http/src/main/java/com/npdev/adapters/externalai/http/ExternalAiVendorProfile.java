package com.npdev.adapters.externalai.http;

/**
 * Per-vendor config for {@link HttpExternalAiCapabilityAdapter}, supplied by the caller at
 * construction time -- same pattern as {@code SmtpMailCapabilityAdapter}'s host/port/credentials
 * (RuntimeHost wires this from env vars via {@code @Value}). The API key itself is never held here:
 * only the name of the env var to read it from, so a profile is safe to log or serialize.
 *
 * <p>The three static factories cover D1's vendor choice with well-known public default endpoints;
 * the canonical constructor is available for a different base URL (e.g. an Azure/enterprise
 * variant) without any code change.</p>
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

    public static ExternalAiVendorProfile openAi(String apiKeyEnvVar, String model) {
        return new ExternalAiVendorProfile(
                "openai", "https://api.openai.com/v1/chat/completions", model, apiKeyEnvVar,
                ExternalAiRequestFormat.OPENAI_CHAT);
    }

    public static ExternalAiVendorProfile gemini(String apiKeyEnvVar, String model) {
        return new ExternalAiVendorProfile(
                "gemini", "https://generativelanguage.googleapis.com/v1beta", model, apiKeyEnvVar,
                ExternalAiRequestFormat.GEMINI_GENERATE_CONTENT);
    }

    public static ExternalAiVendorProfile xai(String apiKeyEnvVar, String model) {
        return new ExternalAiVendorProfile(
                "xai", "https://api.x.ai/v1/chat/completions", model, apiKeyEnvVar,
                ExternalAiRequestFormat.OPENAI_CHAT);
    }
}
