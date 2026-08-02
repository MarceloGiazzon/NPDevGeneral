package com.finalexec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.adapters.events.inproc.InProcEventBus;
import com.npdev.adapters.events.inproc.InProcEventStore;
import com.npdev.adapters.expression.cel.CelInvariantEngine;
import com.npdev.adapters.flowcompiled.CompiledModelFlowDefinitionProvider;
import com.npdev.adapters.json.jackson.JacksonJsonCodec;
import com.npdev.adapters.schema.validator.DefaultSchemaValidator;
import com.npdev.dsl.v1.compiled.CompiledCapability;
import com.npdev.dsl.v1.compiled.CompiledCapabilityBinding;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generated.runtime.config.GeneratedBindingManifestLoader;
import com.npdev.generated.runtime.config.GeneratedPermissionManifestLoader;
import com.npdev.generated.runtime.config.GeneratedRuntimeOverridesLoader;
import com.npdev.generated.runtime.model.NPDevModelProvider;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.RegistryCapabilityDispatcher;
import com.npdev.kernel.capabilities.CapabilityBindingManifest;
import com.npdev.kernel.capabilities.CapabilityBindingResolver;
import com.npdev.kernel.capabilities.RuntimeOverrideCapabilityBindingResolver;
import com.npdev.kernel.capabilities.RuntimeOverridesManifest;
import com.npdev.kernel.capability.CapabilityPolicyOverrides;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.concepts.InMemoryConceptGatewayTraceSink;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.InvariantScopeProvider;
import com.npdev.kernel.ports.JsonCodec;
import com.npdev.kernel.ports.MetricsSink;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.RuntimeInvariantEngineFactory;
import com.npdev.kernel.ports.SchemaValidator;
import com.npdev.kernel.procedures.ProcedureExecutor;
import com.npdev.kernel.security.PermissionGrant;
import com.npdev.kernel.security.StaticPermissionEvaluator;
import com.finalexec.npdev.service.ProcedureRunner;
import com.npdev.runtime.support.GeneratedCrudRuntimeSupport;
import com.npdev.runtime.support.InMemoryOrchestrationExecutionRegistry;
import com.npdev.runtime.support.OrchestrationExecutionRegistry;
import com.npdev.runtime.support.RuntimeClock;
import com.npdev.runtime.support.SystemRuntimeClock;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
public class NpdevCapabilityBindingConfig {

    @Bean
    public CompiledModel compiledModel(NPDevModelProvider modelProvider) {
        return modelProvider.compiledModel();
    }

    @Bean
    public InvariantEngine invariantEngine(CompiledModel compiledModel) {
        return CelInvariantEngine.fromCompiledModel(compiledModel);
    }

    @Bean
    public ConceptGateway conceptGateway(
            CompiledModel compiledModel,
            ConceptStore conceptStore,
            AuditLogStore auditLogStore,
            ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactionManager
    ) {
        // B18 (Move 9 A2, docs/ACCEPTED_BOUNDARIES.md): a real transaction manager (present against
        // any real DataSource-backed profile) closes the row-authz check-then-act race; its absence
        // (e.g. InMemory mode, no DataSource at all) degrades to today's behavior exactly, same as
        // AggregateRuntime's identical ObjectProvider<PlatformTransactionManager> precedent.
        var manager = transactionManager.getIfAvailable();
        com.npdev.kernel.ports.TransactionRunner transactionRunner = manager == null
                ? com.npdev.kernel.ports.TransactionRunner.none()
                : new SpringTransactionRunner(manager);
        return new DefaultConceptGateway(
                conceptStore,
                PermissionEvaluator.allowAll(),
                com.npdev.kernel.ports.TenantIsolationPolicy.STRICT_EQUALS,
                auditLogStore,
                RuntimeConceptGatewaySemanticPolicies.fromCompiledModel(compiledModel),
                new InMemoryConceptGatewayTraceSink(),
                transactionRunner
        );
    }

    /**
     * RC-A3 (Move 14 Phase B item B2/B3, REG-114): resolves the scoped-property cascade against
     * {@code workspace::PropertyValue} through the SAME {@code conceptGateway} bean above -- tenant
     * isolation/permissions/audit for the underlying rows come from there, exactly like every other
     * consumer of this gateway.
     */
    @Bean
    public com.npdev.kernel.properties.PropertyResolver propertyResolver(
            ConceptGateway conceptGateway, AuditLogStore auditLogStore, CompiledModel compiledModel) {
        return new com.npdev.kernel.properties.DefaultPropertyResolver(conceptGateway, auditLogStore, compiledModel);
    }

