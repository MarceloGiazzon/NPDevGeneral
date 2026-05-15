package com.npdev.generator.migration;

import java.nio.file.Path;

public record StatefulMigrationOptions(
        String migrationMode,
        boolean migrationPlanOnly,
        MigrationRiskThreshold riskThreshold,
        Path decisionReportPath
) {
    public StatefulMigrationOptions {
        migrationMode = migrationMode == null || migrationMode.isBlank() ? "disabled" : migrationMode.trim();
        riskThreshold = riskThreshold == null ? MigrationRiskThreshold.SAFE_ADDITIVE : riskThreshold;
    }

    public static StatefulMigrationOptions disabled() {
        return new StatefulMigrationOptions("disabled", false, MigrationRiskThreshold.SAFE_ADDITIVE, null);
    }

    public boolean additiveOnly() {
        return "additive-only".equalsIgnoreCase(migrationMode);
    }
}
