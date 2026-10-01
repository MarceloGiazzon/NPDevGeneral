package com.npdev.dsl.v1.compiled;

/** Compiled form of {@code AgentAccessAssistantAst}. */
public final class CompiledAgentAccessAssistant {
    private final String name;
    private final String instructions;
    private final String language;

    public CompiledAgentAccessAssistant(String name, String instructions, String language) {
        this.name = name;
        this.instructions = instructions;
        this.language = language;
    }

    public String getName() { return name; }

    public String getInstructions() { return instructions; }

    public String getLanguage() { return language; }
}
