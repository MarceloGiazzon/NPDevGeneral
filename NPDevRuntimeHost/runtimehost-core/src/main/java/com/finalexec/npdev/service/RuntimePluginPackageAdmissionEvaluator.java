package com.finalexec.npdev.service;

import com.finalexec.npdev.model.RuntimePluginPackageDescriptor;

import java.util.List;
import java.util.Objects;

// verifier-token: class\s+RuntimePluginPackageAdmissionEvaluator|interface\s+RuntimePluginPackageAdmissionEvaluator
public final class RuntimePluginPackageAdmissionEvaluator {

    private final RuntimePluginPackageCompatibilityEvaluator compatibilityEvaluator;
    private final RuntimePluginPackageTrustEvaluator trustEvaluator;

    public RuntimePluginPackageAdmissionEvaluator(String runtimeApiVersion, String bootstrapVersion) {
        this(
                new RuntimePluginPackageCompatibilityEvaluator(runtimeApiVersion, bootstrapVersion, "1.0", "(runtime-plugin-manifest-unset)"),
                new RuntimePluginPackageTrustEvaluator(List.of("internal", "local-dev"), true)
        );
    }

    public RuntimePluginPackageAdmissionEvaluator(
            RuntimePluginPackageCompatibilityEvaluator compatibilityEvaluator,
            RuntimePluginPackageTrustEvaluator trustEvaluator
    ) {
        this.compatibilityEvaluator = Objects.requireNonNull(compatibilityEvaluator, "compatibilityEvaluator");
        this.trustEvaluator = Objects.requireNonNull(trustEvaluator, "trustEvaluator");
    }

    public AdmissionDecision evaluate(RuntimePluginPackageDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");

        RuntimePluginPackageCompatibilityEvaluator.CompatibilityEvaluation compatibilityEvaluation =
                compatibilityEvaluator.evaluate(descriptor);
        RuntimePluginPackageTrustEvaluator.TrustEvaluation trustEvaluation = trustEvaluator.evaluate(descriptor);

        if (!compatibilityEvaluation.compatible()) {
            return AdmissionDecision.reject(
                    "compatibility",
                    compatibilityEvaluation.reasonCode(),
                    compatibilityEvaluation.reasonMessage(),
                    compatibilityEvaluation,
                    trustEvaluation
            );
        }
        if (!trustEvaluation.trusted()) {
            return AdmissionDecision.reject(
                    "trust",
                    trustEvaluation.reasonCode(),
                    trustEvaluation.reasonMessage(),
                    compatibilityEvaluation,
                    trustEvaluation
            );
        }
        return AdmissionDecision.allow(compatibilityEvaluation, trustEvaluation);
    }

    public RuntimePluginPackageCompatibilityEvaluator.RuntimeCompatibilitySummary toSummary() {
        return compatibilityEvaluator.toSummary();
    }

    public RuntimePluginPackageTrustEvaluator.TrustPolicySummary trustPolicySummary() {
        return trustEvaluator.toSummary();
    }

    private static String normalizeRequired(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + " must be non-blank");
        }
        return normalized;
    }

    public record AdmissionDecision(
            boolean admitted,
            String status,
            String rejectionCategory,
            String reasonCode,
            String reasonMessage,
            String rejectionReason,
            RuntimePluginPackageCompatibilityEvaluator.CompatibilityEvaluation compatibilityEvaluation,
            RuntimePluginPackageTrustEvaluator.TrustEvaluation trustEvaluation
    ) {

        public AdmissionDecision {
            status = normalizeRequired(status, "status");
            rejectionCategory = normalizeOptional(rejectionCategory);
            reasonCode = normalizeOptional(reasonCode);
            reasonMessage = normalizeOptional(reasonMessage);
            rejectionReason = normalizeOptional(rejectionReason);
        }

        public static AdmissionDecision allow(
                RuntimePluginPackageCompatibilityEvaluator.CompatibilityEvaluation compatibilityEvaluation,
                RuntimePluginPackageTrustEvaluator.TrustEvaluation trustEvaluation
        ) {
            return new AdmissionDecision(
                    true,
                    "admitted",
                    null,
                    null,
                    null,
                    null,
                    compatibilityEvaluation,
                    trustEvaluation
            );
        }

        public static AdmissionDecision reject(String reasonCode, String reasonMessage) {
            return reject("governance", reasonCode, reasonMessage, null, null);
        }

        public static AdmissionDecision reject(
                String rejectionCategory,
                String reasonCode,
                String reasonMessage,
                RuntimePluginPackageCompatibilityEvaluator.CompatibilityEvaluation compatibilityEvaluation,
                RuntimePluginPackageTrustEvaluator.TrustEvaluation trustEvaluation
        ) {
            return new AdmissionDecision(
                    false,
                    "rejected",
                    rejectionCategory,
                    reasonCode,
                    reasonMessage,
                    reasonMessage,
                    compatibilityEvaluation,
                    trustEvaluation
            );
        }
    }

    private static String normalizeOptional(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
