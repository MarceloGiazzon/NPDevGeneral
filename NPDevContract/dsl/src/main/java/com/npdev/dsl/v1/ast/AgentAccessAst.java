package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AGENT-1: what an AI agent may see and do in this app, and on which channels (MCP, Telegram,
 * WhatsApp). Every tool call still runs through the app's own REST API as the calling/linked user --
 * this block only decides what is OFFERED to the agent, never a second permission system. A model
 * that declares no {@code agentAccess} block keeps agent access entirely off (see
 * {@link ModelAst#getAgentAccess()}, which returns {@code null} in that case).
 */
public final class AgentAccessAst {
    private final AgentAccessAssistantAst assistant;
    private final AgentAccessChannelsAst channels;
    private final List<AgentAccessExposureAst> expose;
    private final AgentAccessPhotoIntakeAst photoIntake;

    public AgentAccessAst(AgentAccessAssistantAst assistant, AgentAccessChannelsAst channels,
            List<AgentAccessExposureAst> expose) {
        this(assistant, channels, expose, null);
    }

    /** P8: with the optional {@code photoIntake} block (null = photos are not ingested). */
    public AgentAccessAst(AgentAccessAssistantAst assistant, AgentAccessChannelsAst channels,
            List<AgentAccessExposureAst> expose, AgentAccessPhotoIntakeAst photoIntake) {
        this.photoIntake = photoIntake;
        this.assistant = assistant;
        this.channels = channels;
        this.expose = expose == null ? new ArrayList<>() : new ArrayList<>(expose);
    }

    public AgentAccessAssistantAst getAssistant() { return assistant; }

    public AgentAccessChannelsAst getChannels() { return channels; }
    public AgentAccessPhotoIntakeAst getPhotoIntake() { return photoIntake; }

    public List<AgentAccessExposureAst> getExpose() {
        return Collections.unmodifiableList(expose);
    }
}
