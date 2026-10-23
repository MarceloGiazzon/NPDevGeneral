package com.npdev.dsl.v1.compiled;

/**
 * Compiled form of {@code AgentAccessAssistantAst}. AIP-3 (3.4a): with a {@code profile}, {@code vendor} and
 * {@code model} are that catalogue profile's and {@code effort} is the assistant's own or else the profile's;
 * all four are null when the model names no profile.
 */
public final class CompiledAgentAccessAssistant {
    private final String name;
    private final String instructions;
    private final String language;
    private final String profile;
    private final String vendor;
    private final String model;
    private final String effort;

    public CompiledAgentAccessAssistant(String name, String instructions, String language) {
        this(name, instructions, language, null, null, null, null);
    }

    public CompiledAgentAccessAssistant(String name, String instructions, String language,
                                        String profile, String vendor, String model, String effort) {
        this.name = name;
        this.instructions = instructions;
        this.language = language;
        this.profile = profile;
        this.vendor = vendor;
        this.model = model;
        this.effort = effort;
    }

    public String getName() { return name; }

    public String getInstructions() { return instructions; }

    public String getLanguage() { return language; }

    public String getProfile() { return profile; }

    public String getVendor() { return vendor; }

    public String getModel() { return model; }

    public String getEffort() { return effort; }
}
