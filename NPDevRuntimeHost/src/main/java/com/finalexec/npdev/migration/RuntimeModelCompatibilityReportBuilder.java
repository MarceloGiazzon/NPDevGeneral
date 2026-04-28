package com.finalexec.npdev.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class RuntimeModelCompatibilityReportBuilder {
    public RuntimeModelCompatibilityReport build(StorageSchemaSnapshot previous, StorageSchemaSnapshot current, Properties buildInfo) {
        MigrationRiskAssessment risk = new MigrationRiskAssessmentBuilder().build(previous, current);
        List<String> warnings = new ArrayList<>();
        List<String> blockingIssues = new ArrayList<>(risk.breakingChanges());

        warnings.addAll(risk.manualReviewChanges());
        warnings.addAll(risk.backfillRequiredChanges());

        boolean compatible = blockingIssues.isEmpty();
        String status = compatible ? (warnings.isEmpty() ? "COMPATIBLE" : "COMPATIBLE_WITH_WARNINGS") : "INCOMPATIBLE";

        String runtimeBuildVersion = buildInfo == null ? "unknown" : buildInfo.getProperty("npdev.version", "unknown");
        String runtimeBuiltAt = buildInfo == null ? "unknown" : buildInfo.getProperty("npdev.builtAt", "unknown");

        return new RuntimeModelCompatibilityReport(
                compatible,
                status,
                runtimeBuildVersion,
                runtimeBuiltAt,
                previous == null ? "none" : previous.normalized().modelVersion(),
                current == null ? "unknown" : current.normalized().modelVersion(),
                MigrationSharedSupport.hash(previous),
                MigrationSharedSupport.hash(current),
                risk.overallRisk(),
                warnings,
                blockingIssues
        );
    }
}
