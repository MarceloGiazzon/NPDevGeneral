package com.finalexec.npdev.service;

import java.util.Objects;

public final class JavaSourceArtifactRealizationProvider implements RuntimePluginArtifactRealizationProvider {

    private final RuntimePluginRuntimeRefResolver runtimePluginRuntimeRefResolver;

    public JavaSourceArtifactRealizationProvider(RuntimePluginRuntimeRefResolver runtimePluginRuntimeRefResolver) {
        this.runtimePluginRuntimeRefResolver = Objects.requireNonNull(
                runtimePluginRuntimeRefResolver,
                "runtimePluginRuntimeRefResolver"
        );
    }

    @Override
    public String providerId() {
        return "artifact-local-java-source-provider";
    }

    @Override
    public String realizationStrategy() {
        return "runtimeRefJavaSource";
    }

    @Override
    public String artifactRealizationStrategy() {
        return "artifact-local-java-source";
    }

    @Override
    public boolean supports(RuntimePluginPackageRealizationService.RealizationRequest request) {
        return packageBackedArtifactOfKind(request, "javaSource");
    }

    @Override
    public Object realizeHandler(RuntimePluginPackageRealizationService.RealizationRequest request) {
        return runtimePluginRuntimeRefResolver.resolve(request.contribution());
    }
}
