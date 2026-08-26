package com.finalexec.config;

import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.RuntimePluginPackageRealizationService;
import com.finalexec.npdev.service.SandboxedCapabilityAdapter;
import com.finalexec.npdev.service.TimeBoundedPluginExecutionEngine;
import com.npdev.dsl.v1.compiled.CompiledCapability;
import com.npdev.dsl.v1.compiled.CompiledCapabilityBinding;
import com.npdev.kernel.capabilities.CapabilityBindingDescriptor;
import com.npdev.kernel.capabilities.CapabilityBindingResolver;
import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.Objects;

public final class CapabilityAdapterResolver {

    private final CapabilityBindingResolver capabilityBindingResolver;
    private final Environment environment;
    private final String runtimeEnvironment;
    private final RuntimePluginAdapterRegistry runtimePluginAdapterRegistry;
    private final RuntimePluginPackageRealizationService runtimePluginPackageRealizationService;
    private final TimeBoundedPluginExecutionEngine timeBoundedPluginExecutionEngine;

    public CapabilityAdapterResolver(
            CapabilityBindingResolver capabilityBindingResolver,
            Environment environment,
            String runtimeEnvironment,
            RuntimePluginAdapterRegistry runtimePluginAdapterRegistry,
            RuntimePluginPackageRealizationService runtimePluginPackageRealizationService,
            TimeBoundedPluginExecutionEngine timeBoundedPluginExecutionEngine
    ) {
        this.capabilityBindingResolver = Objects.requireNonNull(capabilityBindingResolver, "capabilityBindingResolver");
        this.environment = environment;
        this.runtimeEnvironment = runtimeEnvironment;
        this.runtimePluginAdapterRegistry = Objects.requireNonNull(runtimePluginAdapterRegistry, "runtimePluginAdapterRegistry");
        this.runtimePluginPackageRealizationService = Objects.requireNonNull(
                runtimePluginPackageRealizationService,
                "runtimePluginPackageRealizationService"
        );
        this.timeBoundedPluginExecutionEngine = Objects.requireNonNull(timeBoundedPluginExecutionEngine, "timeBoundedPluginExecutionEngine");
    }

    public ResolvedCapabilityAdapter resolve(CompiledCapability capability, CompiledCapabilityBinding binding) {
        return resolve(capability, "", binding);
    }

    public ResolvedCapabilityAdapter resolve(
            CompiledCapability capability,
            String operation,
            CompiledCapabilityBinding binding
    ) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(binding, "binding");

        CapabilityBindingDescriptor resolvedBinding = capabilityBindingResolver.resolve(
                capability.getName(),
                capability.getType(),
                "",
                activeEnvironment()
        ).orElse(null);

        String explicitAdapterId = normalize(binding.getAdapter());
        String adapterId = explicitAdapterId.isBlank()
                ? (resolvedBinding == null || resolvedBinding.adapterId().isBlank() ? binding.getAdapter() : resolvedBinding.adapterId())
                : explicitAdapterId;

        String adapterClass = null;
        if (resolvedBinding != null) {
            String resolvedAdapterId = normalize(resolvedBinding.adapterId());
            if (explicitAdapterId.isBlank() || explicitAdapterId.equals(resolvedAdapterId)) {
                adapterClass = resolvedBinding.adapterClass();
            }
        }

        RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution =
                runtimePluginAdapterRegistry.requireContribution(capability.getName(), operation, adapterId);
        RuntimePluginPackageRealizationService.RealizedAdapter realizedAdapter =
                runtimePluginPackageRealizationService.realize(contribution);
        Object sandboxedHandler = new SandboxedCapabilityAdapter(
                contribution,
                realizedAdapter.summary(),
                realizedAdapter.handler(),
                timeBoundedPluginExecutionEngine
        );
        return new ResolvedCapabilityAdapter(adapterId, adapterClass, sandboxedHandler);
    }

    private String activeEnvironment() {
        if (runtimeEnvironment != null && !runtimeEnvironment.isBlank()) {
            return runtimeEnvironment.trim().toLowerCase(Locale.ROOT);
        }
        if (environment == null) {
            return "default";
        }
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles == null || activeProfiles.length == 0) {
            return "default";
        }
        String[] preferredOrder = {"dev", "test", "stage", "prod", "default"};
        for (String preferred : preferredOrder) {
            for (String profile : activeProfiles) {
                if (profile != null && preferred.equalsIgnoreCase(profile.trim())) {
                    return preferred;
                }
            }
        }
        for (String profile : activeProfiles) {
            if (profile == null) {
                continue;
            }
            String normalized = profile.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isBlank() && !"postgres".equals(normalized)) {
                return normalized;
            }
        }
        return "default";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record ResolvedCapabilityAdapter(
            String adapterId,
            String adapterClass,
            Object handler
    ) {
    }
}
