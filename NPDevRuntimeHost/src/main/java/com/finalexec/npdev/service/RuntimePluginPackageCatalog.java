package com.finalexec.npdev.service;

import com.finalexec.npdev.model.RuntimePluginPackageDescriptor;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

// verifier-token: rejectionReason|rejectionCode|rejectedPackages
public final class RuntimePluginPackageCatalog {

    private final RuntimePluginPackageDiscoveryService.DiscoveryResult discoveryResult;
    private final RuntimePluginPackageCompatibilityEvaluator.RuntimeCompatibilitySummary runtimeCompatibility;
    private final RuntimePluginPackageTrustEvaluator.TrustPolicySummary trustPolicy;
    private final List<PackageCatalogEntry> entries;

    public RuntimePluginPackageCatalog(
            RuntimePluginPackageDiscoveryService.DiscoveryResult discoveryResult,
            RuntimePluginPackageCompatibilityEvaluator.RuntimeCompatibilitySummary runtimeCompatibility,
            RuntimePluginPackageTrustEvaluator.TrustPolicySummary trustPolicy,
            List<PackageCatalogEntry> entries
    ) {
        this.discoveryResult = Objects.requireNonNull(discoveryResult, "discoveryResult");
        this.runtimeCompatibility = Objects.requireNonNull(runtimeCompatibility, "runtimeCompatibility");
        this.trustPolicy = Objects.requireNonNull(trustPolicy, "trustPolicy");
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    public Summary toSummary(String activePluginManifestPath) {
        List<PackageSummary> packageSummaries = entries.stream()
                .map(entry -> entry.toSummary(activePluginManifestPath))
                .sorted(Comparator.comparing(PackageSummary::packageId, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<String> discoveredPackageIds = packageSummaries.stream().map(PackageSummary::packageId).toList();
        List<String> admittedPackageIds = packageSummaries.stream()
                .filter(PackageSummary::admitted)
                .map(PackageSummary::packageId)
                .toList();
        List<String> rejectedPackageIds = packageSummaries.stream()
                .filter(summary -> !summary.admitted())
                .map(PackageSummary::packageId)
                .toList();
        List<String> selectedPackageIds = packageSummaries.stream()
                .filter(PackageSummary::admitted)
                .flatMap(summary -> summary.capabilities().stream()
                        .map(capability -> new SelectedCapabilityBinding(
                                capability.capability(),
                                capability.operation(),
                                capability.adapterId(),
                                summary.packageId(),
                                summary.version()
                        )))
                .sorted(Comparator
                        .comparing(SelectedCapabilityBinding::capability, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SelectedCapabilityBinding::packageId, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SelectedCapabilityBinding::adapterId, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SelectedCapabilityBinding::operation, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toMap(
                        binding -> binding.capability().toLowerCase(Locale.ROOT),
                        binding -> binding,
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values().stream()
                .map(SelectedCapabilityBinding::packageId)
                .distinct()
                .toList();
        List<RejectedPackageSummary> rejectedPackages = packageSummaries.stream()
                .filter(summary -> !summary.admitted())
                .map(summary -> new RejectedPackageSummary(
                        summary.packageId(),
                        summary.packagePath(),
                        summary.rejectionCategory(),
                        summary.rejectionCode(),
                        summary.rejectionMessage(),
                        summary.rejectionMessage(),
                        summary.trust(),
                        summary.compatibility(),
                        summary.signature(),
                        summary.provenance(),
                        summary.compatibilityEvaluation(),
                        summary.trustEvaluation()
                ))
                .toList();
        return new Summary(
                discoveryResult.discoveryMode(),
                discoveryResult.discoveryLocation(),
                discoveryResult.indexResourcePath(),
                activePluginManifestPath,
                runtimeCompatibility,
                trustPolicy,
                discoveredPackageIds,
                admittedPackageIds,
                rejectedPackageIds,
                selectedPackageIds,
                rejectedPackages,
                new GovernanceSummary(runtimeCompatibility, trustPolicy, admittedPackageIds.size(), rejectedPackageIds.size()),
                packageSummaries
        );
    }

    public List<PackageCatalogEntry> entries() {
        return entries;
    }

    public record PackageCatalogEntry(
            RuntimePluginPackageDiscoveryService.DiscoveredPackageCandidate candidate,
            RuntimePluginPackageDescriptor descriptor,
            RuntimePluginPackageAdmissionEvaluator.AdmissionDecision admissionDecision
    ) {

        public PackageCatalogEntry {
            candidate = Objects.requireNonNull(candidate, "candidate");
            admissionDecision = Objects.requireNonNull(admissionDecision, "admissionDecision");
        }

        public PackageSummary toSummary(String activePluginManifestPath) {
            RuntimePluginPackageDescriptor effectiveDescriptor = descriptor;
            String packageId = effectiveDescriptor == null ? candidate.derivedPackageId() : effectiveDescriptor.packageId();
            String packagePath = effectiveDescriptor == null ? candidate.resourcePath() : effectiveDescriptor.packagePath();
            String packageFormatVersion = effectiveDescriptor == null ? "(unresolved)" : effectiveDescriptor.packageFormatVersion();
            String displayName = effectiveDescriptor == null ? packageId : effectiveDescriptor.displayName();
            String version = effectiveDescriptor == null ? "(unresolved)" : effectiveDescriptor.version();
            String description = effectiveDescriptor == null ? "Descriptor could not be loaded" : effectiveDescriptor.description();
            String provider = effectiveDescriptor == null ? "(unresolved)" : effectiveDescriptor.provider();
            RuntimePluginPackageDescriptor.CompatibilitySummary compatibility =
                    effectiveDescriptor == null
                            ? new RuntimePluginPackageDescriptor.CompatibilitySummary("(unresolved)", "(unresolved)", null, null, null)
                            : effectiveDescriptor.compatibility().toSummary();
            RuntimePluginPackageDescriptor.TrustSummary trust =
                    effectiveDescriptor == null
                            ? new RuntimePluginPackageDescriptor.TrustSummary("(unresolved)", "(unresolved)", "(unresolved)")
                            : effectiveDescriptor.trust().toSummary();
            RuntimePluginPackageDescriptor.SignatureSummary signature =
                    effectiveDescriptor == null || effectiveDescriptor.signature() == null
                            ? null
                            : effectiveDescriptor.signature().toSummary();
            RuntimePluginPackageDescriptor.ProvenanceSummary provenance =
                    effectiveDescriptor == null || effectiveDescriptor.provenance() == null
                            ? null
                            : effectiveDescriptor.provenance().toSummary();
            List<RuntimePluginPackageDescriptor.ArtifactSummary> artifacts =
                    effectiveDescriptor == null
                            ? List.of()
                            : effectiveDescriptor.artifacts().stream()
                                    .map(RuntimePluginPackageDescriptor.ArtifactRef::toSummary)
                                    .sorted(Comparator.comparing(RuntimePluginPackageDescriptor.ArtifactSummary::path, String.CASE_INSENSITIVE_ORDER))
                                    .toList();
            String pluginManifestPath =
                    effectiveDescriptor == null ? "(unresolved)" : effectiveDescriptor.pluginManifest().path();
            boolean targetsActivePluginManifest =
                    effectiveDescriptor != null && effectiveDescriptor.pluginManifest().matches(activePluginManifestPath);

            return new PackageSummary(
                    packagePath,
                    packageFormatVersion,
                    packageId,
                    displayName,
                    version,
                    description,
                    provider,
                    compatibility,
                    trust,
                    signature,
                    provenance,
                    artifacts,
                    pluginManifestPath,
                    targetsActivePluginManifest,
                    admissionDecision.compatibilityEvaluation(),
                    admissionDecision.trustEvaluation(),
                    admissionDecision.admitted(),
                    admissionDecision.status(),
                    admissionDecision.rejectionCategory(),
                    admissionDecision.reasonCode(),
                    admissionDecision.reasonMessage(),
                    admissionDecision.rejectionReason() == null
                            ? null
                            : new RejectionReason(
                                    admissionDecision.rejectionCategory(),
                                    admissionDecision.reasonCode(),
                                    admissionDecision.rejectionReason()
                            ),
                    effectiveDescriptor == null ? List.of() : effectiveDescriptor.toSummary(activePluginManifestPath).capabilities()
            );
        }
    }

    public record Summary(
            String discoveryMode,
            String discoveryLocation,
            String indexResourcePath,
            String activePluginManifestPath,
            RuntimePluginPackageCompatibilityEvaluator.RuntimeCompatibilitySummary runtimeCompatibility,
            RuntimePluginPackageTrustEvaluator.TrustPolicySummary trustPolicy,
            List<String> discoveredPackageIds,
            List<String> admittedPackageIds,
            List<String> rejectedPackageIds,
            List<String> selectedPackageIds,
            List<RejectedPackageSummary> rejectedPackages,
            GovernanceSummary governance,
            List<PackageSummary> packages
    ) {

        public Summary {
            discoveryMode = Objects.requireNonNull(discoveryMode, "discoveryMode");
            discoveryLocation = Objects.requireNonNull(discoveryLocation, "discoveryLocation");
            indexResourcePath = indexResourcePath == null || indexResourcePath.isBlank()
                    ? null
                    : indexResourcePath.trim();
            activePluginManifestPath = Objects.requireNonNull(activePluginManifestPath, "activePluginManifestPath");
            runtimeCompatibility = Objects.requireNonNull(runtimeCompatibility, "runtimeCompatibility");
            trustPolicy = Objects.requireNonNull(trustPolicy, "trustPolicy");
            discoveredPackageIds = List.copyOf(Objects.requireNonNull(discoveredPackageIds, "discoveredPackageIds"));
            admittedPackageIds = List.copyOf(Objects.requireNonNull(admittedPackageIds, "admittedPackageIds"));
            rejectedPackageIds = List.copyOf(Objects.requireNonNull(rejectedPackageIds, "rejectedPackageIds"));
            selectedPackageIds = List.copyOf(Objects.requireNonNull(selectedPackageIds, "selectedPackageIds"));
            rejectedPackages = List.copyOf(Objects.requireNonNull(rejectedPackages, "rejectedPackages"));
            governance = Objects.requireNonNull(governance, "governance");
            packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
        }
    }

    public record PackageSummary(
            String packagePath,
            String packageFormatVersion,
            String packageId,
            String displayName,
            String version,
            String description,
            String provider,
            RuntimePluginPackageDescriptor.CompatibilitySummary compatibility,
            RuntimePluginPackageDescriptor.TrustSummary trust,
            RuntimePluginPackageDescriptor.SignatureSummary signature,
            RuntimePluginPackageDescriptor.ProvenanceSummary provenance,
            List<RuntimePluginPackageDescriptor.ArtifactSummary> artifacts,
            String pluginManifestPath,
            boolean targetsActivePluginManifest,
            RuntimePluginPackageCompatibilityEvaluator.CompatibilityEvaluation compatibilityEvaluation,
            RuntimePluginPackageTrustEvaluator.TrustEvaluation trustEvaluation,
            boolean admitted,
            String admissionStatus,
            String rejectionCategory,
            String rejectionCode,
            String rejectionMessage,
            RejectionReason rejectionReason,
            List<RuntimePluginPackageDescriptor.CapabilityBindingSummary> capabilities
    ) {

        public PackageSummary {
            packagePath = Objects.requireNonNull(packagePath, "packagePath");
            packageFormatVersion = Objects.requireNonNull(packageFormatVersion, "packageFormatVersion");
            packageId = Objects.requireNonNull(packageId, "packageId");
            displayName = Objects.requireNonNull(displayName, "displayName");
            version = Objects.requireNonNull(version, "version");
            description = Objects.requireNonNull(description, "description");
            provider = Objects.requireNonNull(provider, "provider");
            compatibility = Objects.requireNonNull(compatibility, "compatibility");
            trust = Objects.requireNonNull(trust, "trust");
            artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
            pluginManifestPath = Objects.requireNonNull(pluginManifestPath, "pluginManifestPath");
            admissionStatus = Objects.requireNonNull(admissionStatus, "admissionStatus");
            rejectionCategory = rejectionCategory == null || rejectionCategory.isBlank() ? null : rejectionCategory.trim();
            rejectionCode = rejectionCode == null || rejectionCode.isBlank() ? null : rejectionCode.trim();
            rejectionMessage = rejectionMessage == null || rejectionMessage.isBlank() ? null : rejectionMessage.trim();
            capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        }
    }

    public record SelectedCapabilityBinding(
            String capability,
            String operation,
            String adapterId,
            String packageId,
            String packageVersion
    ) {

        public SelectedCapabilityBinding {
            capability = Objects.requireNonNull(capability, "capability");
            operation = Objects.requireNonNull(operation, "operation");
            adapterId = Objects.requireNonNull(adapterId, "adapterId");
            packageId = Objects.requireNonNull(packageId, "packageId");
            packageVersion = Objects.requireNonNull(packageVersion, "packageVersion");
        }
    }

    public record GovernanceSummary(
            RuntimePluginPackageCompatibilityEvaluator.RuntimeCompatibilitySummary runtimeCompatibility,
            RuntimePluginPackageTrustEvaluator.TrustPolicySummary trustPolicy,
            int admittedPackageCount,
            int rejectedPackageCount
    ) {

        public GovernanceSummary {
            runtimeCompatibility = Objects.requireNonNull(runtimeCompatibility, "runtimeCompatibility");
            trustPolicy = Objects.requireNonNull(trustPolicy, "trustPolicy");
        }
    }

    public record RejectedPackageSummary(
            String packageId,
            String packagePath,
            String rejectionCategory,
            String rejectionCode,
            String reason,
            String rejectionMessage,
            RuntimePluginPackageDescriptor.TrustSummary trust,
            RuntimePluginPackageDescriptor.CompatibilitySummary compatibility,
            RuntimePluginPackageDescriptor.SignatureSummary signature,
            RuntimePluginPackageDescriptor.ProvenanceSummary provenance,
            RuntimePluginPackageCompatibilityEvaluator.CompatibilityEvaluation compatibilityEvaluation,
            RuntimePluginPackageTrustEvaluator.TrustEvaluation trustEvaluation
    ) {

        public RejectedPackageSummary {
            packageId = Objects.requireNonNull(packageId, "packageId");
            packagePath = Objects.requireNonNull(packagePath, "packagePath");
            rejectionCategory = Objects.requireNonNull(rejectionCategory, "rejectionCategory");
            rejectionCode = Objects.requireNonNull(rejectionCode, "rejectionCode");
            reason = Objects.requireNonNull(reason, "reason");
            rejectionMessage = Objects.requireNonNull(rejectionMessage, "rejectionMessage");
            trust = Objects.requireNonNull(trust, "trust");
            compatibility = Objects.requireNonNull(compatibility, "compatibility");
        }
    }

    public record RejectionReason(
            String category,
            String code,
            String message
    ) {

        public RejectionReason {
            category = Objects.requireNonNull(category, "category");
            code = Objects.requireNonNull(code, "code");
            message = Objects.requireNonNull(message, "message");
        }
    }
}
