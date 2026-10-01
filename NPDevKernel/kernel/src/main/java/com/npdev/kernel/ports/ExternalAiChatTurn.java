package com.npdev.kernel.ports;

import java.util.List;

/**
 * AGENT-1: one turn of a tool-calling conversation. {@code role} is {@code "user"},
 * {@code "assistant"} or {@code "tool"}.
 * <ul>
 *   <li>{@code user}: {@code text} only.</li>
 *   <li>{@code assistant}: {@code text} and/or {@code toolCalls}, plus {@code vendorRaw} -- the raw
 *       vendor JSON for this turn (Gemini {@code candidates[0].content}, OpenAI
 *       {@code choices[0].message}, Anthropic the {@code content} array), replayed VERBATIM on the
 *       next request so hidden thinking signatures survive.</li>
 *   <li>{@code tool}: {@code toolCallId}/{@code toolName}/{@code resultJson} (a JSON object string).</li>
 * </ul>
 */
public record ExternalAiChatTurn(String role, String text, List<ExternalAiToolCall> toolCalls,
        String vendorRaw, String toolCallId, String toolName, String resultJson) {

    public static ExternalAiChatTurn user(String text) {
        return new ExternalAiChatTurn("user", text, List.of(), null, null, null, null);
    }

    public static ExternalAiChatTurn toolResult(String callId, String name, String json) {
        return new ExternalAiChatTurn("tool", null, List.of(), null, callId, name, json);
    }
}