    @Bean
    public RuntimeInvariantEngineFactory runtimeInvariantEngineFactory(CompiledModel compiledModel) {
        return new RuntimeInvariantEngineFactory() {
            @Override
            public InvariantEngine create(
                    UniqueValueLookup uniqueValueLookup,
                    ConflictLookup conflictLookup
            ) {
                return create(uniqueValueLookup, conflictLookup, InvariantScopeProvider.noop());
            }

            @Override
            public InvariantEngine create(
                    UniqueValueLookup uniqueValueLookup,
                    ConflictLookup conflictLookup,
                    InvariantScopeProvider invariantScopeProvider
            ) {
                        return CelInvariantEngine.fromCompiledModel(
                        compiledModel,
                        (requestedEntity, fieldName, value, rawPayload) -> uniqueValueLookup != null
                                && uniqueValueLookup.exists(requestedEntity, fieldName, value, rawPayload),
                        new CelInvariantEngine.ConflictChecker() {
                            @Override
                            public boolean conflicts(
                                    String resourceField,
                                    Object resourceId,
                                    String startsAtField,
                                    Object startsAt,
                                    String durationField,
                                    Object durationMinutes,
                                    Object excludeId,
                                    Object payload
                            ) {
                                return conflictLookup != null && conflictLookup.conflicts(
                                        resourceField,
                                        resourceId,
                                        startsAtField,
                                        startsAt,
                                        durationField,
                                        durationMinutes,
                                        excludeId,
                                        payload
                                );
                            }
                        },
                        (conceptName, fieldPath, expectedValue, state, payload) -> invariantScopeProvider != null
                                && invariantScopeProvider.exists(conceptName, fieldPath, expectedValue, state, payload)
                );
            }
        };
    }

    @Bean
    public EventBus eventBus() {
        return new InProcEventBus();
    }

    @Bean
    public JsonCodec jsonCodec() {
        return new JacksonJsonCodec();
    }

    @Bean
    public SchemaValidator schemaValidator() {
        return new DefaultSchemaValidator();
    }

    @Bean
    public CapabilityBindingManifest capabilityBindingManifest(
            ObjectMapper objectMapper,
            com.finalexec.npdev.service.RuntimePluginProfileResolver.ResolvedRuntimePluginProfile runtimePluginProfile
    ) {
        return GeneratedBindingManifestLoader.load(objectMapper, runtimePluginProfile.bindingsManifestPath());
    }

    @Bean
    public RuntimeOverridesManifest runtimeOverridesManifest(ObjectMapper objectMapper) {
        return GeneratedRuntimeOverridesLoader.load(objectMapper);
    }

    @Bean
    public CapabilityBindingResolver capabilityBindingResolver(
            CapabilityBindingManifest manifest,
            RuntimeOverridesManifest runtimeOverridesManifest
    ) {
        return new RuntimeOverrideCapabilityBindingResolver(manifest, runtimeOverridesManifest);
    }

    @Bean
    public CapabilityAdapterResolver capabilityAdapterResolver(
            CapabilityBindingResolver capabilityBindingResolver,
            Environment environment,
            com.finalexec.npdev.service.RuntimePluginAdapterRegistry runtimePluginAdapterRegistry,
            com.finalexec.npdev.service.RuntimePluginPackageRealizationService runtimePluginPackageRealizationService,
            com.finalexec.npdev.service.SandboxedPluginExecutionEngine sandboxedPluginExecutionEngine,
            com.finalexec.npdev.service.RuntimePluginProfileResolver.ResolvedRuntimePluginProfile runtimePluginProfile
    ) {
        return new CapabilityAdapterResolver(
                capabilityBindingResolver,
                environment,
                runtimePluginProfile.executionEnvironment(),
                runtimePluginAdapterRegistry,
                runtimePluginPackageRealizationService,
                sandboxedPluginExecutionEngine
        );
    }

    @Bean
    public List<PermissionGrant> permissionGrants(ObjectMapper objectMapper) {
        return GeneratedPermissionManifestLoader.load(objectMapper);
    }

    @Bean
    public PermissionEvaluator permissionEvaluator(List<PermissionGrant> permissionGrants) {
        return new StaticPermissionEvaluator(permissionGrants);
    }

    @Bean
    public CapabilityRegistry capabilityRegistry(
            CompiledModel compiledModel,
            CapabilityAdapterResolver capabilityAdapterResolver
    ) {
        CapabilityRegistry registry = new CapabilityRegistry();
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
                throw new IllegalStateException("Binding references unknown capability: " + binding.getCapability());
            }

