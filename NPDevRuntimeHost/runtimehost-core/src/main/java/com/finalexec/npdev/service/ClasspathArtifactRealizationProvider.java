package com.finalexec.npdev.service;

import java.util.Objects;

public final class ClasspathArtifactRealizationProvider implements RuntimePluginArtifactRealizationProvider {

    private final RuntimePluginPackagedArtifactHandlerResolver runtimePluginPackagedArtifactHandlerResolver;

    public ClasspathArtifactRealizationProvider(
            RuntimePluginPackagedArtifactHandlerResolver runtimePluginPackagedArtifactHandlerResolver
    ) {
        this.runtimePluginPackagedArtifactHandlerResolver = Objects.requireNonNull(
                runtimePluginPackagedArtifactHandlerResolver,
                "runtimePluginPackagedArtifactHandlerResolver"
        );
    }

    @Override
    public String providerId() {
        return "classpath-artifact-provider";
    }

    @Override
    public String realizationStrategy() {
        return "runtimeRefBundle";
    }

    @Override
    public String artifactRealizationStrategy() {
        return "classpath-artifact";
    }

    @Override
    public boolean supports(RuntimePluginPackageRealizationService.RealizationRequest request) {
        return packageBackedArtifactOfKind(request, "runtimerefbundle")
                && !packagePathLooksFilesystem(request);
    }

    @Override
    public Object realizeHandler(RuntimePluginPackageRealizationService.RealizationRequest request) {
        return runtimePluginPackagedArtifactHandlerResolver.resolve(request, artifactRealizationStrategy()).handler();
    }
}
