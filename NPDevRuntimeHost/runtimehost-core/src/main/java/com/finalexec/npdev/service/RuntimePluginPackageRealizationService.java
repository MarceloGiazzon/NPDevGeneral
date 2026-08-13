package com.finalexec.npdev.service;

import com.finalexec.npdev.model.RuntimePluginPackageDescriptor;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// verifier-token: class\s+RuntimePluginPackageRealizationService|interface\s+RuntimePluginPackageRealizationService
public final class RuntimePluginPackageRealizationService {

    private final RuntimePluginPackageCatalog runtimePluginPackageCatalog;
    private final String activePluginManifestPath;
    private final List<RuntimePluginArtifactRealizationProvider> artifactRealizationProviders;
    private final Map<String, RealizationSummaryItem> realizedContributions;

    public RuntimePluginPackageRealizationService(
            RuntimePluginPackageCatalog runtimePluginPackageCatalog,
            String activePluginManifestPath,
            List<RuntimePluginArtifactRealizationProvider> artifactRealizationProviders
    ) {
        this.runtimePluginPackageCatalog = Objects.requireNonNull(runtimePluginPackageCatalog, "runtimePluginPackageCatalog");
        this.activePluginManifestPath = normalizeRequired(activePluginManifestPath, "activePluginManifestPath");
        this.artifactRealizationProviders = List.copyOf(Objects.requireNonNull(
                artifactRealizationProviders,
                "artifactRealizationProviders"
        ));
        this.realizedContributions = new ConcurrentHashMap<>();
    }

    public RealizedAdapter realize(RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");

        Optional<SelectedPackageMatch> selectedPackageArtifact = selectPackageArtifact(contribution);
        RealizationRequest request = new RealizationRequest(
                contribution,
                selectedPackageArtifact.map(SelectedPackageMatch::descriptor).orElse(null),
                selectedPackageArtifact.map(SelectedPackageMatch::artifact).orElse(null)
        );
        ArtifactRealization artifactRealization = selectArtifactRealizationProvider(request).realize(request);

        RealizationSummaryItem summaryItem = new RealizationSummaryItem(
                contribution.pluginId(),
                contribution.pluginVersion(),
                contribution.capability(),
                contribution.operation(),
                contribution.adapterId(),
                contribution.runtimeRef(),
                request.packageBacked(),
                request.packageDescriptor() == null ? null : request.packageDescriptor().packageId(),
                request.packageDescriptor() == null ? null : request.packageDescriptor().version(),
                request.packageDescriptor() == null ? null : request.packageDescriptor().packagePath(),
                request.artifact() == null ? null : request.artifact().kind(),
                request.artifact() == null ? null : request.artifact().path(),
                artifactRealization.providerId(),
                artifactRealization.artifactRealizationStrategy(),
                artifactRealization.realizationStrategy()
        );
        realizedContributions.put(summaryKey(contribution), summaryItem);
        return new RealizedAdapter(artifactRealization.handler(), summaryItem);
    }

