package com.npdev.dsl.v1.compiled;

/** Compiled form of {@code AgentAccessChannelsAst}. Each getter is never null -- an unmentioned
 *  channel compiles to a disabled {@link CompiledAgentAccessChannel}. */
public final class CompiledAgentAccessChannels {
    private static final CompiledAgentAccessChannel DISABLED = new CompiledAgentAccessChannel(false);

    private final CompiledAgentAccessChannel mcp;
    private final CompiledAgentAccessChannel telegram;
    private final CompiledAgentAccessChannel whatsapp;

    public CompiledAgentAccessChannels(CompiledAgentAccessChannel mcp, CompiledAgentAccessChannel telegram,
            CompiledAgentAccessChannel whatsapp) {
        this.mcp = mcp == null ? DISABLED : mcp;
        this.telegram = telegram == null ? DISABLED : telegram;
        this.whatsapp = whatsapp == null ? DISABLED : whatsapp;
    }

    public CompiledAgentAccessChannel getMcp() { return mcp; }

    public CompiledAgentAccessChannel getTelegram() { return telegram; }

    public CompiledAgentAccessChannel getWhatsapp() { return whatsapp; }
}
