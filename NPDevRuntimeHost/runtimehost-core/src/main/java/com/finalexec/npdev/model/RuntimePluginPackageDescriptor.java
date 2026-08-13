package com.finalexec.npdev.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record RuntimePluginPackageDescriptor(
        String packagePath,
        String packageFormatVersion,
        String packageId,
        String displayName,
        String version,
        String description,
        String provider,
        Compatibility compatibility,
        Trust trust,
        Signature signature,
        Provenance provenance,
        List<ArtifactRef> artifacts,
        PluginManifestRef pluginManifest,
        List<CapabilityBinding> capabilities
) {

    public RuntimePluginPackageDescriptor {
        packagePath = normalizeRequired(packagePath, "packagePath");
        packageFormatVersion = normalizeRequired(packageFormatVersion, "packageFormatVersion");
        packageId = normalizeRequired(packageId, "packageId");
        displayName = normalizeRequired(displayName, "displayName");
        version = normalizeRequired(version, "version");
        description = normalizeRequired(description, "description");
        provider = normalizeRequired(provider, "provider");
        compatibility = Objects.requireNonNull(compatibility, "compatibility");
        trust = Objects.requireNonNull(trust, "trust");
        signature = signature == null ? null : signature.normalized();
        provenance = provenance == null ? null : provenance.normalized();
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        pluginManifest = Objects.requireNonNull(pluginManifest, "pluginManifest");
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    }

    public static RuntimePluginPackageDescriptor fromJson(String packagePath, JsonNode root) {
        Objects.requireNonNull(root, "root");

        JsonNode compatibilityNode = root.path("compatibility");
        Compatibility compatibility = new Compatibility(
                compatibilityNode.path("npdevRuntimeApiVersion").asText(),
                compatibilityNode.path("minBootstrapVersion").asText(),
                compatibilityNode.path("maxBootstrapVersion").asText(null),
                null,
                null
        );
        JsonNode trustNode = root.path("trust");
        Trust trust = new Trust(
                trustNode.path("mode").asText(),
                trustNode.path("source").asText(),
                trustNode.path("level").asText()
        );

        Signature signature = null;
        JsonNode signatureNode = root.path("signature");
        if (signatureNode.isObject() && signatureNode.size() > 0) {
            signature = new Signature(
                    signatureNode.path("algorithm").asText(null),
                    signatureNode.path("digest").asText(null),
                    signatureNode.path("status").asText(null),
                    signatureNode.path("verifiedBy").asText(null)
            );
        }

        Provenance provenance = null;
        JsonNode provenanceNode = root.path("provenance");
        if (provenanceNode.isObject() && provenanceNode.size() > 0) {
            provenance = new Provenance(
                    provenanceNode.path("sourceType").asText(null),
                    provenanceNode.path("sourceLocation").asText(null),
                    provenanceNode.path("publishedAt").asText(null),
                    provenanceNode.path("attestation").asText(null)
            );
        }

        List<ArtifactRef> artifacts = new ArrayList<>();
        for (JsonNode artifactNode : root.path("artifacts")) {
            artifacts.add(new ArtifactRef(
                    artifactNode.path("kind").asText(),
                    artifactNode.path("path").asText()
            ));
        }

        PluginManifestRef pluginManifest = new PluginManifestRef(root.path("pluginManifest").path("path").asText());
        List<CapabilityBinding> capabilities = new ArrayList<>();
        for (JsonNode capabilityNode : root.path("capabilities")) {
            capabilities.add(new CapabilityBinding(
                    capabilityNode.path("capability").asText(),
                    capabilityNode.path("operation").asText(),
                    capabilityNode.path("adapterId").asText()
            ));
        }

        return new RuntimePluginPackageDescriptor(
                packagePath,
                root.path("packageFormatVersion").asText(),
                root.path("packageId").asText(),
                root.path("displayName").asText(),
                root.path("version").asText(),
                root.path("description").asText(),
                root.path("provider").asText(),
                compatibility,
                trust,
                signature,
                provenance,
                artifacts,
                pluginManifest,
                capabilities
        );
    }

    public static RuntimePluginPackageDescriptor fromExternalManifest(String packagePath, JsonNode root) {
        Objects.requireNonNull(root, "root");

        JsonNode compatibilityNode = root.path("compatibility");
        Compatibility compatibility = new Compatibility(
                null,
                null,
                null,
                compatibilityNode.path("npdevMinVersion").asText(),
                compatibilityNode.path("npdevMaxVersion").asText()
        );

        String trustLevel = root.path("trustLevel").asText();
        Trust trust = new Trust(
                "trusted".equalsIgnoreCase(trustLevel) ? "local-dev" : "unsigned",
                "filesystem-plugin-manifest",
                trustLevel
        );

        Provenance provenance = new Provenance(
                "filesystem-folder",
                packagePath,
                null,
                "step1-plugin-manifest"
        );

        List<CapabilityBinding> capabilities = new ArrayList<>();
        for (JsonNode capabilityNode : root.path("capabilities")) {
            String capability = capabilityNode.path("capability").asText();
            String adapterId = capabilityNode.path("adapterId").asText();
            for (JsonNode operationNode : capabilityNode.path("operations")) {
                capabilities.add(new CapabilityBinding(
                        capability,
                        operationNode.asText(),
                        adapterId
                ));
            }
        }

        return new RuntimePluginPackageDescriptor(
                packagePath,
                "manifest-v1",
                root.path("packageId").asText(),
                root.path("packageId").asText(),
                root.path("version").asText(),
                "External filesystem plugin manifest discovered by runtime governance.",
                "filesystem-plugin-manifest",
                compatibility,
                trust,
                null,
                provenance,
                List.of(),
                new PluginManifestRef("npdev/plugins/external-filesystem.plugin-manifest.json"),
                capabilities
        );
    }

    public Summary toSummary(String activePluginManifestPath) {
        return new Summary(
                packagePath,
                packageFormatVersion,
                packageId,
                displayName,
                version,
                description,
                provider,
                compatibility.toSummary(),
                trust.toSummary(),
                signature == null ? null : signature.toSummary(),
                provenance == null ? null : provenance.toSummary(),
                artifacts.stream()
                        .map(ArtifactRef::toSummary)
                        .sorted(Comparator.comparing(ArtifactSummary::path, String.CASE_INSENSITIVE_ORDER))
                        .toList(),
                pluginManifest.path(),
                pluginManifest.matches(activePluginManifestPath),
                capabilities.stream()
                        .map(CapabilityBinding::toSummary)
                        .sorted(Comparator
                                .comparing(CapabilityBindingSummary::capability, String.CASE_INSENSITIVE_ORDER)
                                .thenComparing(CapabilityBindingSummary::operation, String.CASE_INSENSITIVE_ORDER)
                                .thenComparing(CapabilityBindingSummary::adapterId, String.CASE_INSENSITIVE_ORDER))
                        .toList()
        );
    }

    public record Compatibility(
            String npdevRuntimeApiVersion,
            String minBootstrapVersion,
            String maxBootstrapVersion,
            String npdevMinVersion,
            String npdevMaxVersion
    ) {

        public Compatibility {
            npdevRuntimeApiVersion = normalizeOptional(npdevRuntimeApiVersion);
            minBootstrapVersion = normalizeOptional(minBootstrapVersion);
            maxBootstrapVersion = normalizeOptional(maxBootstrapVersion);
            npdevMinVersion = normalizeOptional(npdevMinVersion);
            npdevMaxVersion = normalizeOptional(npdevMaxVersion);
            boolean packageDescriptorCompatibility =
                    npdevRuntimeApiVersion != null && minBootstrapVersion != null;
            boolean manifestRangeCompatibility =
                    npdevMinVersion != null && npdevMaxVersion != null;
            if (!packageDescriptorCompatibility && !manifestRangeCompatibility) {
                throw new IllegalArgumentException(
                        "compatibility must declare package descriptor compatibility or manifest version range"
                );
            }
        }

        public CompatibilitySummary toSummary() {
            return new CompatibilitySummary(
                    npdevRuntimeApiVersion,
                    minBootstrapVersion,
                    maxBootstrapVersion,
                    npdevMinVersion,
                    npdevMaxVersion
            );
        }

        public boolean usesManifestVersionRange() {
            return npdevMinVersion != null && npdevMaxVersion != null;
        }
    }

    public record Trust(
            String mode,
            String source,
            String level
    ) {

        public Trust {
            mode = normalizeRequired(mode, "mode").toLowerCase(Locale.ROOT);
            source = normalizeRequired(source, "source");
            level = normalizeRequired(level, "level").toLowerCase(Locale.ROOT);
        }

        public TrustSummary toSummary() {
            return new TrustSummary(mode, source, level);
        }
    }

    public record Signature(
            String algorithm,
            String digest,
            String status,
            String verifiedBy
    ) {
        public Signature normalized() {
            return new Signature(
                    normalizeOptional(algorithm),
                    normalizeOptional(digest),
                    normalizeOptional(status),
                    normalizeOptional(verifiedBy)
            );
        }

        public SignatureSummary toSummary() {
            Signature normalized = normalized();
            return new SignatureSummary(normalized.algorithm, normalized.digest, normalized.status, normalized.verifiedBy);
        }
    }

    public record Provenance(
            String sourceType,
            String sourceLocation,
            String publishedAt,
            String attestation
    ) {
        public Provenance normalized() {
            return new Provenance(
                    normalizeOptional(sourceType),
                    normalizeOptional(sourceLocation),
                    normalizeOptional(publishedAt),
                    normalizeOptional(attestation)
            );
        }

        public ProvenanceSummary toSummary() {
            Provenance normalized = normalized();
            return new ProvenanceSummary(normalized.sourceType, normalized.sourceLocation, normalized.publishedAt, normalized.attestation);
        }
    }

    public record ArtifactRef(String kind, String path) {

        public ArtifactRef {
            kind = normalizeRequired(kind, "kind").toLowerCase(Locale.ROOT);
            path = normalizeRequired(path, "path");
        }

        public ArtifactSummary toSummary() {
            return new ArtifactSummary(kind, path);
        }
    }

    public record PluginManifestRef(String path) {

        public PluginManifestRef {
            path = normalizeRequired(path, "path");
        }

        public boolean matches(String activePluginManifestPath) {
            return path.equalsIgnoreCase(normalizeRequired(activePluginManifestPath, "activePluginManifestPath"));
        }
    }

    public record CapabilityBinding(
            String capability,
            String operation,
            String adapterId
    ) {

        public CapabilityBinding {
            capability = normalizeRequired(capability, "capability");
            operation = normalizeRequired(operation, "operation");
            adapterId = normalizeRequired(adapterId, "adapterId");
        }

        public CapabilityBindingSummary toSummary() {
            return new CapabilityBindingSummary(capability, operation, adapterId);
        }
    }

    public record Summary(
            String packagePath,
            String packageFormatVersion,
            String packageId,
            String displayName,
            String version,
            String description,
            String provider,
            CompatibilitySummary compatibility,
            TrustSummary trust,
            SignatureSummary signature,
            ProvenanceSummary provenance,
            List<ArtifactSummary> artifacts,
            String pluginManifestPath,
            boolean targetsActivePluginManifest,
            List<CapabilityBindingSummary> capabilities
    ) {

        public Summary {
            packagePath = normalizeRequired(packagePath, "packagePath");
            packageFormatVersion = normalizeRequired(packageFormatVersion, "packageFormatVersion");
            packageId = normalizeRequired(packageId, "packageId");
            displayName = normalizeRequired(displayName, "displayName");
            version = normalizeRequired(version, "version");
            description = normalizeRequired(description, "description");
            provider = normalizeRequired(provider, "provider");
            compatibility = Objects.requireNonNull(compatibility, "compatibility");
            trust = Objects.requireNonNull(trust, "trust");
            artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
            pluginManifestPath = normalizeRequired(pluginManifestPath, "pluginManifestPath");
            capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        }
    }

    public record CapabilityBindingSummary(
            String capability,
            String operation,
            String adapterId
    ) {

        public CapabilityBindingSummary {
            capability = normalizeRequired(capability, "capability");
            operation = normalizeRequired(operation, "operation");
            adapterId = normalizeRequired(adapterId, "adapterId");
        }
    }

    public record CompatibilitySummary(
            String npdevRuntimeApiVersion,
            String minBootstrapVersion,
            String maxBootstrapVersion,
            String npdevMinVersion,
            String npdevMaxVersion
    ) {

        public CompatibilitySummary {
            npdevRuntimeApiVersion = normalizeOptional(npdevRuntimeApiVersion);
            minBootstrapVersion = normalizeOptional(minBootstrapVersion);
            maxBootstrapVersion = normalizeOptional(maxBootstrapVersion);
            npdevMinVersion = normalizeOptional(npdevMinVersion);
            npdevMaxVersion = normalizeOptional(npdevMaxVersion);
            boolean packageDescriptorCompatibility =
                    npdevRuntimeApiVersion != null && minBootstrapVersion != null;
            boolean manifestRangeCompatibility =
                    npdevMinVersion != null && npdevMaxVersion != null;
            if (!packageDescriptorCompatibility && !manifestRangeCompatibility) {
                throw new IllegalArgumentException(
                        "compatibility summary must declare package descriptor compatibility or manifest version range"
                );
            }
        }

        public boolean usesManifestVersionRange() {
            return npdevMinVersion != null && npdevMaxVersion != null;
        }
    }

    public record ArtifactSummary(String kind, String path) {

        public ArtifactSummary {
            kind = normalizeRequired(kind, "kind").toLowerCase(Locale.ROOT);
            path = normalizeRequired(path, "path");
        }
    }

    public record TrustSummary(
            String mode,
            String source,
            String level
    ) {

        public TrustSummary {
            mode = normalizeRequired(mode, "mode").toLowerCase(Locale.ROOT);
            source = normalizeRequired(source, "source");
            level = normalizeRequired(level, "level").toLowerCase(Locale.ROOT);
        }
    }

    public record SignatureSummary(
            String algorithm,
            String digest,
            String status,
            String verifiedBy
    ) {
    }

    public record ProvenanceSummary(
            String sourceType,
            String sourceLocation,
            String publishedAt,
            String attestation
    ) {
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
}
