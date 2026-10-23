package com.npdev.dsl.v1.ast;

/**
 * How the built-in chat assistant (Telegram/WhatsApp) behaves. Not used by the MCP endpoint, where
 * the connecting agent brings its own model. AIP-3 (3.4a): {@code profile} names the AI catalogue profile
 * it chats with, {@code effort} overrides that profile's effort.
 */
public final class AgentAccessAssistantAst {
    private final String name;
    private final String instructions;
    private final String language;
    private final String profile;
    private final String effort;

    public AgentAccessAssistantAst(String name, String instructions, String language) {
        this(name, instructions, language, null, null);
    }

    public AgentAccessAssistantAst(String name, String instructions, String language, String profile, String effort) {
        this.name = name;
        this.instructions = instructions;
        this.language = language;
        this.profile = profile;
        this.effort = effort;
    }

    public String getName() { return name; }

    public String getInstructions() { return instructions; }

    public String getLanguage() { return language; }

    public String getProfile() { return profile; }

    public String getEffort() { return effort; }
}
