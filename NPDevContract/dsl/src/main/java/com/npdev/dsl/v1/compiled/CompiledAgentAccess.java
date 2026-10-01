package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AGENT-1: compiled form of {@code AgentAccessAst} -- what an AI agent may see and do in this app,
 * and on which channels. {@code null} on {@link CompiledModel#getAgentAccess()} when the model
 * declares no {@code agentAccess} block (agent access is entirely off in that case).
 */
public final class CompiledAgentAccess {
    private final CompiledAgentAccessAssistant assistant;
    private final CompiledAgentAccessChannels channels;
    private final List<CompiledAgentAccessExposure> expose;

    public CompiledAgentAccess(CompiledAgentAccessAssistant assistant, CompiledAgentAccessChannels channels,
            List<CompiledAgentAccessExposure> expose) {
        this.assistant = assistant;
        this.channels = channels;
        this.expose = expose == null ? new ArrayList<>() : new ArrayList<>(expose);
    }

    public CompiledAgentAccessAssistant getAssistant() { return assistant; }

    public CompiledAgentAccessChannels getChannels() { return channels; }

    public List<CompiledAgentAccessExposure> getExpose() {
        return Collections.unmodifiableList(expose);
    }
}
