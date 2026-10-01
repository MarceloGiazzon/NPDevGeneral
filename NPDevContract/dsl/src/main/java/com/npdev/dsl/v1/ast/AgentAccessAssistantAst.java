package com.npdev.dsl.v1.ast;

/**
 * How the built-in chat assistant (Telegram/WhatsApp) behaves. Not used by the MCP endpoint, where
 * the connecting agent brings its own model.
 */
public final class AgentAccessAssistantAst {
    private final String name;
    private final String instructions;
    private final String language;

    public AgentAccessAssistantAst(String name, String instructions, String language) {
        this.name = name;
        this.instructions = instructions;
        this.language = language;
    }

    public String getName() { return name; }

    public String getInstructions() { return instructions; }

    public String getLanguage() { return language; }
}
