package com.finalexec.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.RuntimePluginProfileResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

@Configuration
public class BindingsFingerprintConfig {

    private static final Logger log = LoggerFactory.getLogger(BindingsFingerprintConfig.class);

    @Bean
    public ApplicationRunner printBindingsFingerprint(
            RuntimePluginProfileResolver.ResolvedRuntimePluginProfile runtimePluginProfile
    ) {
        return args -> {
            String path = normalizeResourcePath(runtimePluginProfile.bindingsManifestPath());
            try {
                ClassPathResource r = new ClassPathResource(path);
                if (!r.exists()) {
                    log.warn("BIND FP -> NOT FOUND on classpath: {}", path);
                    return;
                }
                String txt = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode root = new ObjectMapper().readTree(txt);
                String fp = root.has("debugFingerprint") ? root.get("debugFingerprint").asText() : "(missing)";
                log.info("BIND FP -> Loaded classpath:{} fingerprint={}", path, fp);
            } catch (Exception e) {
                log.error("BIND FP -> Failed reading {}: {}", path, e.toString());
            }
        };
    }

    private static String normalizeResourcePath(String resourcePath) {
        String normalized = resourcePath.trim();
        if (normalized.startsWith("classpath:")) {
            normalized = normalized.substring("classpath:".length());
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
