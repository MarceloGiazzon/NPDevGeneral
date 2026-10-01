package com.npdev.dsl.v1.ast;

/** Which agent channels are enabled. Each channel absent/false by default. */
public final class AgentAccessChannelsAst {
    private final boolean mcp;
    private final boolean telegram;
    private final boolean whatsapp;

    public AgentAccessChannelsAst(boolean mcp, boolean telegram, boolean whatsapp) {
        this.mcp = mcp;
        this.telegram = telegram;
        this.whatsapp = whatsapp;
    }

    public boolean isMcp() { return mcp; }

    public boolean isTelegram() { return telegram; }

    public boolean isWhatsapp() { return whatsapp; }
}
