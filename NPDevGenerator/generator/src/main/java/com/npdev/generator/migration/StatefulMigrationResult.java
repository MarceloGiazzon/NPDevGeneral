package com.npdev.generator.migration;

import java.nio.file.Path;

public record StatefulMigrationResult(
        boolean passed,
        boolean planOnly,
        boolean versionedFlywayMigrationGenerated,
        String overallRisk,
        String currentHash,
        int operationCount,
        Path dryRunSqlPath,
        Path versionedFlywayMigrationPath,
        Path decisionReportPath
) {
}
