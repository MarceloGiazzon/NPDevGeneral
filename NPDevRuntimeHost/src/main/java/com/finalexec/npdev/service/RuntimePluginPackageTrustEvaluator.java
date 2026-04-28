package com.finalexec.npdev.service;

import com.finalexec.npdev.model.RuntimePluginPackageDescriptor;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

// verifier-token: class\s+RuntimePluginPackageTrustEvaluator|interface\s+RuntimePluginPackageTrustEvaluator
public final class RuntimePluginPackageTrustEvaluator {

    private final List<String> allowedModes;
    private final boolean trustMetadataRequired;

    public RuntimePluginPackageTrustEvaluator(List<String> allowedModes, boolean trustMetadataRequired) {
        this.allowedModes = List.copyOf(Objects.requireNonNull(allowedModes, "allowedModes").stream()
                .map(mode -> normalizeRequired(mode, "allowedMode").toLowerCase(Locale.ROOT))
                .toList());
        this.trustMetadataRequired = trustMetadataRequired;
    }

    public TrustEvaluation evaluate(RuntimePluginPackageDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");

        RuntimePluginPackageDescriptor.TrustSummary trust = descriptor.trust().toSummary();
        if (allowedModes.stream().noneMatch(mode -> mode.equalsIgnoreCase(trust.mode()))) {
            return TrustEvaluation.reject(
                    "UNSUPPORTED_TRUST_MODE",
                    "Package trust mode '%s' is not allowed by runtime policy".formatted(trust.mode()),
                    trust,
                    toSummary()
            );
        }
        return TrustEvaluation.allow(trust, toSummary());
    }

    public TrustPolicySummary toSummary() {
        return new TrustPolicySummary(trustMetadataRequired, allowedModes);
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

    public record TrustPolicySummary(
            boolean trustMetadataRequired,
            List<String> allowedModes
    ) {

        public TrustPolicySummary {
            allowedModes = List.copyOf(Objects.requireNonNull(allowedModes, "allowedModes"));
        }
    }

    public record TrustEvaluation(
            boolean trusted,
            String status,
            String category,
            String reasonCode,
            String reasonMessage,
            RuntimePluginPackageDescriptor.TrustSummary trust,
            TrustPolicySummary policy
    ) {

        public TrustEvaluation {
            status = normalizeRequired(status, "status");
            category = normalizeRequired(category, "category");
            reasonCode = normalizeOptional(reasonCode);
            reasonMessage = normalizeOptional(reasonMessage);
            trust = Objects.requireNonNull(trust, "trust");
            policy = Objects.requireNonNull(policy, "policy");
        }

        public static TrustEvaluation allow(
                RuntimePluginPackageDescriptor.TrustSummary trust,
                TrustPolicySummary policy
        ) {
            return new TrustEvaluation(true, "trusted", "trust", null, null, trust, policy);
        }

        public static TrustEvaluation reject(
                String reasonCode,
                String reasonMessage,
                RuntimePluginPackageDescriptor.TrustSummary trust,
                TrustPolicySummary policy
        ) {
            return new TrustEvaluation(false, "rejected", "trust", reasonCode, reasonMessage, trust, policy);
        }
    }
}
