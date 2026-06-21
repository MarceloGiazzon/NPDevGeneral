package com.finalexec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.auth.IdentityAwareContextResolver;
import com.finalexec.auth.TenantStatusFilter;
import com.finalexec.npdev.service.CredentialRegistryService;
import com.finalexec.npdev.service.TenantRegistryService;
import com.npdev.adapters.authcontext.jwt.JwtAuthenticatedContextResolver;
import com.npdev.adapters.authz.defaultpolicy.DefaultExecutionAuthorizationPolicy;
import com.npdev.adapters.authz.defaultpolicy.DefaultTenantIsolationPolicy;
import com.npdev.adapters.runtime.validation.RuntimeSettings;
import com.npdev.generated.runtime.config.RuntimeApiKeyAuthFilter;
import com.npdev.kernel.ports.AuthenticatedContextResolver;
import com.npdev.kernel.ports.ExecutionAuthorizationPolicy;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration
public class NpdevAuthConfig {

    @Bean
    public TenantIsolationPolicy tenantIsolationPolicy() {
        return new DefaultTenantIsolationPolicy();
    }

    @Bean
    public ExecutionAuthorizationPolicy executionAuthorizationPolicy(TenantIsolationPolicy tenantIsolationPolicy) {
        return new DefaultExecutionAuthorizationPolicy(tenantIsolationPolicy);
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticatedContextResolver.class)
    public AuthenticatedContextResolver authenticatedContextResolver(
            org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> dataSourceProvider
    ) {
        // Base resolver decodes the principal (api-key / JWT) into tenant + actor + claim-roles;
        // the identity-aware wrapper lets the persistent identity pack override roles when populated.
        return new IdentityAwareContextResolver(new JwtAuthenticatedContextResolver(), dataSourceProvider);
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "apikey")
    public RuntimeApiKeyAuthFilter runtimeApiKeyAuthFilter(
            @Value("${npdev.auth.api-keys:}") String encodedMappings,
            CredentialRegistryService credentialRegistryService
    ) {
        // Static mappings (encodedMappings) win first; credentialRegistryService is the fallback
        // that lets a tenant onboarded at runtime (T4) authenticate without a regenerate/restart.
        return new RuntimeApiKeyAuthFilter(encodedMappings, credentialRegistryService);
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "apikey")
    public FilterRegistrationBean<RuntimeApiKeyAuthFilter> runtimeApiKeyAuthFilterRegistration(
            RuntimeApiKeyAuthFilter runtimeApiKeyAuthFilter,
            RuntimeSettings runtimeSettings
    ) {
        FilterRegistrationBean<RuntimeApiKeyAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(runtimeApiKeyAuthFilter);
        bean.addUrlPatterns("/*");
        bean.setOrder(-100);
        bean.setEnabled(runtimeSettings.authEnabled());
        return bean;
    }

    @Bean
    public FilterRegistrationBean<TenantStatusFilter> tenantStatusFilterRegistration(
            TenantRegistryService tenantRegistryService,
            RuntimeSettings runtimeSettings
    ) {
        FilterRegistrationBean<TenantStatusFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new TenantStatusFilter(tenantRegistryService));
        bean.addUrlPatterns("/*");
        // After the authentication filter (-100) so the claims attribute is already set.
        bean.setOrder(-90);
        bean.setEnabled(runtimeSettings.authEnabled());
        return bean;
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
    public JwtBearerAuthFilter jwtBearerAuthFilter(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${npdev.auth.jwt.issuer:}") String issuer,
            @Value("${npdev.auth.jwt.audience:}") String audience,
            @Value("${npdev.auth.jwt.public-key-path:}") String publicKeyPath
    ) {
        return new JwtBearerAuthFilter(objectMapper, resourceLoader, issuer, audience, publicKeyPath);
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
    public FilterRegistrationBean<JwtBearerAuthFilter> jwtBearerAuthFilterRegistration(
            JwtBearerAuthFilter jwtBearerAuthFilter,
            RuntimeSettings runtimeSettings
    ) {
        FilterRegistrationBean<JwtBearerAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(jwtBearerAuthFilter);
        bean.addUrlPatterns("/api/*", "/api/v1/*");
        bean.setOrder(-100);
        bean.setEnabled(runtimeSettings.authEnabled());
        return bean;
    }
}
