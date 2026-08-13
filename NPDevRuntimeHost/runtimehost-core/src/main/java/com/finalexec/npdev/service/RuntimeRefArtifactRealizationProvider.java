package com.finalexec.npdev.service;

import java.util.Objects;

public final class RuntimeRefArtifactRealizationProvider implements RuntimePluginArtifactRealizationProvider {

    private final RuntimePluginRuntimeRefResolver runtimePluginRuntimeRefResolver;

    public RuntimeRefArtifactRealizationProvider(
            RuntimePluginRuntimeRefResolver runtimePluginRuntimeRefResolver
    ) {
        this.runtimePluginRuntimeRefResolver = Objects.requireNonNull(
                runtimePluginRuntimeRefResolver,
                "runtimePluginRuntimeRefResolver"
        );
    }

    @Override
    public String providerId() {
        return "runtime-ref-artifact-provider";
    }

    @Override
    public String realizationStrategy() {
        return "runtimeRefDirect";
    }

    @Override
    public String artifactRealizationStrategy() {
        return "runtime-ref-direct";
    }

    @Override
    public boolean supports(RuntimePluginPackageRealizationService.RealizationRequest request) {
        return !request.packageBacked();
    }

    @Override
    public Object realizeHandler(RuntimePluginPackageRealizationService.RealizationRequest request) {
        return runtimePluginRuntimeRefResolver.resolve(request.contribution());
    }
}
