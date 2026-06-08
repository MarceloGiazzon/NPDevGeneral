package com.finalexec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.service.ClasspathArtifactRealizationProvider;
import com.finalexec.npdev.service.FileRuntimePluginExecutionSummaryStore;
import com.finalexec.npdev.service.FilesystemArtifactRealizationProvider;
import com.finalexec.npdev.service.GenericCustomProcedureCapabilityAdapter;
import com.finalexec.npdev.service.GenericMountedCapabilityHandler;
import com.finalexec.npdev.service.JavaSourceArtifactRealizationProvider;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.PluginManifestSchemaValidator;
import com.finalexec.npdev.service.PluginPackageSchemaValidator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.RuntimePluginArtifactRealizationProvider;
import com.finalexec.npdev.service.RuntimePluginExecutionSummaryStore;
import com.finalexec.npdev.service.RuntimePluginManifestLoader;
import com.finalexec.npdev.service.RuntimePluginPackageAdmissionEvaluator;
import com.finalexec.npdev.service.RuntimePluginPackageCatalog;
import com.finalexec.npdev.service.RuntimePluginPackageCompatibilityEvaluator;
import com.finalexec.npdev.service.RuntimePluginPackageDescriptorLoader;
import com.finalexec.npdev.service.RuntimePluginPackageDiscoveryService;
import com.finalexec.npdev.service.RuntimePluginPackageRealizationService;
import com.finalexec.npdev.service.RuntimePluginPackageTrustEvaluator;
import com.finalexec.npdev.service.RuntimePluginPackagedArtifactHandlerResolver;
import com.finalexec.npdev.service.RuntimePluginProfileDiagnostics;
import com.finalexec.npdev.service.RuntimePluginProfileResolver;
import com.finalexec.npdev.service.RuntimePluginRealizationProvider;
import com.finalexec.npdev.service.RuntimePluginRealizationProviderCatalog;
import com.finalexec.npdev.service.RuntimePluginRuntimeRefResolver;
import com.finalexec.npdev.service.RuntimePluginStatusSummary;
import com.finalexec.npdev.service.RuntimeRefArtifactRealizationProvider;
import com.finalexec.npdev.service.SandboxedPluginExecutionEngine;
import com.npdev.adapters.notification.inproc.InProcNotificationCapabilityAdapter;
import com.npdev.adapters.notification.inproc.InProcWarningNotificationCapabilityAdapter;
import com.npdev.adapters.persistence.inproc.InMemoryPersistenceCapabilityAdapter;
import com.npdev.adapters.persistence.postgres.PostgresPersistenceCapabilityAdapter;
import com.npdev.adapters.webhook.inproc.InProcWebhookCapabilityAdapter;
import com.npdev.dsl.v1.compiled.CompiledCapability;
import com.npdev.dsl.v1.compiled.CompiledCapabilityBinding;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.capabilities.CapabilityBindingResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Configuration
public class NpdevPluginConfig {

    @Bean
    public RuntimePluginProfileResolver.ResolvedRuntimePluginProfile runtimePluginProfile(
            @Value("${npdev.runtime.deployment-profile:${npdev.runtime.plugin-profile:default}}") String deploymentProfile,
            @Value("${npdev.runtime.deployment.bindings-manifest:${npdev.runtime.bindings-manifest:}}") String bindingsManifest,
            @Value("${npdev.runtime.deployment.plugin-manifest:${npdev.runtime.plugin-manifest:}}") String pluginManifest,
            @Value("${npdev.runtime.execution-environment:${npdev.runtime.environment:}}") String executionEnvironment
    ) {
        return new RuntimePluginProfileResolver(
                deploymentProfile,
                bindingsManifest,
                pluginManifest,
                executionEnvironment
        ).resolve();
    }

    @Bean
    public PluginManifestSchemaValidator pluginManifestSchemaValidator() {
        return new PluginManifestSchemaValidator();
    }

    @Bean
    public RuntimePluginManifestLoader runtimePluginManifestLoader(
            ObjectMapper objectMapper,
            PluginManifestSchemaValidator pluginManifestSchemaValidator
    ) {
        return new RuntimePluginManifestLoader(objectMapper, pluginManifestSchemaValidator);
    }

