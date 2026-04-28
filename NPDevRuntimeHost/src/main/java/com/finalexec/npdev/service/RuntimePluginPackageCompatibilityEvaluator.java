package com.finalexec.npdev.service;

import com.finalexec.npdev.model.RuntimePluginPackageDescriptor;

import java.util.Objects;

// verifier-token: class\s+RuntimePluginPackageCompatibilityEvaluator|interface\s+RuntimePluginPackageCompatibilityEvaluator
public final class RuntimePluginPackageCompatibilityEvaluator {

    private final String runtimeApiVersion;
    private final String bootstrapVersion;
    private final String runtimeNpdevVersion;
    private final String supportedPackageFormatVersion;
    private final String activePluginManifestPath;

    public RuntimePluginPackageCompatibilityEvaluator(
            String runtimeApiVersion,
            String bootstrapVersion,
            String supportedPackageFormatVersion,
            String activePluginManifestPath
    ) {
        this(runtimeApiVersion, bootstrapVersion, runtimeApiVersion, supportedPackageFormatVersion, activePluginManifestPath);
    }

    public RuntimePluginPackageCompatibilityEvaluator(
            String runtimeApiVersion,
            String bootstrapVersion,
            String runtimeNpdevVersion,
            String supportedPackageFormatVersion,
            String activePluginManifestPath
    ) {
        this.runtimeApiVersion = normalizeRequired(runtimeApiVersion, "runtimeApiVersion");
        this.bootstrapVersion = normalizeRequired(bootstrapVersion, "bootstrapVersion");
        this.runtimeNpdevVersion = normalizeRequired(runtimeNpdevVersion, "runtimeNpdevVersion");
        this.supportedPackageFormatVersion = normalizeRequired(supportedPackageFormatVersion, "supportedPackageFormatVersion");
        this.activePluginManifestPath = normalizeRequired(activePluginManifestPath, "activePluginManifestPath");
    }

    public CompatibilityEvaluation evaluate(RuntimePluginPackageDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");

        ManifestSelection manifestSelection = new ManifestSelection(
                descriptor.pluginManifest().path(),
                activePluginManifestPath,
                descriptor.pluginManifest().matches(activePluginManifestPath),
                descriptor.pluginManifest().matches(activePluginManifestPath) ? "aligned" : "alternate-manifest-target"
        );

        if (descriptor.compatibility().usesManifestVersionRange()) {
            if (compareVersions(runtimeNpdevVersion, descriptor.compatibility().npdevMinVersion()) < 0) {
                return CompatibilityEvaluation.reject(
                        "NPDEV_VERSION_TOO_LOW",
                        "Package requires npdevMinVersion '%s' but runtime is '%s'"
                                .formatted(descriptor.compatibility().npdevMinVersion(), runtimeNpdevVersion),
                        descriptor.compatibility().toSummary(),
                        toSummary(),
                        manifestSelection
                );
            }
            if (compareVersions(runtimeNpdevVersion, descriptor.compatibility().npdevMaxVersion()) > 0) {
                return CompatibilityEvaluation.reject(
                        "NPDEV_VERSION_TOO_HIGH",
                        "Package npdevMaxVersion '%s' is lower than runtime '%s'"
                                .formatted(descriptor.compatibility().npdevMaxVersion(), runtimeNpdevVersion),
                        descriptor.compatibility().toSummary(),
                        toSummary(),
                        manifestSelection
                );
            }
            return CompatibilityEvaluation.allow(
                    descriptor.compatibility().toSummary(),
                    toSummary(),
                    manifestSelection
            );
        }

        if (!supportedPackageFormatVersion.equalsIgnoreCase(descriptor.packageFormatVersion())) {
            return CompatibilityEvaluation.reject(
                    "UNSUPPORTED_PACKAGE_FORMAT",
                    "Package format '%s' is not supported by runtime format '%s'"
                            .formatted(descriptor.packageFormatVersion(), supportedPackageFormatVersion),
                    descriptor.compatibility().toSummary(),
                    toSummary(),
                    manifestSelection
            );
        }
        if (!runtimeApiVersion.equalsIgnoreCase(descriptor.compatibility().npdevRuntimeApiVersion())) {
            return CompatibilityEvaluation.reject(
                    "INCOMPATIBLE_RUNTIME_API",
                    "Package requires npdevRuntimeApiVersion '%s' but runtime provides '%s'"
                            .formatted(descriptor.compatibility().npdevRuntimeApiVersion(), runtimeApiVersion),
                    descriptor.compatibility().toSummary(),
                    toSummary(),
                    manifestSelection
            );
        }
        if (compareVersions(bootstrapVersion, descriptor.compatibility().minBootstrapVersion()) < 0) {
            return CompatibilityEvaluation.reject(
                    "BOOTSTRAP_VERSION_TOO_LOW",
                    "Package requires minBootstrapVersion '%s' but runtime is '%s'"
                            .formatted(descriptor.compatibility().minBootstrapVersion(), bootstrapVersion),
                    descriptor.compatibility().toSummary(),
                    toSummary(),
                    manifestSelection
            );
        }
        if (descriptor.compatibility().maxBootstrapVersion() != null
                && compareVersions(bootstrapVersion, descriptor.compatibility().maxBootstrapVersion()) > 0) {
            return CompatibilityEvaluation.reject(
                    "BOOTSTRAP_VERSION_TOO_HIGH",
                    "Package maxBootstrapVersion '%s' is lower than runtime '%s'"
                            .formatted(descriptor.compatibility().maxBootstrapVersion(), bootstrapVersion),
                    descriptor.compatibility().toSummary(),
                    toSummary(),
                    manifestSelection
            );
        }
        return CompatibilityEvaluation.allow(
                descriptor.compatibility().toSummary(),
                toSummary(),
                manifestSelection
        );
    }

