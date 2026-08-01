package com.finalexec.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.npdev.adapters.metrics.micrometer.MicrometerMetricsSink;
import com.npdev.adapters.runtime.validation.NpdevBuildInfoInfoContributor;
import com.npdev.adapters.runtime.validation.NpdevDbHealthIndicator;
import com.npdev.adapters.runtime.validation.NpdevEventStoreHealthIndicator;
import com.npdev.adapters.runtime.validation.NpdevSchedulerHealthIndicator;
import com.npdev.adapters.runtime.validation.RuntimeRequestSizeFilter;
import com.npdev.adapters.runtime.validation.RuntimeSettings;
import com.npdev.adapters.runtime.validation.StartupValidator;
import com.npdev.adapters.runtime.validation.StrictExecutionValidator;
import com.npdev.adapters.tracing.redaction.DefaultEventRedactionPolicy;
import com.npdev.adapters.tracing.redaction.DefaultExecutionRedactionPolicy;
import com.npdev.adapters.tracing.redaction.DefaultTraceRedactionPolicy;
import com.npdev.adapters.tracing.redaction.SensitiveKeyPolicy;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.ports.EventMetaStore;
import com.npdev.kernel.ports.EventRedactionPolicy;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.ExecutionRedactionPolicy;
import com.npdev.kernel.ports.ExecutionSummaryStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.MetricsSink;
import com.npdev.kernel.ports.TraceRedactionPolicy;
import com.npdev.kernel.ports.TraceStore;
import com.npdev.kernel.ports.TraceSummaryStore;
import com.npdev.kernel.runtime.SchedulerRuntimeState;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Configuration
public class NpdevObservabilityConfig {

    /**
     * R80 (ledger/items/REG-80.yml, docs/MOVE7_IMPLEMENTATION_SPEC.md): registers the model's own
     * {@code field.sensitive: true} declarations into {@link SensitiveKeyPolicy}, OR'd in alongside
     * (not replacing) its static key-name-substring denylist -- so {@code
     * traceRedactionPolicy}/{@code eventRedactionPolicy}/{@code executionRedactionPolicy} below
     * redact a model-declared sensitive field even when its name doesn't match any static
     * substring. Wired here, not in {@code NpdevCapabilityBindingConfig}, because this class is
     * where {@code CompiledModel} and the tracing-redaction-default adapter types are already both
     * in scope (the {@code StartupValidator} bean below already depends on {@code CompiledModel}).
     * The registration itself has no ordering requirement against the policy beans: {@code
     * SensitiveKeyPolicy.isSensitiveKey} is only ever called in response to real request/event/trace
     * activity, which cannot happen before Spring finishes constructing every singleton in this
     * context -- so which of these independent {@code @Bean} methods Spring happens to run first
     * does not matter.
     */
    @Bean
    public String sensitiveFieldModelRegistration(CompiledModel compiledModel) {
        Set<String> fieldNames = sensitiveFieldNames(compiledModel);
        SensitiveKeyPolicy.registerModelSensitiveFieldNames(fieldNames);
        return "registered " + fieldNames.size() + " model-declared sensitive field name(s)";
    }

    /** Extracted for direct unit testing (see {@code NpdevObservabilityConfigSensitiveFieldTest}). */
    static Set<String> sensitiveFieldNames(CompiledModel compiledModel) {
        Set<String> fieldNames = new LinkedHashSet<>();
        for (CompiledConcept concept : compiledModel.getConcepts()) {
            for (CompiledField field : concept.getFields()) {
                if (field.isSensitive() && field.getName() != null) {
                    fieldNames.add(field.getName());
                }
            }
        }
        return fieldNames;
    }

    @Bean
    public TraceRedactionPolicy traceRedactionPolicy() {
        return new DefaultTraceRedactionPolicy();
    }

    @Bean
    public EventRedactionPolicy eventRedactionPolicy() {
        return new DefaultEventRedactionPolicy();
    }

    @Bean
    public ExecutionRedactionPolicy executionRedactionPolicy() {
        return new DefaultExecutionRedactionPolicy();
    }

    @Bean
    public RuntimeRequestSizeFilter runtimeRequestSizeFilter(RuntimeSettings runtimeSettings) {
        return new RuntimeRequestSizeFilter(runtimeSettings.apiMaxBodyBytes());
    }

