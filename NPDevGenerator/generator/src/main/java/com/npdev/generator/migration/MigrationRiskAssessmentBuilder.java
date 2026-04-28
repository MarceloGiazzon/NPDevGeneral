package com.npdev.generator.migration;

import java.util.ArrayList;
import java.util.List;

public final class MigrationRiskAssessmentBuilder {

    public MigrationRiskAssessment build(StorageSchemaSnapshot previous, StorageSchemaSnapshot current) {
        ModelDiffPreview preview = new ModelDiffPreviewBuilder().build(previous, current);

        List<String> safeChanges = new ArrayList<>(preview.additiveChanges());
        List<String> backfillRequiredChanges = new ArrayList<>();
        List<String> manualReviewChanges = new ArrayList<>();
        List<String> breakingChanges = new ArrayList<>(preview.breakingChanges());

        for (String riskyChange : preview.riskyChanges()) {
            if (riskyChange == null || riskyChange.isBlank()) {
                continue;
            }
            if (riskyChange.startsWith("tighten required ")) {
                backfillRequiredChanges.add(riskyChange);
            } else {
                manualReviewChanges.add(riskyChange);
            }
        }

        String overallRisk;
        if (!breakingChanges.isEmpty()) {
            overallRisk = "BREAKING";
        } else if (!manualReviewChanges.isEmpty()) {
            overallRisk = "MANUAL_REVIEW";
        } else if (!backfillRequiredChanges.isEmpty()) {
            overallRisk = "BACKFILL_REQUIRED";
        } else {
            overallRisk = "SAFE_ADDITIVE";
        }

        return new MigrationRiskAssessment(
                preview.deterministicDiff(),
                overallRisk,
                preview.previousVersion(),
                preview.currentVersion(),
                preview.previousHash(),
                preview.currentHash(),
                preview.operationCount(),
                safeChanges,
                backfillRequiredChanges,
                manualReviewChanges,
                breakingChanges
        );
    }
}
