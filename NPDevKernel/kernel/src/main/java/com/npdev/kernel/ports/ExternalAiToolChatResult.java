package com.npdev.kernel.ports;

/** AGENT-1: the vendor's reply to a {@link ExternalAiToolChatRequest} -- either a final text answer
 *  or one or more tool calls (see {@link #wantsTools()}). */
public record ExternalAiToolChatResult(String vendorId, String model, ExternalAiChatTurn assistantTurn,
        String rawResponse) {

    public boolean wantsTools() {
        return !assistantTurn.toolCalls().isEmpty();
    }
}