    @Bean
    public FilterRegistrationBean<RuntimeRequestSizeFilter> runtimeRequestSizeFilterRegistration(
            RuntimeRequestSizeFilter runtimeRequestSizeFilter
    ) {
        FilterRegistrationBean<RuntimeRequestSizeFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(runtimeRequestSizeFilter);
        bean.addUrlPatterns("/api/*", "/api/v1/*");
        bean.setOrder(-90);
        return bean;
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer npdevJsonDepthCustomizer(RuntimeSettings runtimeSettings) {
        return builder -> builder.postConfigurer(objectMapper -> objectMapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxNestingDepth(runtimeSettings.apiMaxJsonDepth())
                        .build()
        ));
    }

    @Bean
    public TraceSummaryStore traceSummaryStore(TraceStore traceStore) {
        if (traceStore instanceof TraceSummaryStore store) {
            return store;
        }
        return query -> List.of();
    }

    @Bean
    public ExecutionSummaryStore executionSummaryStore(FlowInstanceStore flowInstanceStore) {
        if (flowInstanceStore instanceof ExecutionSummaryStore store) {
            return store;
        }
        return (tenantId, mode, limit, offset) -> List.of();
    }

    @Bean
    public EventMetaStore eventMetaStore(EventStore eventStore) {
        if (eventStore instanceof EventMetaStore store) {
            return store;
        }
        return (tenantId, correlationId, limit, offset) -> List.of();
    }

    @Bean
    public StartupValidator startupValidator(
            RuntimeSettings runtimeSettings,
            ObjectProvider<DataSource> dataSourceProvider,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            Environment environment,
            @Value("${npdev.auth.mode:}") String authMode,
            @Value("${npdev.auth.api-keys:}") String apiKeyMappings,
            @Value("${npdev.auth.jwt.issuer:}") String jwtIssuer,
            @Value("${npdev.auth.jwt.audience:}") String jwtAudience,
            @Value("${npdev.auth.jwt.public-key-path:}") String jwtPublicKeyPath,
            @Value("${npdev.auth.jwt.private-key-path:}") String jwtPrivateKeyPath,
            CompiledModel compiledModel,
            CapabilityRegistry capabilityRegistry
    ) {
        return new StartupValidator(
                runtimeSettings,
                dataSourceProvider.getIfAvailable(),
                eventStore,
                flowInstanceStore,
                environment,
                authMode,
                apiKeyMappings,
                jwtIssuer,
                jwtAudience,
                jwtPublicKeyPath,
                jwtPrivateKeyPath,
                compiledModel,
                capabilityRegistry
        );
    }

    @Bean
    public StrictExecutionValidator strictExecutionValidator(
            @Value("${npdev.strict-execution.enabled:true}") boolean strictExecutionEnabled,
            // LNCH-7: '/' not '\\' -- see NpdevFileStoreConfig's identical fix; a literal backslash
            // in a property-default string is not a path separator on Linux, so this resolved to a
            // single bogus directory name instead of user.dir/npdev-generated under Docker/Alpine.
            @Value("${npdev.strict-execution.generated-root:${user.dir}/npdev-generated}") String strictExecutionGeneratedRoot,
            @Value("${npdev.execution.mode:governed}") String executionMode,
            @Value("${npdev.runtime.surface-profile:supported-core}") String surfaceProfile,
            @Value("${npdev.runtime.supported-surface-enforced:true}") boolean supportedSurfaceEnforced
    ) {
        return new StrictExecutionValidator(
                strictExecutionEnabled,
                strictExecutionGeneratedRoot,
                executionMode,
                surfaceProfile,
                supportedSurfaceEnforced
        );
    }

    @Bean(name = "npdevDb")
    public HealthIndicator npdevDbHealthIndicator(
            RuntimeSettings runtimeSettings,
            ObjectProvider<DataSource> dataSourceProvider
    ) {
        return new NpdevDbHealthIndicator(runtimeSettings, dataSourceProvider.getIfAvailable());
    }

    @Bean(name = "npdevScheduler")
    public HealthIndicator npdevSchedulerHealthIndicator(
            RuntimeSettings runtimeSettings,
            SchedulerRuntimeState schedulerRuntimeState
    ) {
        return new NpdevSchedulerHealthIndicator(runtimeSettings, schedulerRuntimeState);
    }

    @Bean(name = "npdevEventStore")
    public HealthIndicator npdevEventStoreHealthIndicator(RuntimeSettings runtimeSettings, EventStore eventStore) {
        return new NpdevEventStoreHealthIndicator(runtimeSettings, eventStore);
    }

    @Bean
    public InfoContributor npdevInfoContributor(RuntimeSettings runtimeSettings) {
        return new NpdevBuildInfoInfoContributor(runtimeSettings);
    }

    @Bean
    public MetricsSink metricsSink(MeterRegistry meterRegistry) {
        return new MicrometerMetricsSink(meterRegistry);
    }
}
