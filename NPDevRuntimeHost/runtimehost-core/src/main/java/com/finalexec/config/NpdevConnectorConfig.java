package com.finalexec.config;

import com.finalexec.connector.ConnectorEgressPolicy;
import com.finalexec.connector.HttpConnectorCapabilityHandler;
import com.npdev.kernel.properties.PropertyResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * INT-2: wires the platform-served handler behind every capability bound to adapter
 * {@code http-connector}. Operator knobs (all optional):
 * <ul>
 *   <li>{@code npdev.connectors.enabled} (default true) -- false refuses every outbound connector call;</li>
 *   <li>{@code npdev.connectors.allowed-hosts} (comma list, default empty = any host a model declares);</li>
 *   <li>{@code npdev.connectors.allow-insecure-http} (default false; loopback is always allowed over http).</li>
 * </ul>
 * Secrets are looked up by NAME through the Spring {@link Environment}, so an environment variable such as
 * {@code NPDEV_CONNECTOR_PAYMENTS_TOKEN} (or the same key as a -D / application property) works.
 */
@Configuration
public class NpdevConnectorConfig {

    @Bean
    public HttpConnectorCapabilityHandler httpConnectorCapabilityHandler(
            ModelHolder modelHolder,
            ObjectProvider<PropertyResolver> propertyResolver,
            Environment environment,
            @Value("${npdev.connectors.enabled:true}") boolean enabled,
            @Value("${npdev.connectors.allowed-hosts:}") String allowedHosts,
            @Value("${npdev.connectors.allow-insecure-http:false}") boolean allowInsecureHttp
    ) {
        return new HttpConnectorCapabilityHandler(
                modelHolder::get,
                propertyResolver::getIfAvailable,
                environment::getProperty,
                HttpConnectorCapabilityHandler.defaultClient(),
                new ConnectorEgressPolicy(enabled, splitHosts(allowedHosts), allowInsecureHttp));
    }

    static Set<String> splitHosts(String allowedHosts) {
        if (allowedHosts == null || allowedHosts.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(allowedHosts.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