    @Bean
    public RuntimePluginManifest runtimePluginManifest(
            RuntimePluginManifestLoader runtimePluginManifestLoader,
            RuntimePluginProfileResolver.ResolvedRuntimePluginProfile runtimePluginProfile
    ) {
        return runtimePluginManifestLoader.load(runtimePluginProfile.pluginManifestPath());
    }

    @Bean
    public PluginPackageSchemaValidator pluginPackageSchemaValidator() {
        return new PluginPackageSchemaValidator();
    }

    @Bean
    public RuntimePluginPackageDescriptorLoader runtimePluginPackageDescriptorLoader(
            ObjectMapper objectMapper,
            PluginPackageSchemaValidator pluginPackageSchemaValidator
    ) {
        return new RuntimePluginPackageDescriptorLoader(objectMapper, pluginPackageSchemaValidator);
    }

    @Bean
    public RuntimePluginPackageDiscoveryService runtimePluginPackageDiscoveryService(
            ObjectMapper objectMapper,
            @Value("${npdev.runtime.plugin-package-location:npdev/plugin-packages}") String discoveryLocation,
            @Value("${npdev.runtime.plugin-package-directory:}") String pluginPackageDirectory,
            @Value("${npdev.runtime.plugin-package-discovery-mode:}") String pluginPackageDiscoveryMode
    ) {
        return new RuntimePluginPackageDiscoveryService(
                objectMapper,
                discoveryLocation,
                pluginPackageDirectory,
                pluginPackageDiscoveryMode
        );
    }

    @Bean
    public RuntimePluginPackageCompatibilityEvaluator runtimePluginPackageCompatibilityEvaluator(
            @Value("${npdev.runtime.plugin-package-runtime-api-version:1.0}") String runtimeApiVersion,
            @Value("${npdev.runtime.plugin-package-bootstrap-version:1.0.0}") String bootstrapVersion,
            @Value("${npdev.runtime.plugin-package-npdev-version:0.1.0}") String runtimeNpdevVersion,
            RuntimePluginProfileResolver.ResolvedRuntimePluginProfile runtimePluginProfile
    ) {
        return new RuntimePluginPackageCompatibilityEvaluator(
                runtimeApiVersion,
                bootstrapVersion,
                runtimeNpdevVersion,
                "1.0",
                runtimePluginProfile.pluginManifestPath()
        );
    }

    @Bean
    public RuntimePluginPackageTrustEvaluator runtimePluginPackageTrustEvaluator() {
        return new RuntimePluginPackageTrustEvaluator(List.of("internal", "local-dev"), true);
    }

    @Bean
    public RuntimePluginPackageAdmissionEvaluator runtimePluginPackageAdmissionEvaluator(
            RuntimePluginPackageCompatibilityEvaluator runtimePluginPackageCompatibilityEvaluator,
            RuntimePluginPackageTrustEvaluator runtimePluginPackageTrustEvaluator
    ) {
        return new RuntimePluginPackageAdmissionEvaluator(
                runtimePluginPackageCompatibilityEvaluator,
                runtimePluginPackageTrustEvaluator
        );
    }

    @Bean
    public RuntimePluginPackageCatalog runtimePluginPackageCatalog(
            RuntimePluginPackageDiscoveryService runtimePluginPackageDiscoveryService,
            RuntimePluginPackageDescriptorLoader runtimePluginPackageDescriptorLoader,
            RuntimePluginPackageAdmissionEvaluator runtimePluginPackageAdmissionEvaluator
    ) {
        RuntimePluginPackageDiscoveryService.DiscoveryResult discoveryResult = runtimePluginPackageDiscoveryService.discover();
        List<RuntimePluginPackageCatalog.PackageCatalogEntry> entries = discoveryResult.candidates().stream()
                .map(candidate -> buildPluginPackageCatalogEntry(
                        candidate,
                        runtimePluginPackageDescriptorLoader,
                        runtimePluginPackageAdmissionEvaluator
                ))
                .toList();
        return new RuntimePluginPackageCatalog(
                discoveryResult,
                runtimePluginPackageAdmissionEvaluator.toSummary(),
                runtimePluginPackageAdmissionEvaluator.trustPolicySummary(),
                entries
        );
    }

    @Bean
    public RuntimePluginAdapterRegistry runtimePluginAdapterRegistry(RuntimePluginManifest runtimePluginManifest) {
        return new RuntimePluginAdapterRegistry(runtimePluginManifest);
    }

