package com.finalexec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.CredentialRegistryService;
import com.finalexec.npdev.service.TenantRegistryService;
import com.npdev.adapters.runtime.validation.RuntimeSettings;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generated.runtime.config.RuntimeApiKeyAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REG-156: {@code runtimeApiKeyAuthFilter}/{@code runtimeApiKeyAuthFilterRegistration} carry
 * {@code @ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "apikey", matchIfMissing =
 * true)}. Before that fix, a bare/un-profiled boot (the property genuinely unset by any property
 * file) passed {@code StartupValidator} -- which already normalizes an unset mode to "apikey"
 * internally -- while these beans, gated on the literal property being SET, never registered at
 * all: a supplied API key validated at startup but no filter ever enforced it. This test proves
 * the conditional wiring itself, via a real Spring {@link ApplicationContextRunner} standing up
 * the whole {@link NpdevAuthConfig} configuration class (not calling its bean methods directly,
 * which would bypass Spring's own condition evaluation).
 */
class NpdevAuthConfigAuthModeTest {

    @Test
    void apiKeyFilterRegistersWhenAuthModeIsAbsent() {
        contextRunner()
                .run(context -> {
                    assertThat(context).hasSingleBean(RuntimeApiKeyAuthFilter.class);
                    assertThat(context.getBeansOfType(FilterRegistrationBean.class))
                            .anySatisfy((name, bean) -> assertThat(bean.getFilter())
                                    .isInstanceOf(RuntimeApiKeyAuthFilter.class));
                    assertThat(context).doesNotHaveBean(JwtBearerAuthFilter.class);
                });
    }

    @Test
    void apiKeyFilterRegistersWhenAuthModeIsExplicitlyApikey() {
        contextRunner()
                .withPropertyValues("npdev.auth.mode=apikey")
                .run(context -> {
                    assertThat(context).hasSingleBean(RuntimeApiKeyAuthFilter.class);
                    assertThat(context).doesNotHaveBean(JwtBearerAuthFilter.class);
                });
    }

    @Test
    void apiKeyFilterDoesNotRegisterWhenAuthModeIsJwt() {
        contextRunner()
                .withPropertyValues("npdev.auth.mode=jwt")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RuntimeApiKeyAuthFilter.class);
                    assertThat(context).hasSingleBean(JwtBearerAuthFilter.class);
                });
    }

    @Test
    void apiKeyFilterDoesNotRegisterForAnyOtherExplicitMode() {
        // matchIfMissing=true fires ONLY when the property is genuinely absent -- an explicit
        // value other than "apikey" (here a value neither apikey- nor jwt-mode recognizes) still
        // correctly leaves the apikey beans OFF, same as any already-set profile.
        contextRunner()
                .withPropertyValues("npdev.auth.mode=none")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RuntimeApiKeyAuthFilter.class);
                    assertThat(context).doesNotHaveBean(JwtBearerAuthFilter.class);
                });
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withBean(ModelHolder.class, () -> new ModelHolder(identityModel()))
                .withBean(CredentialRegistryService.class,
                        () -> new CredentialRegistryService(new FixedProvider(null)))
                .withBean(TenantRegistryService.class,
                        () -> new TenantRegistryService(new FixedProvider(null)))
                .withBean(RuntimeSettings.class, () -> new RuntimeSettings(
                        "inproc", true, 100, 2000, true, 1024, 64,
                        null, null, null, 5, 30, 10, 4096, null, true))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ResourceLoader.class, DefaultResourceLoader::new)
                .withUserConfiguration(NpdevAuthConfig.class);
    }

    private static CompiledModel identityModel() {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", new CompiledConcept("User", "User", "identity_users", List.of()));
        return new CompiledModel("test", "1.0.0", concepts);
    }

    private record FixedProvider(DataSource dataSource) implements ObjectProvider<DataSource> {
        @Override
        public DataSource getObject(Object... args) {
            return dataSource;
        }

        @Override
        public DataSource getObject() {
            return dataSource;
        }

        @Override
        public DataSource getIfAvailable() {
            return dataSource;
        }

        @Override
        public DataSource getIfAvailable(Supplier<DataSource> defaultSupplier) {
            return dataSource;
        }

        @Override
        public DataSource getIfUnique() {
            return dataSource;
        }

        @Override
        public DataSource getIfUnique(Supplier<DataSource> defaultSupplier) {
            return dataSource;
        }
    }
}
