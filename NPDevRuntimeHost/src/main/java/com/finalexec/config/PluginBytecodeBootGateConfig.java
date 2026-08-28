package com.finalexec.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SEC-3 / B30: registers the plugin bytecode boot admission gate. The gate itself is a no-op
 * unless the generator wrote {@code npdev/plugin-bytecode/plugin-owned-classes.txt} (i.e. unless
 * the model mounted any plugin), so this bean is harmless in every app that carries no plugins.
 */
@Configuration
public class PluginBytecodeBootGateConfig {

    @Bean
    public PluginBytecodeBootGate pluginBytecodeBootGate() {
        return new PluginBytecodeBootGate();
    }
}