    @Bean
    public RuntimePluginRuntimeRefResolver runtimePluginRuntimeRefResolver(
            RuntimePluginRealizationProviderCatalog runtimePluginRealizationProviderCatalog
    ) {
        return new RuntimePluginRuntimeRefResolver(runtimePluginRealizationProviderCatalog);
    }

    @Bean
    public RuntimePluginRealizationProviderCatalog runtimePluginRealizationProviderCatalog(
            List<RuntimePluginRealizationProvider> runtimePluginRealizationProviders
    ) {
        return new RuntimePluginRealizationProviderCatalog(runtimePluginRealizationProviders);
    }

    @Bean
    public RuntimePluginPackagedArtifactHandlerResolver runtimePluginPackagedArtifactHandlerResolver(
            RuntimePluginRuntimeRefResolver runtimePluginRuntimeRefResolver
    ) {
        return new RuntimePluginPackagedArtifactHandlerResolver(runtimePluginRuntimeRefResolver);
    }

    @Bean
    public RuntimePluginArtifactRealizationProvider classpathArtifactRealizationProvider(
            RuntimePluginPackagedArtifactHandlerResolver runtimePluginPackagedArtifactHandlerResolver
    ) {
        return new ClasspathArtifactRealizationProvider(runtimePluginPackagedArtifactHandlerResolver);
    }

    @Bean
    public RuntimePluginArtifactRealizationProvider filesystemArtifactRealizationProvider(
            RuntimePluginPackagedArtifactHandlerResolver runtimePluginPackagedArtifactHandlerResolver
    ) {
        return new FilesystemArtifactRealizationProvider(runtimePluginPackagedArtifactHandlerResolver);
    }

    @Bean
    public RuntimePluginArtifactRealizationProvider runtimeRefArtifactRealizationProvider(
            RuntimePluginRuntimeRefResolver runtimePluginRuntimeRefResolver
    ) {
        return new RuntimeRefArtifactRealizationProvider(runtimePluginRuntimeRefResolver);
    }

    @Bean
    public RuntimePluginArtifactRealizationProvider javaSourceArtifactRealizationProvider(
            RuntimePluginRuntimeRefResolver runtimePluginRuntimeRefResolver
    ) {
        return new JavaSourceArtifactRealizationProvider(runtimePluginRuntimeRefResolver);
    }

    @Bean
    public RuntimePluginPackageRealizationService runtimePluginPackageRealizationService(
            RuntimePluginPackageCatalog runtimePluginPackageCatalog,
            RuntimePluginProfileResolver.ResolvedRuntimePluginProfile runtimePluginProfile,
            List<RuntimePluginArtifactRealizationProvider> runtimePluginArtifactRealizationProviders
    ) {
        return new RuntimePluginPackageRealizationService(
                runtimePluginPackageCatalog,
                runtimePluginProfile.pluginManifestPath(),
                runtimePluginArtifactRealizationProviders
        );
    }

    @Bean
    public RuntimePluginRealizationProvider notificationInProcRuntimePluginRealizationProvider() {
        return namedRuntimePluginRealizationProvider(
                "notificationInProcCapabilityAdapter",
                InProcNotificationCapabilityAdapter::new
        );
    }

    @Bean
    public RuntimePluginRealizationProvider notificationWarningRuntimePluginRealizationProvider() {
        return namedRuntimePluginRealizationProvider(
                "notificationWarningCapabilityAdapter",
                InProcWarningNotificationCapabilityAdapter::new
        );
    }

    @Bean
    public RuntimePluginRealizationProvider persistenceInMemoryRuntimePluginRealizationProvider() {
        return namedRuntimePluginRealizationProvider(
                "persistenceInMemoryCapabilityAdapter",
                InMemoryPersistenceCapabilityAdapter::new
        );
    }

