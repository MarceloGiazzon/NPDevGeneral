package com.finalexec.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@Configuration
@Profile("dev")
public class NpdevPropertiesDumpConfig {

    private static final Logger log = LoggerFactory.getLogger(NpdevPropertiesDumpConfig.class);

    @Bean
    public ApplicationRunner dumpNpdevProperties(Environment env) {
        return args -> {
            log.info("NPDEV PROPS -> Dumping keys that contain 'npdev' and 'adapter'/'persistence' (best-effort).");

            // We can’t iterate Environment keys portably, but we can probe common ones.
            // Add more probes here as you discover them.
            String[] probes = new String[] {
                    "npdev.environment",
                    "npdev.tenantId",
                    "npdev.capabilities.persistence.defaultAdapterId",
                    "npdev.capabilities.persistence.adapterId",
                    "npdev.persistence.defaultAdapterId",
                    "npdev.persistence.adapterId",
                    "npdev.bindings.environment",
                    "npdev.bindings.tenantId",
                    "npdev.defaultAdapterId",
                    "npdev.default.adapterId"
            };

            Arrays.stream(probes).forEach(k -> {
                String v = env.getProperty(k);
                if (v != null) log.info("NPDEV PROPS -> {} = {}", k, v);
            });
        };
    }
}
