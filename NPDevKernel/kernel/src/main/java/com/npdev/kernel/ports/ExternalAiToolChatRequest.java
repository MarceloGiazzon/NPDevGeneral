package com.npdev.kernel.ports;

import java.util.List;

/** AGENT-1: a tool-calling chat request to {@link ExternalAiCapabilityContract#chatWithTools}. */
public record ExternalAiToolChatRequest(String vendorId, String model, String systemInstruction,
        List<ExternalAiToolSpec> tools, List<ExternalAiChatTurn> turns) {
}