    @Bean
    public RuntimePluginRealizationProvider persistencePostgresRuntimePluginRealizationProvider(
            ObjectProvider<DataSource> dataSourceProvider,
            @Value("${npdev.storage.mode:in-memory}") String storageMode
    ) {
        return namedRuntimePluginRealizationProvider(
                "persistencePostgresCapabilityAdapter",
                () -> {
                    DataSource dataSource = dataSourceProvider.getIfAvailable();
                    if ("in-memory".equalsIgnoreCase(storageMode)) {
                        return new InMemoryPersistenceCapabilityAdapter();
                    }
                    if (dataSource == null) {
                        throw new IllegalStateException("DataSource is required for postgres persistence adapter");
                    }
                    return new PostgresPersistenceCapabilityAdapter(dataSource);
                }
        );
    }

    @Bean
    public RuntimePluginRealizationProvider webhookInProcRuntimePluginRealizationProvider() {
        return namedRuntimePluginRealizationProvider(
                "webhookInProcCapabilityAdapter",
                InProcWebhookCapabilityAdapter::new
        );
    }

    @Bean
    public RuntimePluginRealizationProvider customProcedureRuntimePluginRealizationProvider() {
        return namedRuntimePluginRealizationProvider(
                "genericCustomProcedureCapabilityAdapter",
                GenericCustomProcedureCapabilityAdapter::new
        );
    }

    @Bean
    public RuntimePluginRealizationProvider genericMountedCapabilityRuntimePluginRealizationProvider() {
        return namedRuntimePluginRealizationProvider(
                "genericMountedCapabilityHandler",
                GenericMountedCapabilityHandler::new
        );
    }

    @Bean
    public PluginExecutionPolicyEvaluator pluginExecutionPolicyEvaluator(
            Environment environment,
            RuntimePluginProfileResolver.ResolvedRuntimePluginProfile runtimePluginProfile,
            @Value("${npdev.runtime.plugin-policy.deny-plugin-ids:}") String deniedPluginIds,
            @Value("${npdev.runtime.plugin-policy.deny-adapter-ids:}") String deniedAdapterIds,
            @Value("${npdev.runtime.plugin-policy.allow-plugin-ids:}") String allowedPluginIds,
            @Value("${npdev.runtime.plugin-policy.allow-adapter-ids:}") String allowedAdapterIds
    ) {
        return new PluginExecutionPolicyEvaluator(
                environment,
                runtimePluginProfile.executionEnvironment(),
                deniedPluginIds,
                deniedAdapterIds,
                allowedPluginIds,
                allowedAdapterIds
        );
    }

    @Bean
    public RuntimePluginExecutionSummaryStore runtimePluginExecutionSummaryStore(
            ObjectMapper objectMapper,
            @Value("${npdev.runtime.plugin-execution-summary-path:runtime-data/plugin-executions.jsonl}") String executionSummaryPath
    ) {
        return new FileRuntimePluginExecutionSummaryStore(objectMapper, java.nio.file.Path.of(executionSummaryPath));
    }

    @Bean(destroyMethod = "close")
    public SandboxedPluginExecutionEngine sandboxedPluginExecutionEngine(
            PluginExecutionPolicyEvaluator pluginExecutionPolicyEvaluator,
            RuntimePluginExecutionSummaryStore runtimePluginExecutionSummaryStore,
            @Value("${npdev.runtime.plugin-timeout-ms:1000}") long pluginTimeoutMs
    ) {
        return new SandboxedPluginExecutionEngine(
                pluginTimeoutMs,
                pluginExecutionPolicyEvaluator,
                runtimePluginExecutionSummaryStore
        );
    }