            CapabilityAdapterResolver.ResolvedCapabilityAdapter resolvedAdapter =
                    capabilityAdapterResolver.resolve(capability, binding);
            registry.register(
                    capability.getName(),
                    capability.getType(),
                    resolvedAdapter.adapterId(),
                    resolvedAdapter.handler()
            );
        }
        return registry;
    }

    @Bean
    public CapabilityDispatcher capabilityDispatcher(CapabilityRegistry capabilityRegistry) {
        return new RegistryCapabilityDispatcher(capabilityRegistry);
    }

    @Bean
    public KernelRunner kernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            CompiledModel compiledModel,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer,
            EventStore eventStore,
            FlowInstanceStore flowInstanceStore,
            CorrelationOwnershipStore correlationOwnershipStore,
            CircuitBreakerStateStore circuitBreakerStateStore,
            BulkheadStore bulkheadStore,
            IdempotencyStore idempotencyStore,
            CapabilityPolicyOverrides capabilityPolicyOverrides,
            JsonCodec jsonCodec,
            SchemaValidator schemaValidator,
            MetricsSink metricsSink,
            PermissionEvaluator permissionEvaluator,
            ObjectProvider<ProcedureRunner> procedureRunnerProvider
    ) {
        KernelRunner kernelRunner = new KernelRunner(
                eventBus,
                invariantEngine,
                new CompiledModelFlowDefinitionProvider(compiledModel),
                capabilityDispatcher,
                executionTracer,
                eventStore,
                flowInstanceStore,
                correlationOwnershipStore,
                circuitBreakerStateStore,
                bulkheadStore,
                idempotencyStore,
                capabilityPolicyOverrides,
                jsonCodec,
                schemaValidator,
                metricsSink
        );
        kernelRunner.withPermissionEvaluator(permissionEvaluator);
        // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): wires the new callProcedure flow
        // step to the SAME ProcedureRunner/procedure registry PanelRuntime/AggregateRuntime already
        // use, rather than a second, separate procedure-execution mechanism -- ProcedureRunner
        // takes a name and resolves+rebuilds its own DefaultProcedureExecutor per call (see its own
        // execute()); ProcedureExecutor takes an already-resolved ProcedureDefinition, so this
        // adapter just supplies the definition's own name back to it.
        ProcedureRunner procedureRunner = procedureRunnerProvider.getIfAvailable();
        if (procedureRunner != null) {
            ProcedureExecutor procedureExecutor = (definition, input, context) ->
                    procedureRunner.execute(definition.name(), input, context);
            kernelRunner.withProcedureExecutor(procedureExecutor, procedureRunner.procedureRegistry());
        }
        if (eventStore instanceof InProcEventStore inProcEventStore) {
            inProcEventStore.registerAppendListener(kernelRunner::onEventPersisted);
        }
        return kernelRunner;
    }

    @Bean
    public GeneratedCrudRuntimeSupport generatedCrudRuntimeSupport(
            CompiledModel compiledModel,
            KernelRunner kernelRunner,
            ObjectProvider<EntityManager> entityManagerProvider,
            CapabilityDispatcher capabilityDispatcher,
            CapabilityRegistry capabilityRegistry,
            ObjectProvider<DataSource> dataSourceProvider,
            RuntimeClock runtimeClock,
            OrchestrationExecutionRegistry orchestrationExecutionRegistry,
            RuntimeInvariantEngineFactory runtimeInvariantEngineFactory,
            AuditLogStore auditLogStore,
            PermissionEvaluator permissionEvaluator,
            IdempotencyStore idempotencyStore,
            ConceptGateway conceptGateway
    ) {
        return new GeneratedCrudRuntimeSupport(
                compiledModel,
                kernelRunner,
                entityManagerProvider.getIfAvailable(),
                capabilityDispatcher,
                capabilityRegistry,
                dataSourceProvider.getIfAvailable(),
                runtimeClock,
                orchestrationExecutionRegistry,
                runtimeInvariantEngineFactory,
                auditLogStore,
                permissionEvaluator,
                idempotencyStore
        ).withConceptGateway(conceptGateway);
    }

    @Bean
    public RuntimeClock runtimeClock() {
        return new SystemRuntimeClock();
    }

    @Bean
    public OrchestrationExecutionRegistry orchestrationExecutionRegistry() {
        return new InMemoryOrchestrationExecutionRegistry();
    }
}
