package com.finalexec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.pluginipc.PluginControllerProxyHandler;
import com.finalexec.npdev.service.pluginipc.PluginControllerRouteManifest;
import com.finalexec.npdev.service.pluginipc.PluginControllerRouteManifestLoader;
import com.finalexec.npdev.service.pluginipc.PluginIpcChildProcessPool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * B30/SEC-9: the route manifest and dispatch handler for isolated {@code plugin:java-controller}
 * mounts -- reusable across any generated app (no {@code com.npdev.generated.*} dependency), so it
 * lives in runtimehost-core and is auto-detected via {@code FinalExecApplication}'s
 * {@code @ComponentScan} of {@code com.finalexec}. The {@code HandlerMapping} that actually registers
 * {@link PluginControllerProxyHandler} against each mount's {@code basePath} lives in
 * {@code PluginControllerSecurityConfig} instead (the app-only tree) -- it must attach D9's
 * {@code minimumRole} interceptor directly to that mapping (see that class's javadoc for why relying
 * on {@code WebMvcConfigurer.addInterceptors} does not work here), and {@code RuntimeContextService}
 * is only resolvable inside an assembled app.
 */
@Configuration
public class PluginControllerProxyConfig {

    @Bean
    public PluginControllerRouteManifest pluginControllerRouteManifest(ObjectMapper objectMapper) {
        return new PluginControllerRouteManifestLoader(objectMapper).load();
    }

    @Bean
    public PluginControllerProxyHandler pluginControllerProxyHandler(
            PluginControllerRouteManifest pluginControllerRouteManifest,
            ObjectProvider<PluginIpcChildProcessPool> pluginIpcChildProcessPool,
            RuntimePluginAdapterRegistry runtimePluginAdapterRegistry,
            PluginExecutionPolicyEvaluator pluginExecutionPolicyEvaluator,
            ObjectMapper objectMapper
    ) {
        return new PluginControllerProxyHandler(
                pluginControllerRouteManifest,
                pluginIpcChildProcessPool,
                runtimePluginAdapterRegistry,
                pluginExecutionPolicyEvaluator,
                objectMapper
        );
    }
}
