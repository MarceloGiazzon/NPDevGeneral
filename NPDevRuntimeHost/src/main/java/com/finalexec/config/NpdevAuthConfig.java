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
import com.npdev.dsl.v1.compiled.CompiledModel;
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
    public ExecutionAuthorizationPolicy executionAuthorizationPolicy(
            TenantIsolationPolicy tenantIsolationPolicy, CompiledModel compiledModel,
            org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> dataSourceProvider) {
        // Wave 3 (RC-B1): threads the app's declared roles[] (if any) through so a role that is
        // neither USER/OPERATOR/ADMIN nor an app-declared role is denied with a logged diagnostic
        // rather than silently -- see DefaultExecutionAuthorizationPolicy's own javadoc.
        //
        // Move 14 Phase C item C2 (RC-B3): dataSourceProvider::getIfAvailable is resolved fresh on
        // every permission check (never cached at bean-construction time) -- an InMemory-mode app
        // (no DataSource bean at all) gets null, which DefaultExecutionAuthorizationPolicy already
        // treats as "no override ever configured," identical to behavior before C2 existed.
        return new DefaultExecutionAuthorizationPolicy(
                tenantIsolationPolicy, compiledModel, dataSourceProvider::getIfAvailable);
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticatedContextResolver.class)
    public AuthenticatedContextResolver authenticatedContextResolver(
            org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> dataSourceProvider,
            CompiledModel compiledModel
    ) {
        // Base resolver decodes the principal (api-key / JWT) into tenant + actor + claim-roles;
        // the identity-aware wrapper lets the persistent identity pack override roles when populated.
        return new IdentityAwareContextResolver(new JwtAuthenticatedContextResolver(), dataSourceProvider, compiledModel);
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

    // REG-156: matchIfMissing = true on both apikey-mode beans below. Without it, a bare/un-profiled
    // boot (npdev.auth.mode never set by ANY property file -- application-default.properties didn't
    // set it, and this profile is only reachable at all since Stage B removed the old implicit
    // spring.profiles.default=dev) passed StartupValidator (which already normalizes an unset mode
    // to "apikey" internally) while these beans, gated by the literal property being SET, never
    // registered at all -- fail-open, not fail-closed: a supplied key validated at startup but no
    // filter ever enforced it against the wrong one. matchIfMissing=true only fires when the
    // property is genuinely ABSENT; an explicit npdev.auth.mode=jwt (or any other value) still wins
    // and correctly leaves these OFF, so dev/prod/jwt profiles that already set the property are
    // unaffected.
    @Bean
    @ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "apikey", matchIfMissing = true)
    public RuntimeApiKeyAuthFilter runtimeApiKeyAuthFilter(
            @Value("${npdev.auth.api-keys:}") String encodedMappings,
            CredentialRegistryService credentialRegistryService
    ) {
        // Static mappings (encodedMappings) win first; credentialRegistryService is the fallback
        // that lets a tenant onboarded at runtime (T4) authenticate without a regenerate/restart.
        return new RuntimeApiKeyAuthFilter(encodedMappings, credentialRegistryService);
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "apikey", matchIfMissing = true)
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
        // Was "/api/*", "/api/v1/*" only -- a trusted-source panel's own page/state/procedure
        // routes (arbitrary per-app paths under /generated/** plus the panel's own declared route)
        // never reached this filter at all, so a JWT-mode app had NO way to authenticate them
        // (RuntimeApiKeyAuthFilter isn't registered outside apikey mode either). Widened to match
        // RuntimeApiKeyAuthFilter's universal "/*" registration; JwtBearerAuthFilter.shouldNotFilter
        // still only actually validates when an Authorization header is present or the path is
        // /api/*, so this does not change behavior for unauthenticated requests to ordinary pages.
        bean.addUrlPatterns("/*");
        bean.setOrder(-100);
        bean.setEnabled(runtimeSettings.authEnabled());
        return bean;
    }
}
