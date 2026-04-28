package com.npdev.generator.migration;

import java.util.List;

public record RuntimeModelCompatibilityReport(
        boolean compatible,
        String compatibilityStatus,
        boolean deterministicPlan,
        String runtimeBuildVersion,
        String runtimeBuiltAt,
        String baselineVersion,
        String modelVersion,
        String previousHash,
        String currentHash,
        String overallRisk,
        List<String> warnings,
        List<String> blockingIssues
) {
    public RuntimeModelCompatibilityReport {
        compatibilityStatus = compatibilityStatus == null || compatibilityStatus.isBlank() ? "UNKNOWN" : compatibilityStatus.trim();
        runtimeBuildVersion = runtimeBuildVersion == null || runtimeBuildVersion.isBlank() ? "unknown" : runtimeBuildVersion.trim();
        runtimeBuiltAt = runtimeBuiltAt == null || runtimeBuiltAt.isBlank() ? "unknown" : runtimeBuiltAt.trim();
        baselineVersion = baselineVersion == null || baselineVersion.isBlank() ? "none" : baselineVersion.trim();
        modelVersion = modelVersion == null || modelVersion.isBlank() ? "unknown" : modelVersion.trim();
        previousHash = previousHash == null || previousHash.isBlank() ? "none" : previousHash.trim();
        currentHash = currentHash == null || currentHash.isBlank() ? "unknown" : currentHash.trim();
        overallRisk = overallRisk == null || overallRisk.isBlank() ? "UNKNOWN" : overallRisk.trim();
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingIssues = blockingIssues == null ? List.of() : List.copyOf(blockingIssues);
    }
}
