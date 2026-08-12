package com.npdev.adapters.externalai.http;

/**
 * The vendor request/response shape a {@link ExternalAiVendorProfile} uses.
 *
 * <p>{@link #OPENAI_CHAT} covers every OpenAI-compatible chat-completions API -- OpenAI itself,
 * NVIDIA Build, and xAI's Grok all speak it, which is why one shape serves three vendors.
 * {@link #GEMINI_GENERATE_CONTENT} and {@link #ANTHROPIC_MESSAGES} each have their own.
 *
 * <p>The shape decides three things together -- the URL suffix, the auth header, and the body/response
 * JSON -- so they cannot drift apart per vendor. Anthropic is a distinct shape rather than an
 * OpenAI-compatible one for exactly that reason: it authenticates with {@code x-api-key} rather than
 * {@code Authorization: Bearer}, requires an {@code anthropic-version} header, requires
 * {@code max_tokens}, and returns its text at {@code content[0].text} rather than
 * {@code choices[0].message.content}.
 */
public enum ExternalAiRequestFormat {
    OPENAI_CHAT,
    GEMINI_GENERATE_CONTENT,
    ANTHROPIC_MESSAGES
}
