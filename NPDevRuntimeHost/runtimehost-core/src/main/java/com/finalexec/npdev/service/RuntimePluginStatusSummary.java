package com.finalexec.npdev.service;

import com.finalexec.npdev.model.RuntimePluginManifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

// verifier-token: class\s+RuntimePluginStatusSummary|class\s+RuntimePluginStatusController|class\s+RuntimePluginRuntimeStatus
public final class RuntimePluginStatusSummary {

    private static final List<ResourceReference> NP_AUTHORED_PLUGIN_RESOURCES = List.of(
            new ResourceReference(
                    "plugin-manifest-schema",
                    "npdev/schema/npdev-plugin-manifest-v1.schema.json",
                    "np-authored-plugin-resource"
            ),
            new ResourceReference(
                    "plugin-package-schema",
                    "npdev/schema/npdev-plugin-package-v1.schema.json",
                    "np-authored-plugin-resource"
            )
    );

    private final RuntimePluginProfileResolver.ResolvedRuntimePluginProfile runtimePluginProfile;
    private final RuntimePluginManifest runtimePluginManifest;
    private final RuntimePluginProfileDiagnostics runtimePluginProfileDiagnostics;
    private final RuntimePluginPackageCatalog runtimePluginPackageCatalog;
    private final RuntimePluginPackageRealizationService runtimePluginPackageRealizationService;
    private final RuntimePluginPackagedArtifactHandlerResolver runtimePluginPackagedArtifactHandlerResolver;
    private final RuntimePluginRealizationProviderCatalog runtimePluginRealizationProviderCatalog;
    private final PluginExecutionPolicyEvaluator pluginExecutionPolicyEvaluator;
    private final SandboxedPluginExecutionEngine sandboxedPluginExecutionEngine;

    public RuntimePluginStatusSummary(
            RuntimePluginProfileResolver.ResolvedRuntimePluginProfile runtimePluginProfile,
            RuntimePluginManifest runtimePluginManifest,
            RuntimePluginProfileDiagnostics runtimePluginProfileDiagnostics,
            RuntimePluginPackageCatalog runtimePluginPackageCatalog,
            RuntimePluginPackageRealizationService runtimePluginPackageRealizationService,
            RuntimePluginPackagedArtifactHandlerResolver runtimePluginPackagedArtifactHandlerResolver,
            RuntimePluginRealizationProviderCatalog runtimePluginRealizationProviderCatalog,
            PluginExecutionPolicyEvaluator pluginExecutionPolicyEvaluator,
            SandboxedPluginExecutionEngine sandboxedPluginExecutionEngine
    ) {
        this.runtimePluginProfile = Objects.requireNonNull(runtimePluginProfile, "runtimePluginProfile");
        this.runtimePluginManifest = Objects.requireNonNull(runtimePluginManifest, "runtimePluginManifest");
        this.runtimePluginProfileDiagnostics = Objects.requireNonNull(runtimePluginProfileDiagnostics, "runtimePluginProfileDiagnostics");
        this.runtimePluginPackageCatalog = Objects.requireNonNull(runtimePluginPackageCatalog, "runtimePluginPackageCatalog");
        this.runtimePluginPackageRealizationService = Objects.requireNonNull(runtimePluginPackageRealizationService, "runtimePluginPackageRealizationService");
        this.runtimePluginPackagedArtifactHandlerResolver = Objects.requireNonNull(
                runtimePluginPackagedArtifactHandlerResolver,
                "runtimePluginPackagedArtifactHandlerResolver"
        );
        this.runtimePluginRealizationProviderCatalog = Objects.requireNonNull(
                runtimePluginRealizationProviderCatalog,
                "runtimePluginRealizationProviderCatalog"
        );
        this.pluginExecutionPolicyEvaluator = Objects.requireNonNull(pluginExecutionPolicyEvaluator, "pluginExecutionPolicyEvaluator");
        this.sandboxedPluginExecutionEngine = Objects.requireNonNull(sandboxedPluginExecutionEngine, "sandboxedPluginExecutionEngine");
    }

