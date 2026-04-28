package com.npdev.generator.migration;

import java.util.List;

public record MigrationRiskAssessment(
        boolean deterministicPlan,
        String overallRisk,
        String previousVersion,
        String currentVersion,
        String previousHash,
        String currentHash,
        int operationCount,
        List<String> safeChanges,
        List<String> backfillRequiredChanges,
        List<String> manualReviewChanges,
        List<String> breakingChanges
) {
    public MigrationRiskAssessment {
        overallRisk = overallRisk == null || overallRisk.isBlank() ? "UNKNOWN" : overallRisk.trim();
        previousVersion = previousVersion == null || previousVersion.isBlank() ? "none" : previousVersion.trim();
        currentVersion = currentVersion == null || currentVersion.isBlank() ? "unknown" : currentVersion.trim();
        previousHash = previousHash == null || previousHash.isBlank() ? "none" : previousHash.trim();
        currentHash = currentHash == null || currentHash.isBlank() ? "unknown" : currentHash.trim();
        safeChanges = safeChanges == null ? List.of() : List.copyOf(safeChanges);
        backfillRequiredChanges = backfillRequiredChanges == null ? List.of() : List.copyOf(backfillRequiredChanges);
        manualReviewChanges = manualReviewChanges == null ? List.of() : List.copyOf(manualReviewChanges);
        breakingChanges = breakingChanges == null ? List.of() : List.copyOf(breakingChanges);
    }
}
