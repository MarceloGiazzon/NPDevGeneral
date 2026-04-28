package com.finalexec.npdev.service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

// verifier-token: ArtifactRealizationProvider|ClasspathArtifactRealizationProvider|FilesystemArtifactRealizationProvider|RuntimeRefArtifactRealizationProvider|realizationStrategy
// verifier-token: class\s+RuntimePluginArtifactRealizationProvider|interface\s+RuntimePluginArtifactRealizationProvider
public interface RuntimePluginArtifactRealizationProvider {

    String providerId();

    String realizationStrategy();

    String artifactRealizationStrategy();

    boolean supports(RuntimePluginPackageRealizationService.RealizationRequest request);

    Object realizeHandler(RuntimePluginPackageRealizationService.RealizationRequest request);

    default RuntimePluginPackageRealizationService.ArtifactRealization realize(
            RuntimePluginPackageRealizationService.RealizationRequest request
    ) {
        return new RuntimePluginPackageRealizationService.ArtifactRealization(
                realizeHandler(request),
                providerId(),
                realizationStrategy(),
                artifactRealizationStrategy()
        );
    }

    default boolean packageBackedArtifactOfKind(
            RuntimePluginPackageRealizationService.RealizationRequest request,
            String expectedArtifactKind
    ) {
        return request.packageBacked()
                && request.packageDescriptor() != null
                && request.artifact() != null
                && expectedArtifactKind.equalsIgnoreCase(request.artifact().kind());
    }

    default boolean packagePathLooksFilesystem(RuntimePluginPackageRealizationService.RealizationRequest request) {
        if (request.packageDescriptor() == null) {
            return false;
        }
        return looksLikeFilesystemPath(request.packageDescriptor().packagePath());
    }

    private static boolean looksLikeFilesystemPath(String pathValue) {
        String normalized = pathValue == null ? "" : pathValue.trim();
        if (normalized.isBlank() || normalized.startsWith("classpath:")) {
            return false;
        }
        if (normalized.startsWith("file:") || normalized.startsWith("\\\\")) {
            return true;
        }
        if (normalized.contains(":\\") || normalized.contains(":/")) {
            return true;
        }
        try {
            return Path.of(normalized).isAbsolute();
        } catch (InvalidPathException exception) {
            return false;
        }
    }
}
