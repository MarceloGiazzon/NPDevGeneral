package com.finalexec.npdev.service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class RuntimePluginPackagedArtifactHandlerResolver {

    private final RuntimePluginRuntimeRefResolver runtimePluginRuntimeRefResolver;

    public RuntimePluginPackagedArtifactHandlerResolver(
            RuntimePluginRuntimeRefResolver runtimePluginRuntimeRefResolver
    ) {
        this.runtimePluginRuntimeRefResolver = Objects.requireNonNull(
                runtimePluginRuntimeRefResolver,
                "runtimePluginRuntimeRefResolver"
        );
    }

    public PackagedArtifactResolution resolve(
            RuntimePluginPackageRealizationService.RealizationRequest request,
            String artifactMedium
    ) {
        Objects.requireNonNull(request, "request");
        String normalizedArtifactMedium = normalizeRequired(artifactMedium, "artifactMedium");
        if (!request.packageBacked() || request.packageDescriptor() == null || request.artifact() == null) {
            throw new IllegalStateException("Packaged artifact resolution requires a selected package descriptor and artifact");
        }
        if (!"runtimerefbundle".equals(normalize(request.artifact().kind()))) {
            throw new IllegalStateException(
                    "Unsupported packaged artifact kind '%s' for package '%s'"
                            .formatted(request.artifact().kind(), request.packageDescriptor().packageId())
            );
        }

        Object handler = runtimePluginRuntimeRefResolver.resolve(request.contribution());
        return new PackagedArtifactResolution(
                handler,
                request.packageDescriptor().packageId(),
                request.packageDescriptor().packagePath(),
                request.artifact().kind(),
                request.artifact().path(),
                request.contribution().runtimeRef(),
                normalizedArtifactMedium
        );
    }

    public Summary boundarySummary() {
        return new Summary(
                "packaged-artifact-runtime-ref-bridge",
                List.of("runtimerefbundle"),
                "RuntimePluginRuntimeRefResolver"
        );
    }

    public record PackagedArtifactResolution(
            Object handler,
            String packageId,
            String packagePath,
            String artifactKind,
            String artifactPath,
            String resolvedRuntimeRef,
            String artifactMedium
    ) {

        public PackagedArtifactResolution {
            packageId = normalizeRequired(packageId, "packageId");
            packagePath = normalizeRequired(packagePath, "packagePath");
            artifactKind = normalizeRequired(artifactKind, "artifactKind").toLowerCase(Locale.ROOT);
            artifactPath = normalizeRequired(artifactPath, "artifactPath");
            resolvedRuntimeRef = normalizeRequired(resolvedRuntimeRef, "resolvedRuntimeRef");
            artifactMedium = normalizeRequired(artifactMedium, "artifactMedium");
        }
    }

    public record Summary(
            String boundaryKind,
            List<String> supportedArtifactKinds,
            String delegatedResolver
    ) {

        public Summary {
            boundaryKind = normalizeRequired(boundaryKind, "boundaryKind");
            supportedArtifactKinds = List.copyOf(Objects.requireNonNull(
                    supportedArtifactKinds,
                    "supportedArtifactKinds"
            ));
            delegatedResolver = normalizeRequired(delegatedResolver, "delegatedResolver");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeRequired(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + " must be non-blank");
        }
        return normalized;
    }
}
