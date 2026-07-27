package com.npdev.adapters.externalai.http;

/**
 * The vendor request/response shape a {@link ExternalAiVendorProfile} uses. Two shapes cover all
 * three D1 vendors: OpenAI and xAI's Grok are both OpenAI-compatible chat-completions APIs; Gemini
 * has its own generateContent shape.
 */
public enum ExternalAiRequestFormat {
    OPENAI_CHAT,
    GEMINI_GENERATE_CONTENT
}
