package com.npdev.kernel.ports;

import java.util.List;

/**
 * AGENT-1: a tool-calling chat request to {@link ExternalAiCapabilityContract#chatWithTools}.
 *
 * <p>AIP-3 (3.4a): {@code effort} is optional and advisory ({@code low}/{@code medium}/{@code high}),
 * with the same contract as {@link ExternalAiGenerationRequest}: a vendor with no real equivalent drops it.
 */
public record ExternalAiToolChatRequest(String vendorId, String model, String systemInstruction,
        List<ExternalAiToolSpec> tools, List<ExternalAiChatTurn> turns, String effort) {

    public ExternalAiToolChatRequest {
        if (effort != null && !effort.isBlank()
                && !("low".equals(effort) || "medium".equals(effort) || "high".equals(effort))) {
            throw new IllegalArgumentException("effort must be one of low, medium, high -- got: " + effort);
        }
    }

    public ExternalAiToolChatRequest(String vendorId, String model, String systemInstruction,
                                     List<ExternalAiToolSpec> tools, List<ExternalAiChatTurn> turns) {
        this(vendorId, model, systemInstruction, tools, turns, null);
    }
}