    public RuntimeCompatibilitySummary toSummary() {
        return new RuntimeCompatibilitySummary(
                runtimeApiVersion,
                bootstrapVersion,
                runtimeNpdevVersion,
                supportedPackageFormatVersion,
                activePluginManifestPath
        );
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = normalizeRequired(left, "left").split("\\.");
        String[] rightParts = normalizeRequired(right, "right").split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            int leftValue = index < leftParts.length ? parseVersionPart(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? parseVersionPart(rightParts[index]) : 0;
            int comparison = Integer.compare(leftValue, rightValue);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int parseVersionPart(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    private static String normalizeRequired(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + " must be non-blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    public record RuntimeCompatibilitySummary(
            String runtimeApiVersion,
            String bootstrapVersion,
            String runtimeNpdevVersion,
            String supportedPackageFormatVersion,
            String activePluginManifestPath
    ) {

        public RuntimeCompatibilitySummary {
            runtimeApiVersion = normalizeRequired(runtimeApiVersion, "runtimeApiVersion");
            bootstrapVersion = normalizeRequired(bootstrapVersion, "bootstrapVersion");
            runtimeNpdevVersion = normalizeRequired(runtimeNpdevVersion, "runtimeNpdevVersion");
            supportedPackageFormatVersion = normalizeRequired(supportedPackageFormatVersion, "supportedPackageFormatVersion");
            activePluginManifestPath = normalizeRequired(activePluginManifestPath, "activePluginManifestPath");
        }
    }

    public record ManifestSelection(
            String requiredPluginManifestPath,
            String activePluginManifestPath,
            boolean consistent,
            String status
    ) {

        public ManifestSelection {
            requiredPluginManifestPath = normalizeRequired(requiredPluginManifestPath, "requiredPluginManifestPath");
            activePluginManifestPath = normalizeRequired(activePluginManifestPath, "activePluginManifestPath");
            status = normalizeRequired(status, "status");
        }
    }

    public record CompatibilityEvaluation(
            boolean compatible,
            String status,
            String category,
            String reasonCode,
            String reasonMessage,
            RuntimePluginPackageDescriptor.CompatibilitySummary descriptorCompatibility,
            RuntimeCompatibilitySummary runtimeCompatibility,
            ManifestSelection manifestSelection
    ) {

        public CompatibilityEvaluation {
            status = normalizeRequired(status, "status");
            category = normalizeRequired(category, "category");
            reasonCode = normalizeOptional(reasonCode);
            reasonMessage = normalizeOptional(reasonMessage);
            descriptorCompatibility = Objects.requireNonNull(descriptorCompatibility, "descriptorCompatibility");
            runtimeCompatibility = Objects.requireNonNull(runtimeCompatibility, "runtimeCompatibility");
            manifestSelection = Objects.requireNonNull(manifestSelection, "manifestSelection");
        }

        public static CompatibilityEvaluation allow(
                RuntimePluginPackageDescriptor.CompatibilitySummary descriptorCompatibility,
                RuntimeCompatibilitySummary runtimeCompatibility,
                ManifestSelection manifestSelection
        ) {
            return new CompatibilityEvaluation(
                    true,
                    "compatible",
                    "compatibility",
                    null,
                    null,
                    descriptorCompatibility,
                    runtimeCompatibility,
                    manifestSelection
            );
        }

        public static CompatibilityEvaluation reject(
                String reasonCode,
                String reasonMessage,
                RuntimePluginPackageDescriptor.CompatibilitySummary descriptorCompatibility,
                RuntimeCompatibilitySummary runtimeCompatibility,
                ManifestSelection manifestSelection
        ) {
            return new CompatibilityEvaluation(
                    false,
                    "rejected",
                    "compatibility",
                    reasonCode,
                    reasonMessage,
                    descriptorCompatibility,
                    runtimeCompatibility,
                    manifestSelection
            );
        }
    }
}