    public Map<String, Object> toSummary() {
        RuntimePluginManifest.Summary manifestSummary = runtimePluginManifest.toSummary();
        RuntimePluginPackageCatalog.Summary packageSummary =
                runtimePluginPackageCatalog.toSummary(runtimePluginProfile.pluginManifestPath());
        RuntimePluginPackageRealizationService.Summary realizationSummary =
                runtimePluginPackageRealizationService.toSummary();
        List<SandboxedPluginExecutionResult.Summary> recentExecutions =
                sandboxedPluginExecutionEngine.recentExecutions().stream().limit(10).toList();
        List<RuntimePluginPackageCatalog.PackageSummary> admittedPackages = packageSummary.packages().stream()
                .filter(RuntimePluginPackageCatalog.PackageSummary::admitted)
                .toList();
        List<RuntimePluginPackageCatalog.PackageSummary> selectedPackages = admittedPackages.stream()
                .filter(summary -> packageSummary.selectedPackageIds().stream()
                        .anyMatch(selectedPackageId -> selectedPackageId.equalsIgnoreCase(summary.packageId())))
                .toList();
        List<RuntimePluginPackageCatalog.SelectedCapabilityBinding> selectedCapabilityBindings = selectedCapabilityBindings(packageSummary);
        PluginTraceabilitySummary traceability =
                buildTraceability(packageSummary, realizationSummary, recentExecutions);

        Map<String, Long> recentExecutionStatusCounts = recentExecutions.stream()
                .collect(Collectors.groupingBy(
                        summary -> normalize(summary.status()),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deploymentProfile", runtimePluginProfile.activeProfile());
        response.put("activeProfile", runtimePluginProfile.activeProfile());
        response.put("selectionMode", runtimePluginProfile.selectionMode());
        response.put("executionEnvironment", runtimePluginProfile.executionEnvironment());
        response.put("bindingsManifestPath", runtimePluginProfile.bindingsManifestPath());
        response.put("pluginManifestPath", runtimePluginProfile.pluginManifestPath());
        response.put("manifestVersion", manifestSummary.manifestVersion());
        response.put("selectedManifestPaths", Map.of(
                "bindingsManifestPath", runtimePluginProfile.bindingsManifestPath(),
                "pluginManifestPath", runtimePluginProfile.pluginManifestPath()
        ));
        response.put("discoveryMode", packageSummary.discoveryMode());
        response.put("discoveryLocation", packageSummary.discoveryLocation());
        response.put("indexResourcePath", packageSummary.indexResourcePath());
        response.put("discoveredPackages", packageSummary.packages().stream()
                .map(packageItem -> Map.of(
                        "packageId", packageItem.packageId(),
                        "version", packageItem.version(),
                        "packagePath", packageItem.packagePath()
                ))
                .toList());
        response.put("discoveredPackageIds", packageSummary.discoveredPackageIds());
        response.put("admittedPackageIds", packageSummary.admittedPackageIds());
        response.put("admittedPackages", admittedPackages);
        response.put("rejectedPackageIds", packageSummary.rejectedPackageIds());
        response.put("rejectedPackages", packageSummary.rejectedPackages());
        response.put("selectedPackageIds", packageSummary.selectedPackageIds());
        response.put("selectedPackages", selectedPackages);
        response.put("selectedCapabilities", selectedCapabilityBindings.stream()
                .map(RuntimePluginPackageCatalog.SelectedCapabilityBinding::capability)
                .toList());
        response.put("selectedCapabilityBindings", selectedCapabilityBindings);
        response.put("compatibility", Map.of(
                "runtime", packageSummary.runtimeCompatibility(),
                "activePluginManifestPath", packageSummary.activePluginManifestPath()
        ));
        response.put("trust", Map.of(
                "policy", packageSummary.trustPolicy(),
                "selectedPackageTrust", packageSummary.packages().stream()
                        .filter(RuntimePluginPackageCatalog.PackageSummary::admitted)
                        .filter(RuntimePluginPackageCatalog.PackageSummary::targetsActivePluginManifest)
                        .map(RuntimePluginPackageCatalog.PackageSummary::trust)
                        .toList()
        ));
        response.put("governance", packageSummary.governance());
        response.put("discoveryOperationalMode", Map.of(
                "demonstratedProfile", "filesystem",
                "directoryProperty", "npdev.runtime.plugin-package-directory",
                "modeActive", "filesystem-folder".equalsIgnoreCase(packageSummary.discoveryMode()),
                "activeMode", packageSummary.discoveryMode()
        ));
        response.put("admittedAdapterIds", runtimePluginProfileDiagnostics.admittedAdapterIds());
        response.put("selectedAdapterIds", runtimePluginProfileDiagnostics.selectedAdapterIds());
        response.put("unresolvedCapabilities", runtimePluginProfileDiagnostics.unresolvedCapabilities());
        response.put("missingAdapterIds", runtimePluginProfileDiagnostics.missingAdapterIds());
        response.put("realizationStrategies", realizationSummary.realizationStrategies());
        response.put("artifactRealizationStrategies", realizationSummary.artifactRealizationStrategies());
        response.put("artifactRealizationProviders", realizationSummary.artifactRealizationProviders());
        response.put("artifactRealizationBoundary", runtimePluginPackagedArtifactHandlerResolver.boundarySummary());
        response.put("realizationBoundary", runtimePluginRealizationProviderCatalog.toSummary());
        response.put("realization", realizationSummary);
        response.put("policy", pluginExecutionPolicyEvaluator.policySummary());
        response.put("recentExecutionSummary", Map.of(
                "timeoutMs", sandboxedPluginExecutionEngine.timeoutMs(),
                "executionStore", sandboxedPluginExecutionEngine.executionStoreDiagnostics(),
                "recentExecutionCount", recentExecutions.size(),
                "statusCounts", Map.copyOf(recentExecutionStatusCounts),
                "recentExecutions", recentExecutions
        ));
        response.put("traceability", traceability);
        response.put("statusAudit", buildStatusAudit(packageSummary, realizationSummary, traceability, recentExecutions));
        response.put("externalMediumDemo", buildExternalMediumDemo(packageSummary, traceability));
        response.put("resourceOwnership", buildResourceOwnership(packageSummary, realizationSummary));
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(response));
    }

    private Map<String, Object> buildStatusAudit(
            RuntimePluginPackageCatalog.Summary packageSummary,
            RuntimePluginPackageRealizationService.Summary realizationSummary,
            PluginTraceabilitySummary traceability,
            List<SandboxedPluginExecutionResult.Summary> recentExecutions
    ) {
        SandboxedPluginExecutionResult.Summary latestExecution = recentExecutions.isEmpty() ? null : recentExecutions.get(0);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("discovery", Map.of(
                "mode", packageSummary.discoveryMode(),
                "location", packageSummary.discoveryLocation()
        ));
        audit.put("governance", Map.of(
                "admittedPackages", packageSummary.packages().stream()
                        .filter(RuntimePluginPackageCatalog.PackageSummary::admitted)
                        .toList(),
                "rejectedPackages", packageSummary.rejectedPackages()
        ));
        audit.put("selection", Map.of(
                "selectedPackageIds", packageSummary.selectedPackageIds(),
                "selectedAdapterIds", runtimePluginProfileDiagnostics.selectedAdapterIds(),
                "selectedCapabilities", selectedCapabilityBindings(packageSummary).stream()
                        .map(RuntimePluginPackageCatalog.SelectedCapabilityBinding::capability)
                        .toList(),
                "selectedRealizations", traceability.adapterExecutionTrace().stream()
                        .filter(trace -> trace.selectedPackageId() != null || trace.artifactPath() != null)
                        .toList()
        ));
        audit.put("recentExecutionOutcome", latestExecution == null
                ? Map.of(
                        "status", "none",
                        "adapterId", "",
                        "selectedPackageId", "",
                        "artifactPath", ""
                )
                : Map.of(
                        "status", latestExecution.status(),
                        "adapterId", latestExecution.adapterId(),
                        "selectedPackageId", latestExecution.selectedPackageId() == null ? "" : latestExecution.selectedPackageId(),
                        "artifactPath", latestExecution.artifactPath() == null ? "" : latestExecution.artifactPath()
                ));
        audit.put("realization", Map.of(
                "strategies", realizationSummary.realizationStrategies(),
                "artifactStrategies", realizationSummary.artifactRealizationStrategies()
        ));
        return java.util.Collections.unmodifiableMap(audit);
    }

    private Map<String, Object> buildExternalMediumDemo(
            RuntimePluginPackageCatalog.Summary packageSummary,
            PluginTraceabilitySummary traceability
    ) {
        RuntimePluginPackageCatalog.PackageSummary acceptedExternalPackage = packageSummary.packages().stream()
                .filter(RuntimePluginPackageCatalog.PackageSummary::admitted)
                .filter(RuntimePluginPackageCatalog.PackageSummary::targetsActivePluginManifest)
                .findFirst()
                .orElse(null);
        RuntimePluginPackageCatalog.RejectedPackageSummary incompatibleExternalPackage = packageSummary.rejectedPackages().stream()
                .filter(rejectedPackage -> "INCOMPATIBLE_RUNTIME_API".equalsIgnoreCase(rejectedPackage.rejectionCode()))
                .findFirst()
                .orElse(null);
        RuntimePluginPackageCatalog.RejectedPackageSummary untrustedExternalPackage = packageSummary.rejectedPackages().stream()
                .filter(rejectedPackage -> "UNSUPPORTED_TRUST_MODE".equalsIgnoreCase(rejectedPackage.rejectionCode()))
                .findFirst()
                .orElse(null);

        Map<String, Object> demo = new LinkedHashMap<>();
        demo.put("pathId", "filesystem-governed-external-package-demo");
        demo.put("activationProfiles", List.of("dev", "external-acceptance", "filesystem", "external-demo"));
        demo.put("directoryProperty", "npdev.runtime.plugin-package-directory");
        demo.put("discoveryMode", packageSummary.discoveryMode());
        demo.put("discoveryLocation", packageSummary.discoveryLocation());
        demo.put("governanceEnabled", true);
        demo.put("acceptedExternalPackage", acceptedExternalPackage);
        demo.put("rejectedIncompatiblePackage", incompatibleExternalPackage);
        demo.put("rejectedUntrustedPackage", untrustedExternalPackage);
        demo.put("selectedExecutionTrace", traceability.adapterExecutionTrace().stream()
                .filter(trace -> trace.selectedPackageId() != null)
                .toList());
        return java.util.Collections.unmodifiableMap(demo);
    }

    private PluginTraceabilitySummary buildTraceability(
            RuntimePluginPackageCatalog.Summary packageSummary,
            RuntimePluginPackageRealizationService.Summary realizationSummary,
            List<SandboxedPluginExecutionResult.Summary> recentExecutions
    ) {
        Map<String, SandboxedPluginExecutionResult.Summary> latestExecutionByTraceKey = new LinkedHashMap<>();
        for (SandboxedPluginExecutionResult.Summary executionSummary : recentExecutions) {
            latestExecutionByTraceKey.putIfAbsent(traceKey(
                    executionSummary.pluginId(),
                    executionSummary.capability(),
                    executionSummary.operation(),
                    executionSummary.adapterId(),
                    executionSummary.selectedPackageId()
            ), executionSummary);
        }

        List<AdapterExecutionTrace> adapterExecutionTrace = realizationSummary.realizations().stream()
                .map(realizationItem -> toAdapterExecutionTrace(
                        realizationItem,
                        latestExecutionByTraceKey.get(traceKey(
                                realizationItem.pluginId(),
                                realizationItem.capability(),
                                realizationItem.operation(),
                                realizationItem.adapterId(),
                                realizationItem.selectedPackageId()
                        ))
                ))
                .toList();

        return new PluginTraceabilitySummary(
                packageSummary.discoveryMode(),
                packageSummary.discoveryLocation(),
                realizationSummary.activePluginManifestPath(),
                adapterExecutionTrace
        );
    }

    private static AdapterExecutionTrace toAdapterExecutionTrace(
            RuntimePluginPackageRealizationService.RealizationSummaryItem realizationItem,
            SandboxedPluginExecutionResult.Summary latestExecution
    ) {
        return new AdapterExecutionTrace(
                realizationItem.pluginId(),
                realizationItem.capability(),
                realizationItem.operation(),
                realizationItem.adapterId(),
                realizationItem.selectedPackageId(),
                realizationItem.selectedPackageVersion(),
                realizationItem.selectedPackagePath(),
                realizationItem.artifactKind(),
                realizationItem.artifactPath(),
                realizationItem.artifactRealizationProvider(),
                realizationItem.artifactRealizationStrategy(),
                realizationItem.realizationStrategy(),
                latestExecution == null ? null : latestExecution.status(),
                latestExecution == null ? null : latestExecution.correlationId(),
                latestExecution == null ? null : latestExecution.errorCode(),
                latestExecution == null ? 0L : latestExecution.executionDurationMs()
        );
    }

    private ResourceOwnershipSummary buildResourceOwnership(
            RuntimePluginPackageCatalog.Summary packageSummary,
            RuntimePluginPackageRealizationService.Summary realizationSummary
    ) {
        List<ResourceReference> projectedRuntimeResources = new java.util.ArrayList<>();
        projectedRuntimeResources.add(new ResourceReference(
                "plugin-manifest",
                runtimePluginProfile.pluginManifestPath(),
                "projected-runtime-resource"
        ));
        projectedRuntimeResources.add(new ResourceReference(
                "bindings-manifest",
                runtimePluginProfile.bindingsManifestPath(),
                "projected-runtime-resource"
        ));
        if (packageSummary.indexResourcePath() != null && !packageSummary.indexResourcePath().isBlank()) {
            projectedRuntimeResources.add(new ResourceReference(
                    "plugin-package-index",
                    packageSummary.indexResourcePath(),
                    "projected-runtime-resource"
            ));
        }
        if (!"filesystem-folder".equalsIgnoreCase(packageSummary.discoveryMode())) {
            projectedRuntimeResources.addAll(packageSummary.packages().stream()
                    .map(RuntimePluginPackageCatalog.PackageSummary::packagePath)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .map(path -> new ResourceReference("plugin-package-descriptor", path, "projected-runtime-resource"))
                    .toList());
        }

        List<ResourceReference> projectedPackageDescriptorResources =
                !"filesystem-folder".equalsIgnoreCase(packageSummary.discoveryMode())
                        ? packageSummary.packages().stream()
                                .map(RuntimePluginPackageCatalog.PackageSummary::packagePath)
                                .sorted(String.CASE_INSENSITIVE_ORDER)
                                .map(path -> new ResourceReference("projected-package-descriptor", path, "projected-package-medium"))
                                .toList()
                        : List.of();

        List<ResourceReference> filesystemPackageDescriptorResources =
                "filesystem-folder".equalsIgnoreCase(packageSummary.discoveryMode())
                        ? packageSummary.packages().stream()
                                .map(RuntimePluginPackageCatalog.PackageSummary::packagePath)
                                .sorted(String.CASE_INSENSITIVE_ORDER)
                                .map(path -> new ResourceReference("filesystem-package-descriptor", path, "filesystem-package-medium"))
                                .toList()
                        : List.of();

        List<SelectedArtifactReference> runtimeSelectedArtifactResources = realizationSummary.realizations().stream()
                .filter(realizationItem -> realizationItem.artifactPath() != null && !realizationItem.artifactPath().isBlank())
                .map(realizationItem -> new SelectedArtifactReference(
                        realizationItem.selectedPackageId(),
                        realizationItem.adapterId(),
                        realizationItem.artifactKind(),
                        realizationItem.artifactPath(),
                        realizationItem.artifactRealizationProvider(),
                        realizationItem.artifactRealizationStrategy(),
                        "runtime-selected-artifact"
                ))
                .toList();

        List<ConfigurationReference> runtimeSelectedConfigurationResources = new java.util.ArrayList<>(List.of(
                new ConfigurationReference(
                        "npdev.runtime.deployment-profile",
                        runtimePluginProfile.activeProfile(),
                        "runtime-selected-configuration"
                ),
                new ConfigurationReference(
                        "npdev.runtime.deployment.bindings-manifest",
                        runtimePluginProfile.bindingsManifestPath(),
                        "runtime-selected-configuration"
                ),
                new ConfigurationReference(
                        "npdev.runtime.deployment.plugin-manifest",
                        runtimePluginProfile.pluginManifestPath(),
                        "runtime-selected-configuration"
                ),
                new ConfigurationReference(
                        "npdev.runtime.plugin-package-location",
                        "filesystem-folder".equalsIgnoreCase(packageSummary.discoveryMode()) ? "" : packageSummary.discoveryLocation(),
                        "runtime-selected-configuration"
                ),
                new ConfigurationReference(
                        "npdev.runtime.plugin-package-directory",
                        "filesystem-folder".equalsIgnoreCase(packageSummary.discoveryMode()) ? packageSummary.discoveryLocation() : "",
                        "runtime-selected-configuration"
                ),
                new ConfigurationReference(
                        "npdev.runtime.plugin-package-discovery-mode",
                        packageSummary.discoveryMode(),
                        "runtime-selected-configuration"
                ),
                new ConfigurationReference(
                        "npdev.runtime.plugin-execution-summary-path",
                        String.valueOf(sandboxedPluginExecutionEngine.executionStoreDiagnostics().get("storePath")),
                        "runtime-selected-configuration"
                )
        ));

        List<MetadataReference> diagnosticsOnlyMetadata = List.of(
                new MetadataReference("selectedAdapterIds", "Capability-to-adapter selection snapshot"),
                new MetadataReference("traceability", "Package-to-artifact-to-adapter-to-execution trace line"),
                new MetadataReference("governance", "Compatibility and trust governance summary for discovered packages"),
                new MetadataReference("policy", "Policy summary derived from current runtime policy inputs"),
                new MetadataReference("recentExecutionSummary", "Recent sandbox execution diagnostics")
        );

        return new ResourceOwnershipSummary(
                NP_AUTHORED_PLUGIN_RESOURCES,
                List.copyOf(projectedRuntimeResources),
                projectedPackageDescriptorResources,
                filesystemPackageDescriptorResources,
                runtimeSelectedArtifactResources,
                filesystemPackageDescriptorResources,
                List.copyOf(runtimeSelectedConfigurationResources),
                diagnosticsOnlyMetadata
        );
    }

    private static String traceKey(
            String pluginId,
            String capability,
            String operation,
            String adapterId,
            String selectedPackageId
    ) {
        return String.join("|",
                normalize(pluginId),
                normalize(capability),
                normalize(operation),
                normalize(adapterId),
                normalize(selectedPackageId)
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<RuntimePluginPackageCatalog.SelectedCapabilityBinding> selectedCapabilityBindings(
            RuntimePluginPackageCatalog.Summary packageSummary
    ) {
        return packageSummary.packages().stream()
                .filter(RuntimePluginPackageCatalog.PackageSummary::admitted)
                .flatMap(summary -> summary.capabilities().stream()
                        .map(capability -> new RuntimePluginPackageCatalog.SelectedCapabilityBinding(
                                capability.capability(),
                                capability.operation(),
                                capability.adapterId(),
                                summary.packageId(),
                                summary.version()
                        )))
                .sorted(java.util.Comparator
                        .comparing(RuntimePluginPackageCatalog.SelectedCapabilityBinding::capability, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(RuntimePluginPackageCatalog.SelectedCapabilityBinding::packageId, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(RuntimePluginPackageCatalog.SelectedCapabilityBinding::adapterId, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(RuntimePluginPackageCatalog.SelectedCapabilityBinding::operation, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toMap(
                        binding -> binding.capability().toLowerCase(Locale.ROOT),
                        binding -> binding,
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values().stream()
                .toList();
    }

    public record PluginTraceabilitySummary(
            String discoveryMode,
            String discoveryLocation,
            String activePluginManifestPath,
            List<AdapterExecutionTrace> adapterExecutionTrace
    ) {

        public PluginTraceabilitySummary {
            discoveryMode = Objects.requireNonNull(discoveryMode, "discoveryMode");
            discoveryLocation = Objects.requireNonNull(discoveryLocation, "discoveryLocation");
            activePluginManifestPath = Objects.requireNonNull(activePluginManifestPath, "activePluginManifestPath");
            adapterExecutionTrace = List.copyOf(Objects.requireNonNull(adapterExecutionTrace, "adapterExecutionTrace"));
        }
    }

    public record AdapterExecutionTrace(
            String pluginId,
            String capability,
            String operation,
            String adapterId,
            String selectedPackageId,
            String selectedPackageVersion,
            String selectedPackagePath,
            String artifactKind,
            String artifactPath,
            String artifactRealizationProvider,
            String artifactRealizationStrategy,
            String realizationStrategy,
            String latestExecutionStatus,
            String latestCorrelationId,
            String latestErrorCode,
            long latestExecutionDurationMs
    ) {
    }

    public record ResourceOwnershipSummary(
            List<ResourceReference> npAuthoredResources,
            List<ResourceReference> projectedRuntimeResources,
            List<ResourceReference> projectedPackageDescriptorResources,
            List<ResourceReference> filesystemPackageDescriptorResources,
            List<SelectedArtifactReference> runtimeSelectedArtifactResources,
            List<ResourceReference> externalPluginResources,
            List<ConfigurationReference> runtimeSelectedConfigurationResources,
            List<MetadataReference> diagnosticsOnlyMetadata
    ) {

        public ResourceOwnershipSummary {
            npAuthoredResources = List.copyOf(Objects.requireNonNull(npAuthoredResources, "npAuthoredResources"));
            projectedRuntimeResources = List.copyOf(Objects.requireNonNull(projectedRuntimeResources, "projectedRuntimeResources"));
            projectedPackageDescriptorResources = List.copyOf(Objects.requireNonNull(
                    projectedPackageDescriptorResources,
                    "projectedPackageDescriptorResources"
            ));
            filesystemPackageDescriptorResources = List.copyOf(Objects.requireNonNull(
                    filesystemPackageDescriptorResources,
                    "filesystemPackageDescriptorResources"
            ));
            runtimeSelectedArtifactResources = List.copyOf(Objects.requireNonNull(
                    runtimeSelectedArtifactResources,
                    "runtimeSelectedArtifactResources"
            ));
            externalPluginResources = List.copyOf(Objects.requireNonNull(externalPluginResources, "externalPluginResources"));
            runtimeSelectedConfigurationResources = List.copyOf(Objects.requireNonNull(
                    runtimeSelectedConfigurationResources,
                    "runtimeSelectedConfigurationResources"
            ));
            diagnosticsOnlyMetadata = List.copyOf(Objects.requireNonNull(diagnosticsOnlyMetadata, "diagnosticsOnlyMetadata"));
        }
    }

    public record ResourceReference(
            String resourceKind,
            String path,
            String ownership
    ) {

        public ResourceReference {
            resourceKind = Objects.requireNonNull(resourceKind, "resourceKind");
            path = Objects.requireNonNull(path, "path");
            ownership = Objects.requireNonNull(ownership, "ownership");
        }
    }

    public record SelectedArtifactReference(
            String selectedPackageId,
            String adapterId,
            String artifactKind,
            String artifactPath,
            String artifactRealizationProvider,
            String artifactRealizationStrategy,
            String ownership
    ) {

        public SelectedArtifactReference {
            selectedPackageId = selectedPackageId == null || selectedPackageId.isBlank() ? null : selectedPackageId.trim();
            adapterId = Objects.requireNonNull(adapterId, "adapterId");
            artifactKind = Objects.requireNonNull(artifactKind, "artifactKind");
            artifactPath = Objects.requireNonNull(artifactPath, "artifactPath");
            artifactRealizationProvider = artifactRealizationProvider == null || artifactRealizationProvider.isBlank()
                    ? null
                    : artifactRealizationProvider.trim();
            artifactRealizationStrategy = artifactRealizationStrategy == null || artifactRealizationStrategy.isBlank()
                    ? null
                    : artifactRealizationStrategy.trim();
            ownership = Objects.requireNonNull(ownership, "ownership");
        }
    }

    public record ConfigurationReference(
            String property,
            String selectedValue,
            String ownership
    ) {

        public ConfigurationReference {
            property = Objects.requireNonNull(property, "property");
            selectedValue = Objects.requireNonNull(selectedValue, "selectedValue");
            ownership = Objects.requireNonNull(ownership, "ownership");
        }
    }

    public record MetadataReference(
            String key,
            String description
    ) {

        public MetadataReference {
            key = Objects.requireNonNull(key, "key");
            description = Objects.requireNonNull(description, "description");
        }
    }
}
