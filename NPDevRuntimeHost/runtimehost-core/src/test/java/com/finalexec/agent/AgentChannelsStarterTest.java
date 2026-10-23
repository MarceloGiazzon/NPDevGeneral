package com.finalexec.agent;

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
}
