package com.finalexec.npdev.service;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.util.Map;
import java.util.Objects;

public final class SandboxedCapabilityAdapter implements CapabilityAdapter {

    private final RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution;
    private final RuntimePluginPackageRealizationService.RealizationSummaryItem realizationSummary;
    private final Object handler;
    private final SandboxedPluginExecutionEngine sandboxedPluginExecutionEngine;

    public SandboxedCapabilityAdapter(
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            RuntimePluginPackageRealizationService.RealizationSummaryItem realizationSummary,
            Object handler,
            SandboxedPluginExecutionEngine sandboxedPluginExecutionEngine
    ) {
        this.contribution = Objects.requireNonNull(contribution, "contribution");
        this.realizationSummary = Objects.requireNonNull(realizationSummary, "realizationSummary");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.sandboxedPluginExecutionEngine = Objects.requireNonNull(sandboxedPluginExecutionEngine, "sandboxedPluginExecutionEngine");
    }

    @Override
    public String adapterId() {
        return contribution.adapterId();
    }

    @Override
    public String capability() {
        return contribution.capability();
    }

    @Override
    public String capabilityType() {
        return contribution.capability();
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        return sandboxedPluginExecutionEngine.execute(
                contribution,
                realizationSummary,
                call,
                contextState,
                handler
        ).toCapabilityResult();
    }
}
