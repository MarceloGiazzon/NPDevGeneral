package com.finalexec.npdev.service;

import java.util.Objects;

// verifier-token: class\s+FilesystemArtifactRealizationProvider
public final class FilesystemArtifactRealizationProvider implements RuntimePluginArtifactRealizationProvider {

    private final RuntimePluginPackagedArtifactHandlerResolver runtimePluginPackagedArtifactHandlerResolver;

    public FilesystemArtifactRealizationProvider(
            RuntimePluginPackagedArtifactHandlerResolver runtimePluginPackagedArtifactHandlerResolver
    ) {
        this.runtimePluginPackagedArtifactHandlerResolver = Objects.requireNonNull(
                runtimePluginPackagedArtifactHandlerResolver,
                "runtimePluginPackagedArtifactHandlerResolver"
        );
    }

    @Override
    public String providerId() {
        return "filesystem-artifact-provider";
    }

    @Override
    public String realizationStrategy() {
        return "runtimeRefBundle";
    }

    @Override
    public String artifactRealizationStrategy() {
        return "filesystem-artifact";
    }

    @Override
    public boolean supports(RuntimePluginPackageRealizationService.RealizationRequest request) {
        return packageBackedArtifactOfKind(request, "runtimerefbundle")
                && packagePathLooksFilesystem(request);
    }

    @Override
    public Object realizeHandler(RuntimePluginPackageRealizationService.RealizationRequest request) {
        return runtimePluginPackagedArtifactHandlerResolver.resolve(request, artifactRealizationStrategy()).handler();
    }
}
