package com.finalexec.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * AGENT-1 (A4.3): wires the agent-access beans. {@code AgentApiExecutor}'s port supplier reads
 * {@code local.server.port} (falling back to the configured {@code server.port}) LAZILY, at call
 * time -- the web server's actual bound port is only known after it starts, which is after this
 * configuration class runs.
 */
@Configuration
public class AgentAccessConfig {

    @Bean
    public AgentApiExecutor agentApiExecutor(ObjectMapper objectMapper, Environment environment) {
        return new AgentApiExecutor(objectMapper, () -> Integer.parseInt(
                environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"))));
    }
}
