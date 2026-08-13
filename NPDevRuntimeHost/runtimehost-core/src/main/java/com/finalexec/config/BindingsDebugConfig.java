package com.finalexec.config;

import com.finalexec.npdev.service.RuntimePluginProfileResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;

/**
 * Prints the exact classpath copy of the selected bindings manifest and the default permissions manifest.
 * Safe: does not iterate over beans (so it cannot accidentally trigger proxies).
 */
@Configuration
@Profile("dev")
public class BindingsDebugConfig {

    private static final Logger log = LoggerFactory.getLogger(BindingsDebugConfig.class);

    @Bean
    public ApplicationRunner dumpBindingsResources(
            ApplicationContext ctx,
            RuntimePluginProfileResolver.ResolvedRuntimePluginProfile runtimePluginProfile
    ) {
        return args -> {
            log.info(
                    "BIND DEBUG -> deploymentProfile='{}' selectionMode='{}' executionEnvironment='{}' bindingsManifest='{}' pluginManifest='{}'",
                    runtimePluginProfile.activeProfile(),
                    runtimePluginProfile.selectionMode(),
                    runtimePluginProfile.executionEnvironment(),
                    runtimePluginProfile.bindingsManifestPath(),
                    runtimePluginProfile.pluginManifestPath()
            );
            dumpClasspathText(ctx, toClasspathLocation(runtimePluginProfile.bindingsManifestPath()));
            dumpClasspathText(ctx, "classpath:npdev/security/dev.permissions.json");
        };
    }

    private static String toClasspathLocation(String resourcePath) {
        String normalized = resourcePath.trim();
        if (normalized.startsWith("classpath:")) {
            return normalized;
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return "classpath:" + normalized;
    }

    private static void dumpClasspathText(ApplicationContext ctx, String location) {
        try {
            Resource r = ctx.getResource(location);
            if (!r.exists()) {
                log.warn("BIND DEBUG -> Resource NOT FOUND: {}", location);
                return;
            }
            byte[] bytes = r.getInputStream().readAllBytes();
            String txt = new String(bytes, StandardCharsets.UTF_8);

            log.info("BIND DEBUG -> Resource FOUND: {}", location);
            log.info("BIND DEBUG -> Resource content start >>>\n{}\n<<< end", txt);
        } catch (Exception e) {
            log.error("BIND DEBUG -> Failed reading resource {}: {}", location, e.toString());
        }
    }
}
