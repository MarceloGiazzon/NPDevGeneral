package com.finalexec.agent;

import com.finalexec.agent.AgentChannelsStarter.AssistantChoice;
import com.npdev.dsl.v1.compiled.CompiledAgentAccessAssistant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** AIP-3 (3.5): the chat assistant's vendor defaults to the model's own, not a hard-coded one. */
class AgentChannelsStarterTest {

    @Test
    void theAssistantUsesTheFirstVendorTheModelOptedInto() {
        assertEquals("groq", AgentChannelsStarter.defaultVendor(List.of(" ", "groq", "gemini")));
    }

    @Test
    void aModelWithNoVendorKeepsGemini() {
        assertEquals("gemini", AgentChannelsStarter.defaultVendor(List.of()));
    }

    /** AIP-3 (3.4a): env override, then the model's assistant profile, then the first model vendor. */
    @Test
    void theAssistantProfileSitsBetweenTheEnvOverrideAndTheModelsFirstVendor() {
        CompiledAgentAccessAssistant claude = new CompiledAgentAccessAssistant(
                "Bot", null, null, "claude", "anthropic", "claude-opus-5-5", "high");
        assertEquals(new AssistantChoice("anthropic", "claude-opus-5-5", "high"),
                AgentChannelsStarter.chooseAssistant(null, null, claude, List.of("groq")));
        assertEquals(new AssistantChoice("groq", "llama-x", "high"),
                AgentChannelsStarter.chooseAssistant("groq", "llama-x", claude, List.of("gemini")),
                "NPDEV_AGENT_VENDOR/MODEL still win; the profile's model never rides on another vendor");
        assertEquals(new AssistantChoice("anthropic", "claude-other", "high"),
                AgentChannelsStarter.chooseAssistant(null, "claude-other", claude, List.of()));
        assertEquals(new AssistantChoice("groq", null, null),
                AgentChannelsStarter.chooseAssistant(null, null, new CompiledAgentAccessAssistant("Bot", null, null),
                        List.of("groq")));
    }
}
