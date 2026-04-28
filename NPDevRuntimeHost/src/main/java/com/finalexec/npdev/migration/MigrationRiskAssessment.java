package com.finalexec.npdev.migration;

import java.util.List;

public record MigrationRiskAssessment(
        String overallRisk,
        List<String> safeChanges,
        List<String> backfillRequiredChanges,
        List<String> manualReviewChanges,
        List<String> breakingChanges
) {
}