    @Bean
    public RuntimePluginProfileDiagnostics runtimePluginProfileDiagnostics(
            RuntimePluginProfileResolver.ResolvedRuntimePluginProfile runtimePluginProfile,
            CompiledModel compiledModel,
            CapabilityBindingResolver capabilityBindingResolver,
            RuntimePluginManifest runtimePluginManifest,
            PluginExecutionPolicyEvaluator pluginExecutionPolicyEvaluator
    ) {
        Map<String, String> selectedAdapterIds = new LinkedHashMap<>();
        java.util.List<String> unresolvedCapabilities = new java.util.ArrayList<>();

        Map<String, CompiledCapability> capabilitiesByName = compiledModel.getCapabilities().stream()
                .collect(Collectors.toMap(
                        CompiledCapability::getName,
                        Function.identity(),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));

        for (CompiledCapabilityBinding binding : compiledModel.getBindings()) {
            if ("eventbus".equalsIgnoreCase(binding.getCapability())) {
                continue;
            }
            CompiledCapability capability = capabilitiesByName.get(binding.getCapability());
            if (capability == null) {
                continue;
            }
            var resolvedBinding = capabilityBindingResolver.resolve(
                    capability.getName(),
                    capability.getType(),
                    "",
                    pluginExecutionPolicyEvaluator.runtimeEnvironment()
            );
            if (resolvedBinding.isEmpty()) {
                unresolvedCapabilities.add(capability.getName());
                continue;
            }
            selectedAdapterIds.put(capability.getName(), resolvedBinding.get().adapterId());
        }

        List<String> admittedAdapterIds = runtimePluginManifest.toSummary().activeAdapterIds();
        List<String> missingAdapterIds = selectedAdapterIds.values().stream()
                .filter(adapterId -> admittedAdapterIds.stream().noneMatch(admitted -> admitted.equalsIgnoreCase(adapterId)))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        RuntimePluginProfileDiagnostics diagnostics = new RuntimePluginProfileDiagnostics(
                runtimePluginProfile.activeProfile(),
                runtimePluginProfile.selectionMode(),
                runtimePluginProfile.bindingsManifestPath(),
                runtimePluginProfile.pluginManifestPath(),
                runtimePluginProfile.executionEnvironment(),
                admittedAdapterIds,
                selectedAdapterIds,
                unresolvedCapabilities.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(),
                missingAdapterIds,
                pluginExecutionPolicyEvaluator.policySummary()
        );
        diagnostics.assertCoherent();
        return diagnostics;
    }

    @Bean
    public RuntimePluginStatusSummary runtimePluginStatusSummary(
            RuntimePluginProfileResolver.ResolvedRuntimePluginProfile runtimePluginProfile,
            RuntimePluginManifest runtimePluginManifest,
            RuntimePluginProfileDiagnostics runtimePluginProfileDiagnostics,
            RuntimePluginPackageCatalog runtimePluginPackageCatalog,
            RuntimePluginPackageRealizationService runtimePluginPackageRealizationService,
            RuntimePluginPackagedArtifactHandlerResolver runtimePluginPackagedArtifactHandlerResolver,
            RuntimePluginRealizationProviderCatalog runtimePluginRealizationProviderCatalog,
            PluginExecutionPolicyEvaluator pluginExecutionPolicyEvaluator,
            SandboxedPluginExecutionEngine sandboxedPluginExecutionEngine
    ) {
        return new RuntimePluginStatusSummary(
                runtimePluginProfile,
                runtimePluginManifest,
                runtimePluginProfileDiagnostics,
                runtimePluginPackageCatalog,
                runtimePluginPackageRealizationService,
                runtimePluginPackagedArtifactHandlerResolver,
                runtimePluginRealizationProviderCatalog,
                pluginExecutionPolicyEvaluator,
                sandboxedPluginExecutionEngine
        );
    }

    private static RuntimePluginRealizationProvider namedRuntimePluginRealizationProvider(
            String runtimeRef,
            java.util.function.Supplier<Object> supplier
    ) {
        return new RuntimePluginRealizationProvider() {
            @Override
            public String runtimeRef() {
                return runtimeRef;
            }

            @Override
            public Object realize() {
                return supplier.get();
            }
        };
    }

    private static RuntimePluginPackageCatalog.PackageCatalogEntry buildPluginPackageCatalogEntry(
            RuntimePluginPackageDiscoveryService.DiscoveredPackageCandidate candidate,
            RuntimePluginPackageDescriptorLoader runtimePluginPackageDescriptorLoader,
            RuntimePluginPackageAdmissionEvaluator runtimePluginPackageAdmissionEvaluator
    ) {
        try {
            var descriptor = runtimePluginPackageDescriptorLoader.load(candidate.resourcePath());
            return new RuntimePluginPackageCatalog.PackageCatalogEntry(
                    candidate,
                    descriptor,
                    runtimePluginPackageAdmissionEvaluator.evaluate(descriptor)
            );
        } catch (RuntimeException exception) {
            return new RuntimePluginPackageCatalog.PackageCatalogEntry(
                    candidate,
                    null,
                    RuntimePluginPackageAdmissionEvaluator.AdmissionDecision.reject(
                            "DESCRIPTOR_LOAD_FAILED",
                            exception.getMessage()
                    )
            );
        }
    }
}
