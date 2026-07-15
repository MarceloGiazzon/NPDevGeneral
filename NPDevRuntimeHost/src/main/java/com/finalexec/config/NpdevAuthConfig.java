package com.finalexec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.auth.IdentityAwareContextResolver;
import com.finalexec.auth.TenantStatusFilter;
import com.finalexec.controlpanel.SuperUserCredentialAuthFilter;
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
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(
            CorrelationIdFilter correlationIdFilter
    ) {
        // Runs before every other filter (lowest order) so every subsequent filter's own log
        // lines are already covered by the MDC correlationId.
        FilterRegistrationBean<CorrelationIdFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(correlationIdFilter);
        bean.addUrlPatterns("/*");
        bean.setOrder(-120);
        return bean;
    }

    @Bean
    public ActuatorAdminGuardFilter actuatorAdminGuardFilter() {
        return new ActuatorAdminGuardFilter();
    }

    @Bean
    public FilterRegistrationBean<ActuatorAdminGuardFilter> actuatorAdminGuardFilterRegistration(
            ActuatorAdminGuardFilter actuatorAdminGuardFilter
    ) {
        // After SuperUserCredentialAuthFilter (-110) so the SUPERUSER claim it resolves (if any)
        // is already on the request attribute this filter checks. /actuator/health is
        // deliberately NOT covered -- it must stay reachable unauthenticated for the Docker
        // healthcheck (LNCH-7).
        FilterRegistrationBean<ActuatorAdminGuardFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(actuatorAdminGuardFilter);
        bean.addUrlPatterns("/actuator/metrics", "/actuator/metrics/*", "/actuator/prometheus");
        bean.setOrder(-105);
        return bean;
    }

    @Bean
    public SuperUserCredentialAuthFilter superUserCredentialAuthFilter(
            CredentialRegistryService credentialRegistryService
    ) {
        return new SuperUserCredentialAuthFilter(credentialRegistryService);
    }

    @Bean
    public FilterRegistrationBean<SuperUserCredentialAuthFilter> superUserCredentialAuthFilterRegistration(
            SuperUserCredentialAuthFilter superUserCredentialAuthFilter
    ) {
        // Unconditional and independent of npdev.auth.mode/runtimeSettings.authEnabled(): the
        // ControlPanel must stay reachable regardless of whether business Login is apikey/jwt/none.
        // No-ops on its own when the X-Super-User-Key header is absent (see the filter itself).
        FilterRegistrationBean<SuperUserCredentialAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(superUserCredentialAuthFilter);
        bean.addUrlPatterns("/*");
        bean.setOrder(-110);
        return bean;
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
