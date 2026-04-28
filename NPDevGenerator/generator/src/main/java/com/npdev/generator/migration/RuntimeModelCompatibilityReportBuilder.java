package com.npdev.generator.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class RuntimeModelCompatibilityReportBuilder {

    public RuntimeModelCompatibilityReport build(StorageSchemaSnapshot previous, StorageSchemaSnapshot current, Properties buildInfo) {
        StorageSchemaSnapshot prev = previous == null ? new StorageSchemaSnapshot("none", List.of()) : previous.normalized();
        StorageSchemaSnapshot curr = current == null ? new StorageSchemaSnapshot("unknown", List.of()) : current.normalized();
        Properties props = buildInfo == null ? new Properties() : buildInfo;

        MigrationRiskAssessment assessment = new MigrationRiskAssessmentBuilder().build(prev, curr);

        List<String> warnings = new ArrayList<>();
        List<String> blockingIssues = new ArrayList<>();

        if ("none".equalsIgnoreCase(prev.modelVersion())) {
            warnings.add("baseline snapshot missing");
        }
        if (!assessment.deterministicPlan()) {
            blockingIssues.add("migration planning is not deterministic");
        }
        warnings.addAll(assessment.backfillRequiredChanges());
        warnings.addAll(assessment.manualReviewChanges());
        blockingIssues.addAll(assessment.breakingChanges());

        boolean compatible = blockingIssues.isEmpty();
        String compatibilityStatus = compatible
                ? (warnings.isEmpty() ? "COMPATIBLE" : "COMPATIBLE_WITH_WARNINGS")
                : "INCOMPATIBLE";

        return new RuntimeModelCompatibilityReport(
                compatible,
                compatibilityStatus,
                assessment.deterministicPlan(),
                firstNonBlank(props.getProperty("npdev.version"), props.getProperty("version"), "unknown"),
                firstNonBlank(props.getProperty("npdev.builtAt"), props.getProperty("builtAt"), "unknown"),
                assessment.previousVersion(),
                assessment.currentVersion(),
                assessment.previousHash(),
                assessment.currentHash(),
                assessment.overallRisk(),
                warnings,
                blockingIssues
        );
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return fallback;
    }
}
