package com.npdev.dsl.v1.compiled;

/** Compiled form of one agent channel's enablement (mcp / telegram / whatsapp). */
public final class CompiledAgentAccessChannel {
    private final boolean enabled;

    public CompiledAgentAccessChannel(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }
}