    public Summary toSummary() {
        List<RealizationSummaryItem> items = realizedContributions.values().stream()
                .sorted(Comparator
                        .comparing(RealizationSummaryItem::capability, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(RealizationSummaryItem::operation, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(RealizationSummaryItem::adapterId, String.CASE_INSENSITIVE_ORDER))
                .toList();
        long packageBackedCount = items.stream().filter(RealizationSummaryItem::packageBacked).count();
        List<String> selectedPackageIds = items.stream()
                .map(RealizationSummaryItem::selectedPackageId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<String> selectedAdapterIds = items.stream()
                .map(RealizationSummaryItem::adapterId)
                .distinct()
                .toList();
        List<String> realizationStrategies = items.stream()
                .map(RealizationSummaryItem::realizationStrategy)
                .distinct()
                .toList();
        List<String> artifactRealizationStrategies = items.stream()
                .map(RealizationSummaryItem::artifactRealizationStrategy)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<String> artifactRealizationProviders = items.stream()
                .map(RealizationSummaryItem::artifactRealizationProvider)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return new Summary(
                activePluginManifestPath,
                items.size(),
                (int) packageBackedCount,
                selectedPackageIds,
                selectedAdapterIds,
                realizationStrategies,
                artifactRealizationStrategies,
                artifactRealizationProviders,
                items
        );
    }

    private RuntimePluginArtifactRealizationProvider selectArtifactRealizationProvider(RealizationRequest request) {
        for (RuntimePluginArtifactRealizationProvider artifactRealizationProvider : artifactRealizationProviders) {
            if (artifactRealizationProvider.supports(request)) {
                return artifactRealizationProvider;
            }
        }
        if (request.packageBacked()) {
            throw new IllegalStateException(
                    "No plugin artifact realization provider registered for artifact kind '%s' in package '%s' at '%s'"
                            .formatted(
                                    request.artifact().kind(),
                                    request.packageDescriptor().packageId(),
                                    request.packageDescriptor().packagePath()
                            )
            );
        }
        throw new IllegalStateException(
                "No plugin artifact realization provider registered for contribution '%s' adapter '%s'"
                        .formatted(request.contribution().capability(), request.contribution().adapterId())
        );
    }

    private Optional<SelectedPackageMatch> selectPackageArtifact(
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution
    ) {
        return runtimePluginPackageCatalog.entries().stream()
                .filter(entry -> entry.admissionDecision().admitted())
                .filter(entry -> entry.descriptor() != null)
                .flatMap(entry -> candidateMatches(entry.descriptor(), contribution).stream())
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator
                        .comparingInt(SelectedPackageMatch::score).reversed()
                        .thenComparing(candidate -> candidate.descriptor().packageId(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(candidate -> candidate.artifact() == null ? "" : candidate.artifact().path(), String.CASE_INSENSITIVE_ORDER))
                .findFirst();
    }

    private List<SelectedPackageMatch> candidateMatches(
            RuntimePluginPackageDescriptor descriptor,
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution
    ) {
        int capabilityScore = capabilityScore(descriptor, contribution);
        if (capabilityScore <= 0) {
            return List.of();
        }

        if (descriptor.artifacts().isEmpty()) {
            return List.of(new SelectedPackageMatch(descriptor, null, capabilityScore));
        }

        return descriptor.artifacts().stream()
                .map(artifact -> new SelectedPackageMatch(
                        descriptor,
                        artifact,
                        capabilityScore + score(descriptor, artifact, contribution)
                ))
                .toList();
    }

    private static int capabilityScore(
            RuntimePluginPackageDescriptor descriptor,
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution
    ) {
        String capability = normalize(contribution.capability());
        String operation = normalize(contribution.operation());
        String adapterId = normalize(contribution.adapterId());

        return descriptor.capabilities().stream()
                .filter(binding -> normalize(binding.capability()).equals(capability))
                .filter(binding -> normalize(binding.operation()).equals(operation))
                .mapToInt(binding -> {
                    int score = 100;
                    if (normalize(binding.adapterId()).equals(adapterId)) {
                        score += 50;
                    }
                    return score;
                })
                .max()
                .orElse(0);
    }

    private static int score(
            RuntimePluginPackageDescriptor descriptor,
            RuntimePluginPackageDescriptor.ArtifactRef artifact,
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution
    ) {
        String normalizedArtifactPath = normalize(artifact.path());
        String normalizedAdapterId = normalize(contribution.adapterId());
        String pluginBaseName = baseName(contribution.pluginId(), "-plugin");
        String packageBaseName = baseName(descriptor.packageId(), "-package");

        int score = 0;
        if (!pluginBaseName.isBlank() && pluginBaseName.equals(packageBaseName)) {
            score += 100;
        }
        if (!normalizedAdapterId.isBlank() && normalizedArtifactPath.contains(normalizedAdapterId)) {
            score += 80;
        }
        if (!pluginBaseName.isBlank() && normalizedArtifactPath.contains(pluginBaseName)) {
            score += 60;
        }
        if (normalize(descriptor.displayName()).contains(normalize(contribution.capability()))) {
            score += 10;
        }
        return score;
    }

    private static String summaryKey(RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution) {
        return String.join("|",
                normalize(contribution.capability()),
                normalize(contribution.operation()),
                normalize(contribution.adapterId()),
                normalize(contribution.pluginId())
        );
    }

    private static String baseName(String value, String suffix) {
        String normalized = normalize(value);
        if (normalized.endsWith(suffix)) {
            return normalized.substring(0, normalized.length() - suffix.length());
        }
        return normalized;
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

    public record RealizationRequest(
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            RuntimePluginPackageDescriptor packageDescriptor,
            RuntimePluginPackageDescriptor.ArtifactRef artifact
    ) {

        public RealizationRequest {
            contribution = Objects.requireNonNull(contribution, "contribution");
        }

        public boolean packageBacked() {
            return packageDescriptor != null && artifact != null;
        }
    }

    public record RealizedAdapter(
            Object handler,
            RealizationSummaryItem summary
    ) {

        public RealizedAdapter {
            summary = Objects.requireNonNull(summary, "summary");
        }
    }

    public record Summary(
            String activePluginManifestPath,
            int realizedContributionCount,
            int packageBackedContributionCount,
            List<String> selectedPackageIds,
            List<String> selectedAdapterIds,
            List<String> realizationStrategies,
            List<String> artifactRealizationStrategies,
            List<String> artifactRealizationProviders,
            List<RealizationSummaryItem> realizations
    ) {

        public Summary {
            activePluginManifestPath = normalizeRequired(activePluginManifestPath, "activePluginManifestPath");
            selectedPackageIds = List.copyOf(Objects.requireNonNull(selectedPackageIds, "selectedPackageIds"));
            selectedAdapterIds = List.copyOf(Objects.requireNonNull(selectedAdapterIds, "selectedAdapterIds"));
            realizationStrategies = List.copyOf(Objects.requireNonNull(realizationStrategies, "realizationStrategies"));
            artifactRealizationStrategies = List.copyOf(Objects.requireNonNull(
                    artifactRealizationStrategies,
                    "artifactRealizationStrategies"
            ));
            artifactRealizationProviders = List.copyOf(Objects.requireNonNull(
                    artifactRealizationProviders,
                    "artifactRealizationProviders"
            ));
            realizations = List.copyOf(Objects.requireNonNull(realizations, "realizations"));
        }
    }

    public record RealizationSummaryItem(
            String pluginId,
            String pluginVersion,
            String capability,
            String operation,
            String adapterId,
            String runtimeRef,
            boolean packageBacked,
            String selectedPackageId,
            String selectedPackageVersion,
            String selectedPackagePath,
            String artifactKind,
            String artifactPath,
            String artifactRealizationProvider,
            String artifactRealizationStrategy,
            String realizationStrategy
    ) {

        public RealizationSummaryItem {
            pluginId = normalizeRequired(pluginId, "pluginId");
            pluginVersion = normalizeRequired(pluginVersion, "pluginVersion");
            capability = normalizeRequired(capability, "capability");
            operation = normalizeRequired(operation, "operation");
            adapterId = normalizeRequired(adapterId, "adapterId");
            runtimeRef = normalizeRequired(runtimeRef, "runtimeRef");
            selectedPackageId = normalizeOptional(selectedPackageId);
            selectedPackageVersion = normalizeOptional(selectedPackageVersion);
            selectedPackagePath = normalizeOptional(selectedPackagePath);
            artifactKind = normalizeOptional(artifactKind);
            artifactPath = normalizeOptional(artifactPath);
            artifactRealizationProvider = normalizeOptional(artifactRealizationProvider);
            artifactRealizationStrategy = normalizeOptional(artifactRealizationStrategy);
            realizationStrategy = normalizeRequired(realizationStrategy, "realizationStrategy");
        }
    }

    public record ArtifactRealization(
            Object handler,
            String providerId,
            String realizationStrategy,
            String artifactRealizationStrategy
    ) {

        public ArtifactRealization {
            providerId = normalizeRequired(providerId, "providerId");
            realizationStrategy = normalizeRequired(realizationStrategy, "realizationStrategy");
            artifactRealizationStrategy = normalizeRequired(
                    artifactRealizationStrategy,
                    "artifactRealizationStrategy"
            );
        }
    }

    private record SelectedPackageMatch(
            RuntimePluginPackageDescriptor descriptor,
            RuntimePluginPackageDescriptor.ArtifactRef artifact,
            int score
    ) {

        private SelectedPackageMatch {
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    private static String normalizeOptional(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
